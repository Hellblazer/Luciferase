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
     * Finalize and return the hash digest as a long.
     * This method can be called multiple times and will return the same result.
     *
     * @return First 8 bytes of the hash as a little-endian long
     */
    long digest();
}
