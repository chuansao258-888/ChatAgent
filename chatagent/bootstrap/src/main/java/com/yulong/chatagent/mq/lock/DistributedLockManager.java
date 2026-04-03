package com.yulong.chatagent.mq.lock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Redis-backed MQ task state machine that deduplicates repeated deliveries across instances.
 */
@Component
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class DistributedLockManager {

    private static final String KEY_PREFIX = "chatagent:mq:task-lock:";
    private static final String SESSION_EXEC_KEY_PREFIX = "chatagent:mq:session-exec-lock:";
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = buildRenewScript();
    private static final DefaultRedisScript<Long> SET_STATE_SCRIPT = buildSetStateScript();
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = buildReleaseScript();
    private static final DefaultRedisScript<Long> CLAIM_FAILED_SCRIPT = buildClaimFailedScript();
    private static final DefaultRedisScript<Long> RENEW_SESSION_SCRIPT = buildRenewSessionScript();
    private static final DefaultRedisScript<Long> RELEASE_SESSION_SCRIPT = buildReleaseSessionScript();

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatAgentMqProperties properties;

    public DistributedLockManager(StringRedisTemplate stringRedisTemplate,
                                  ObjectMapper objectMapper,
                                  ChatAgentMqProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public MqTaskLockAcquisition tryAcquire(MqMessageIdentity identity, String owner) {
        String key = key(identity);
        String token = UUID.randomUUID().toString();
        StoredTaskLock runningState = StoredTaskLock.running(identity, token, owner, Instant.now());
        String serializedRunningState = serialize(runningState);
        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();

        for (int attempt = 0; attempt < 3; attempt++) {
            Boolean acquired = valueOperations.setIfAbsent(key, serializedRunningState, runningTtl());
            if (Boolean.TRUE.equals(acquired)) {
                return new MqTaskLockAcquisition(
                        MqTaskLockAcquireOutcome.ACQUIRED,
                        new MqTaskLockLease(key, token, owner, identity),
                        null
                );
            }

            String existingPayload = valueOperations.get(key);
            if (existingPayload != null) {
                StoredTaskLock existing = deserialize(existingPayload);
                if (existing.status == MqTaskLockState.FAILED) {
                    long claimed = executeClaimFailed(key, serializedRunningState, runningTtl());
                    if (claimed == 1L) {
                        return new MqTaskLockAcquisition(
                                MqTaskLockAcquireOutcome.ACQUIRED,
                                new MqTaskLockLease(key, token, owner, identity),
                                null
                        );
                    }
                    if (claimed == 0L) {
                        continue;
                    }
                    existingPayload = valueOperations.get(key);
                    if (existingPayload == null) {
                        continue;
                    }
                    existing = deserialize(existingPayload);
                }
                if (existing.status == MqTaskLockState.RUNNING) {
                    return new MqTaskLockAcquisition(MqTaskLockAcquireOutcome.WAIT_REQUIRED, null, existing.status);
                }
                return new MqTaskLockAcquisition(MqTaskLockAcquireOutcome.DUPLICATE, null, existing.status);
            }
        }

        throw new IllegalStateException(
                "Failed to classify MQ task lock state after an extreme contention window: " + key
        );
    }

    public MqSessionExecLockAcquisition acquireSessionExecLock(String sessionId, String owner) {
        String key = sessionExecKey(sessionId);
        String token = UUID.randomUUID().toString();
        StoredSessionLock runningState = StoredSessionLock.running(sessionId, token, owner, Instant.now());
        String serializedRunningState = serialize(runningState);
        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();

        for (int attempt = 0; attempt < 3; attempt++) {
            Boolean acquired = valueOperations.setIfAbsent(key, serializedRunningState, sessionExecTtl());
            if (Boolean.TRUE.equals(acquired)) {
                return new MqSessionExecLockAcquisition(
                        MqTaskLockAcquireOutcome.ACQUIRED,
                        new MqSessionExecLockLease(key, token, owner, sessionId)
                );
            }
            String existingPayload = valueOperations.get(key);
            if (existingPayload != null) {
                return new MqSessionExecLockAcquisition(MqTaskLockAcquireOutcome.WAIT_REQUIRED, null);
            }
        }

        throw new IllegalStateException(
                "Failed to classify session execution lock state after an extreme contention window: " + key
        );
    }

    public boolean renew(MqTaskLockLease lease) {
        return executeRenew(lease.key(), lease.token(), lease.owner(), Instant.now(), runningTtl()) == 1L;
    }

    public boolean renewSessionExecLock(MqSessionExecLockLease lease) {
        Long renewed = stringRedisTemplate.execute(
                RENEW_SESSION_SCRIPT,
                List.of(lease.key()),
                lease.token(),
                lease.owner(),
                lease.sessionId(),
                Long.toString(Instant.now().toEpochMilli()),
                Long.toString(sessionExecTtl().toMillis())
        );
        return Long.valueOf(1L).equals(renewed);
    }

    public boolean releaseRunning(MqTaskLockLease lease) {
        Long deleted = stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(lease.key()), lease.token());
        return Long.valueOf(1L).equals(deleted);
    }

    public boolean releaseSessionExecLock(MqSessionExecLockLease lease) {
        Long deleted = stringRedisTemplate.execute(RELEASE_SESSION_SCRIPT, List.of(lease.key()), lease.token());
        return Long.valueOf(1L).equals(deleted);
    }

    public void markCompleted(MqTaskLockLease lease) {
        long updated = executeSetState(lease, MqTaskLockState.COMPLETED, "", completedTtl());
        if (updated != 1L) {
            throw new IllegalStateException("Failed to mark MQ task lock as COMPLETED: " + lease.key());
        }
    }

    public void markFailed(MqTaskLockLease lease, String errorMessage) {
        long updated = executeSetState(
                lease,
                MqTaskLockState.FAILED,
                errorMessage == null ? "" : errorMessage,
                failedTtl()
        );
        if (updated != 1L) {
            throw new IllegalStateException("Failed to mark MQ task lock as FAILED: " + lease.key());
        }
    }

    private long executeSetState(MqTaskLockLease lease,
                                 MqTaskLockState state,
                                 String errorMessage,
                                 Duration ttl) {
        Long updated = stringRedisTemplate.execute(
                SET_STATE_SCRIPT,
                List.of(lease.key()),
                lease.token(),
                state.name(),
                Long.toString(Instant.now().toEpochMilli()),
                lease.owner(),
                errorMessage,
                Long.toString(ttl.toMillis())
        );
        return updated == null ? 0L : updated;
    }

    private long executeRenew(String key,
                              String token,
                              String owner,
                              Instant now,
                              Duration ttl) {
        Long renewed = stringRedisTemplate.execute(
                RENEW_SCRIPT,
                List.of(key),
                token,
                owner,
                Long.toString(now.toEpochMilli()),
                Long.toString(ttl.toMillis())
        );
        return renewed == null ? 0L : renewed;
    }

    private long executeClaimFailed(String key, String serializedRunningState, Duration ttl) {
        Long claimed = stringRedisTemplate.execute(
                CLAIM_FAILED_SCRIPT,
                List.of(key),
                serializedRunningState,
                Long.toString(ttl.toMillis())
        );
        return claimed == null ? 0L : claimed;
    }

    private Duration runningTtl() {
        return Duration.ofMillis(properties.getLocks().getRunningTtlMs());
    }

    private Duration completedTtl() {
        return Duration.ofMillis(properties.getLocks().getCompletedTtlMs());
    }

    private Duration sessionExecTtl() {
        return Duration.ofMillis(properties.getLocks().getSessionExecTtlMs());
    }

    private Duration failedTtl() {
        return Duration.ofMillis(properties.getLocks().getFailedTtlMs());
    }

    private String key(MqMessageIdentity identity) {
        return KEY_PREFIX + identity.taskType() + ":" + identity.idempotencyKey();
    }

    private String sessionExecKey(String sessionId) {
        return SESSION_EXEC_KEY_PREFIX + sessionId;
    }

    private String serialize(Object state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize MQ task lock state", e);
        }
    }

    private StoredTaskLock deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, StoredTaskLock.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize MQ task lock state", e);
        }
    }

    private static DefaultRedisScript<Long> buildRenewScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("""
                local current = redis.call('get', KEYS[1])
                if not current then
                    return 0
                end
                local decoded = cjson.decode(current)
                if decoded['status'] ~= 'RUNNING' or decoded['token'] ~= ARGV[1] then
                    return 0
                end
                decoded['owner'] = ARGV[2]
                decoded['updatedAtEpochMs'] = tonumber(ARGV[3])
                redis.call('psetex', KEYS[1], tonumber(ARGV[4]), cjson.encode(decoded))
                return 1
                """);
        script.setResultType(Long.class);
        return script;
    }

    private static DefaultRedisScript<Long> buildSetStateScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("""
                local current = redis.call('get', KEYS[1])
                if not current then
                    return 0
                end
                local decoded = cjson.decode(current)
                if decoded['token'] ~= ARGV[1] then
                    return 0
                end
                decoded['status'] = ARGV[2]
                decoded['updatedAtEpochMs'] = tonumber(ARGV[3])
                decoded['owner'] = ARGV[4]
                decoded['lastError'] = ARGV[5]
                redis.call('psetex', KEYS[1], tonumber(ARGV[6]), cjson.encode(decoded))
                return 1
                """);
        script.setResultType(Long.class);
        return script;
    }

    private static DefaultRedisScript<Long> buildReleaseScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("""
                local current = redis.call('get', KEYS[1])
                if not current then
                    return 0
                end
                local decoded = cjson.decode(current)
                if decoded['status'] ~= 'RUNNING' or decoded['token'] ~= ARGV[1] then
                    return 0
                end
                return redis.call('del', KEYS[1])
                """);
        script.setResultType(Long.class);
        return script;
    }

    private static DefaultRedisScript<Long> buildClaimFailedScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("""
                local current = redis.call('get', KEYS[1])
                if not current then
                    return 0
                end
                local decoded = cjson.decode(current)
                if decoded['status'] ~= 'FAILED' then
                    return -1
                end
                redis.call('psetex', KEYS[1], tonumber(ARGV[2]), ARGV[1])
                return 1
                """);
        script.setResultType(Long.class);
        return script;
    }

    private static DefaultRedisScript<Long> buildRenewSessionScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("""
                local current = redis.call('get', KEYS[1])
                if not current then
                    return 0
                end
                local decoded = cjson.decode(current)
                if decoded['token'] ~= ARGV[1] then
                    return 0
                end
                decoded['owner'] = ARGV[2]
                decoded['sessionId'] = ARGV[3]
                decoded['updatedAtEpochMs'] = tonumber(ARGV[4])
                redis.call('psetex', KEYS[1], tonumber(ARGV[5]), cjson.encode(decoded))
                return 1
                """);
        script.setResultType(Long.class);
        return script;
    }

    private static DefaultRedisScript<Long> buildReleaseSessionScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("""
                local current = redis.call('get', KEYS[1])
                if not current then
                    return 0
                end
                local decoded = cjson.decode(current)
                if decoded['token'] ~= ARGV[1] then
                    return 0
                end
                return redis.call('del', KEYS[1])
                """);
        script.setResultType(Long.class);
        return script;
    }

    private static final class StoredTaskLock {
        public MqTaskLockState status;
        public String token;
        public String owner;
        public String taskType;
        public String eventId;
        public String idempotencyKey;
        public String traceId;
        public long updatedAtEpochMs;
        public String lastError;

        public StoredTaskLock() {
        }

        private static StoredTaskLock running(MqMessageIdentity identity, String token, String owner, Instant now) {
            StoredTaskLock record = new StoredTaskLock();
            record.status = MqTaskLockState.RUNNING;
            record.token = token;
            record.owner = owner;
            record.taskType = identity.taskType();
            record.eventId = identity.eventId();
            record.idempotencyKey = identity.idempotencyKey();
            record.traceId = identity.traceId();
            record.updatedAtEpochMs = now.toEpochMilli();
            record.lastError = "";
            return record;
        }
    }

    private static final class StoredSessionLock {
        public String token;
        public String owner;
        public String sessionId;
        public long updatedAtEpochMs;

        public StoredSessionLock() {
        }

        private static StoredSessionLock running(String sessionId, String token, String owner, Instant now) {
            StoredSessionLock record = new StoredSessionLock();
            record.token = token;
            record.owner = owner;
            record.sessionId = sessionId;
            record.updatedAtEpochMs = now.toEpochMilli();
            return record;
        }
    }
}
