package com.yulong.chatagent.mq.outbox;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * RFC 4122 name-based UUIDv5 generator for deterministic outbox identifiers.
 */
public final class UuidV5Generator {

    private UuidV5Generator() {
    }

    public static UUID generate(UUID namespace, String name) {
        if (namespace == null) {
            throw new IllegalArgumentException("namespace must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(toBytes(namespace));
            sha1.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = sha1.digest();
            hash[6] &= 0x0f;
            hash[6] |= 0x50;
            hash[8] &= 0x3f;
            hash[8] |= (byte) 0x80;
            ByteBuffer buffer = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is unavailable for UUIDv5 generation", e);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }
}
