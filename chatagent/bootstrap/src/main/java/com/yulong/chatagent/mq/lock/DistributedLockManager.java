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
 * 基于 Redis 的 MQ 幂等锁/执行锁管理器。
 *
 * 这里维护两类锁：
 * 1. task lock：key = taskType + idempotencyKey，用来判断同一个任务是否 RUNNING/COMPLETED/FAILED；
 * 2. session exec lock：key = sessionId，用来保证同一个会话不会并发跑多个 Agent。
 *
 * 所有“读当前状态 + 校验 token + 更新状态/TTL”的动作都用 Lua 完成，
 * 目的是保证这些复合操作在 Redis 中具备原子性。
 */
@Component
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class DistributedLockManager {

    // task lock 的业务唯一性来自 taskType + idempotencyKey。
    private static final String KEY_PREFIX = "chatagent:mq:task-lock:";
    // session exec lock 只按 sessionId 串行化，不区分具体 turn。
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
        // token 是本次持锁凭证。后续 renew/markCompleted/release 都必须带同一个 token，
        // 防止旧 consumer 在锁过期后误删/误改新 consumer 的锁。
        String token = UUID.randomUUID().toString();
        StoredTaskLock runningState = StoredTaskLock.running(identity, token, owner, Instant.now());
        String serializedRunningState = serialize(runningState);
        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();

        for (int attempt = 0; attempt < 3; attempt++) {
            // 第一次尝试：Redis SET NX PX。成功表示当前任务没有任何状态记录，直接获得 RUNNING 锁。
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
                    // FAILED 允许被重新接手：适合人工 replay 或重复消息在失败 TTL 内重新尝试。
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
                    // 同一个任务已经有消费者在执行，调用方应 ack/等待/重投，不能并发执行。
                    return new MqTaskLockAcquisition(MqTaskLockAcquireOutcome.WAIT_REQUIRED, null, existing.status);
                }
                // COMPLETED 等终态表示重复消息，调用方可安全 ack 跳过。
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
            // session lock 没有 COMPLETED/FAILED 状态，只是一个临时互斥锁。
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
        // 续租只允许 RUNNING + token 匹配的持有者操作。
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
        // WAIT/retry handoff 时释放 RUNNING；如果 token 不匹配，Lua 会拒绝删除。
        Long deleted = stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(lease.key()), lease.token());
        return Long.valueOf(1L).equals(deleted);
    }

    public boolean releaseSessionExecLock(MqSessionExecLockLease lease) {
        Long deleted = stringRedisTemplate.execute(RELEASE_SESSION_SCRIPT, List.of(lease.key()), lease.token());
        return Long.valueOf(1L).equals(deleted);
    }

    public void markCompleted(MqTaskLockLease lease) {
        // 成功处理后写 COMPLETED，并保留 completedTtl，短时间内重复消息会被识别并跳过。
        long updated = executeSetState(lease, MqTaskLockState.COMPLETED, "", completedTtl());
        if (updated != 1L) {
            throw new IllegalStateException("Failed to mark MQ task lock as COMPLETED: " + lease.key());
        }
    }

    public void markFailed(MqTaskLockLease lease, String errorMessage) {
        // 终局失败/重试耗尽后写 FAILED，并保留 failedTtl，方便后续 replay/排查。
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
        // KEYS[1] 是 Redis key；ARGV 是 token、目标状态、时间、owner、错误信息、TTL。
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
        // 续租只改 owner/updatedAt/TTL，不改变 taskType/idempotencyKey/eventId 等身份字段。
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
        // FAILED -> RUNNING 的接手操作必须原子化，否则多个 replay 可能同时抢到。
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
        // 注意这里不单独拼 sessionId；如果 idempotencyKey 已经包含 sessionId + ":" + turnId，
        // 再拼 sessionId 只是重复信息。真正的幂等边界由业务构造 idempotencyKey 决定。
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
                -- 只有当前 token 持有者能续租；owner 用于排查“哪台实例/哪个 consumer”在持锁。
                decoded['owner'] = ARGV[2]
                -- updatedAtEpochMs 只是观测字段，TTL 才是真正决定锁是否过期的机制。
                decoded['updatedAtEpochMs'] = tonumber(ARGV[3])
                -- psetex 会同时写回 JSON 和刷新毫秒级 TTL。
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
                -- 状态转换也要求 token 匹配，防止旧 owner 把新 owner 的锁改成 COMPLETED/FAILED。
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
                -- 只删除 RUNNING 锁；COMPLETED/FAILED 是终态记录，不能被 releaseRunning 删除。
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
                -- 只有 FAILED 能被接手为新的 RUNNING；COMPLETED 不允许 replay 自动覆盖。
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
