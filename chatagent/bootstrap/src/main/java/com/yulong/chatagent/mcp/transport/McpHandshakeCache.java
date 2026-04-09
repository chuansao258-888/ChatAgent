package com.yulong.chatagent.mcp.transport;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * In-memory single-flight guard for one server's handshake sequence.
 */
@Component
public class McpHandshakeCache {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T runSingleFlight(String serverId, Supplier<T> supplier) {
        ReentrantLock lock = locks.computeIfAbsent(serverId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                locks.remove(serverId, lock);
            }
        }
    }
}
