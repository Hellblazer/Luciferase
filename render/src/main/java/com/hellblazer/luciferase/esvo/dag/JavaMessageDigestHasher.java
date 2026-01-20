package com.hellblazer.luciferase.esvo.dag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hasher implementation using Java's MessageDigest.
 * Uses little-endian byte order for consistent cross-platform hashing.
 */
public class JavaMessageDigestHasher implements Hasher {
    private final MessageDigest digest;
    private byte[] cachedDigest;

    public JavaMessageDigestHasher(String algorithm) {
        try {
            this.digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Hash algorithm not available: " + algorithm, e);
        }
    }

    @Override
    public void update(byte value) {
        cachedDigest = null; // Invalidate cache
        digest.update(value);
    }

    @Override
    public void update(int value) {
        cachedDigest = null; // Invalidate cache
        // Big-endian byte order for consistent hashing
        digest.update((byte) (value >> 24));
        digest.update((byte) (value >> 16));
        digest.update((byte) (value >> 8));
        digest.update((byte) value);
    }

    @Override
    public void update(long value) {
        cachedDigest = null; // Invalidate cache
        // Big-endian byte order for consistent hashing
        digest.update((byte) (value >> 56));
        digest.update((byte) (value >> 48));
        digest.update((byte) (value >> 40));
        digest.update((byte) (value >> 32));
        digest.update((byte) (value >> 24));
        digest.update((byte) (value >> 16));
        digest.update((byte) (value >> 8));
        digest.update((byte) value);
    }

    @Override
    public long digest() {
        if (cachedDigest == null) {
            cachedDigest = digest.digest();
        }
        return ByteBuffer.wrap(cachedDigest, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }
}
