package com.yulong.chatagent.ratelimit.capacity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunCapacityLimiterRedisIT {

    private static final String TEST_KEY = "chatagent:test:agent-run:active:" + UUID.randomUUID();
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", 6379);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    @AfterEach
    void clearActivePermits() {
        redis.delete(TEST_KEY);
    }

    @Test
    void renewalShouldNeverCreateMissingOrExpiredMember() {
        Long missing = renew("missing", 1_000L);
        assertThat(missing).isZero();
        assertThat(score("missing")).isNull();

        redis.opsForZSet().add(TEST_KEY, "expired", 0D);
        Long expired = renew("expired", 1_000L);
        assertThat(expired).isZero();
        assertThat(score("expired")).isNull();
    }

    @Test
    void concurrentRenewAndReleaseShouldAlwaysEndAbsent() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                String member = "race-" + iteration;
                assertThat(acquire(member, 5_000L)).isEqualTo(1L);
                CountDownLatch start = new CountDownLatch(1);
                Future<Long> renewal = executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return renew(member, 5_000L);
                });
                Future<Long> release = executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return release(member);
                });
                start.countDown();
                renewal.get(5, TimeUnit.SECONDS);
                release.get(5, TimeUnit.SECONDS);

                assertThat(score(member)).isNull();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void renewalShouldExtendLeasePastOriginalExpiry() throws Exception {
        String member = "long-running";
        assertThat(acquire(member, 2_000L)).isEqualTo(1L);
        Double originalExpiry = score(member);

        Thread.sleep(1_800L);
        assertThat(renew(member, 2_000L)).isEqualTo(1L);
        Double renewedExpiry = score(member);
        Thread.sleep(300L);

        assertThat(renewedExpiry).isGreaterThan(originalExpiry);
        assertThat(score(member)).isNotNull();
        assertThat(release(member)).isEqualTo(1L);
        assertThat(score(member)).isNull();
    }

    @Test
    void expiredUnreleasedMembershipShouldBeCleanedByNextAcquire() throws Exception {
        assertThat(acquire("abandoned", 100L)).isEqualTo(1L);
        Thread.sleep(180L);

        assertThat(acquire("replacement", 1_000L)).isEqualTo(1L);

        assertThat(score("abandoned")).isNull();
        assertThat(score("replacement")).isNotNull();
    }

    private static Long acquire(String member, long ttlMs) {
        return redis.execute(
                AgentRunCapacityLimiter.ACQUIRE_SCRIPT,
                List.of(TEST_KEY),
                "3", String.valueOf(ttlMs), member);
    }

    private static Long renew(String member, long ttlMs) {
        return redis.execute(
                AgentRunCapacityLimiter.RENEW_SCRIPT,
                List.of(TEST_KEY),
                String.valueOf(ttlMs), member);
    }

    private static Long release(String member) {
        return redis.execute(
                AgentRunCapacityLimiter.RELEASE_SCRIPT,
                List.of(TEST_KEY),
                member);
    }

    private static Double score(String member) {
        return redis.opsForZSet().score(TEST_KEY, member);
    }
}
