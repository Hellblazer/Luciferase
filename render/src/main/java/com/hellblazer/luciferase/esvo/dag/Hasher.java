package com.hellblazer.luciferase.esvo.dag;

/**
 * Interface for cryptographic hash computation supporting incremental updates.
 * Implementations must provide thread-safe digest computation.
 */
public interface Hasher {
    /**
     * Update the hash with a single byte
     */
    void update(byte value);

    /**
     * Update the hash with an integer (32 bits)
     */
    void update(int value);

    /**
     * Update the hash with a long (64 bits)
     */
    void update(long value);

    /**
     * Finalize and return the full hash digest as a byte array.
     * This method can be called multiple times and will return the same result.
     * The returned array must not be mutated by callers.
     *
     * @return full hash digest bytes
     */
    byte[] digestBytes();

    /**
     * Finalize and return the hash digest as a long.
     * This is a truncated form (first 8 bytes) retained for backward compatibility.
     * Do NOT use for deduplication keys — use {@link #digestBytes()} instead.
     *
     * @return First 8 bytes of the hash as a little-endian long
     * @deprecated Use {@link #digestBytes()} for collision-safe deduplication keys.
     */
    @Deprecated
    default long digest() {
        var bytes = digestBytes();
        long result = 0L;
        for (int i = 0; i < 8; i++) {
            result |= ((long) (bytes[i] & 0xFF)) << (i * 8);
        }
        return result;
    }
}
