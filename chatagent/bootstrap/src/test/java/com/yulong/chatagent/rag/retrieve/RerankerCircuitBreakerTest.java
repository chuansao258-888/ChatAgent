package com.yulong.chatagent.rag.retrieve;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class RerankerCircuitBreakerTest {

    private RerankerProperties properties;
    private RerankerCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        properties = new RerankerProperties();
        properties.setFailureThreshold(3);
        properties.setMinimumRequestVolume(5);
        properties.setOpenStateMs(1000);
        properties.setHalfOpenProbeCount(1);
        
        circuitBreaker = new RerankerCircuitBreaker("test-provider", properties);
    }

    @Test
    void testInitialStateIsClosed() {
        assertEquals(RerankerCircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.allowRequest());
    }

    @Test
    void testOpenCircuitAfterFailures() {
        // Record 4 failures (less than minimum volume 5)
        for (int i = 0; i < 4; i++) {
            circuitBreaker.recordFailure();
            assertEquals(RerankerCircuitBreaker.State.CLOSED, circuitBreaker.getState());
        }

        // 5th failure reaches minimum volume and threshold
        circuitBreaker.recordFailure();
        assertEquals(RerankerCircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.allowRequest());
    }

    @Test
    void testTransitionToHalfOpenAfterTimeout() throws InterruptedException {
        // Open the circuit
        for (int i = 0; i < 5; i++) {
            circuitBreaker.recordFailure();
        }
        assertEquals(RerankerCircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Wait for openStateMs
        Thread.sleep(1100);

        assertTrue(circuitBreaker.allowRequest());
        assertEquals(RerankerCircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
    }

    @Test
    void testHalfOpenProbeCount() throws InterruptedException {
        // Open the circuit
        for (int i = 0; i < 5; i++) {
            circuitBreaker.recordFailure();
        }
        
        Thread.sleep(1100);
        
        // First request allowed (transitions to HALF_OPEN)
        assertTrue(circuitBreaker.allowRequest());
        
        // Second request not allowed if probe count is 1
        assertFalse(circuitBreaker.allowRequest());
    }

    @Test
    void testRecoveryFromHalfOpen() throws InterruptedException {
        // Open the circuit
        for (int i = 0; i < 5; i++) {
            circuitBreaker.recordFailure();
        }
        
        Thread.sleep(1100);
        circuitBreaker.allowRequest(); // transitions to HALF_OPEN
        
        circuitBreaker.recordSuccess();
        assertEquals(RerankerCircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.allowRequest());
    }

    @Test
    void testFailureInHalfOpenOpensAgain() throws InterruptedException {
        // Open the circuit
        for (int i = 0; i < 5; i++) {
            circuitBreaker.recordFailure();
        }
        
        Thread.sleep(1100);
        circuitBreaker.allowRequest(); // transitions to HALF_OPEN
        
        circuitBreaker.recordFailure();
        assertEquals(RerankerCircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.allowRequest());
    }

    @Test
    void testHalfOpenOnlyAllowsSingleConcurrentProbe() throws Exception {
        for (int i = 0; i < 5; i++) {
            circuitBreaker.recordFailure();
        }

        Thread.sleep(1100);

        ExecutorService executorService = Executors.newFixedThreadPool(4);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                futures.add(executorService.submit(() -> {
                    startLatch.await();
                    return circuitBreaker.allowRequest();
                }));
            }

            startLatch.countDown();

            int allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    allowed++;
                }
            }
            assertEquals(1, allowed);
            assertEquals(RerankerCircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void testSuccessesDiluteFailuresWithinWindow() {
        properties.setFailureRateThresholdPercent(50);
        circuitBreaker = new RerankerCircuitBreaker("test-provider", properties);

        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }
        for (int i = 0; i < 7; i++) {
            circuitBreaker.recordSuccess();
        }

        assertEquals(RerankerCircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.allowRequest());
    }
}
