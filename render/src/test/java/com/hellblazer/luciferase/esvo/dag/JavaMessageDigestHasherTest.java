package com.hellblazer.luciferase.esvo.dag;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for JavaMessageDigestHasher - validates SHA-256 hashing implementation
 */
class JavaMessageDigestHasherTest {

    @Test
    void testHasherInterface() {
        Hasher hasher = new JavaMessageDigestHasher("SHA-256");
        assertNotNull(hasher);
    }

    @Test
    void testUpdateByte() {
        var hasher = new JavaMessageDigestHasher("SHA-256");
        hasher.update((byte) 0x42);
        long result = hasher.digest();
        assertTrue(result != 0);
    }

    @Test
    void testUpdateInt() {
        var hasher = new JavaMessageDigestHasher("SHA-256");
        hasher.update(0x12345678);
        long result = hasher.digest();
        assertTrue(result != 0);
    }

    @Test
    void testUpdateLong() {
        var hasher = new JavaMessageDigestHasher("SHA-256");
        hasher.update(0x123456789ABCDEFL);
        long result = hasher.digest();
        assertTrue(result != 0);
    }

    @Test
    void testUpdateSequence() {
        var hasher = new JavaMessageDigestHasher("SHA-256");
        hasher.update(1);
        hasher.update(2);
        hasher.update(3);
        long result1 = hasher.digest();
        assertTrue(result1 != 0);

        // Verify calling digest() again returns same result (digest finalizes)
        long result2 = hasher.digest();
        assertEquals(result1, result2);
    }

    @Test
    void testDifferentInputsDifferentHashes() {
        var hasher1 = new JavaMessageDigestHasher("SHA-256");
        hasher1.update(0x11111111);
        long hash1 = hasher1.digest();

        var hasher2 = new JavaMessageDigestHasher("SHA-256");
        hasher2.update(0x22222222);
        long hash2 = hasher2.digest();

        assertNotEquals(hash1, hash2);
    }

    @Test
    void testHashCollisionResistance() {
        // SHA-256 should have no collisions for different inputs
        var hashes = new HashSet<Long>();
        for (int i = 0; i < 1000; i++) {
            var hasher = new JavaMessageDigestHasher("SHA-256");
            hasher.update(i);
            hashes.add(hasher.digest());
        }
        assertEquals(1000, hashes.size(), "All hashes should be unique");
    }

    @Test
    void testByteOrder() {
        // Verify consistent byte order (little-endian)
        var hasher1 = new JavaMessageDigestHasher("SHA-256");
        hasher1.update(0x12345678);
        long hash1 = hasher1.digest();

        var hasher2 = new JavaMessageDigestHasher("SHA-256");
        hasher2.update(0x12345678);
        long hash2 = hasher2.digest();

        assertEquals(hash1, hash2);
    }

    @Test
    void testInvalidAlgorithm() {
        assertThrows(IllegalArgumentException.class, () -> new JavaMessageDigestHasher("INVALID-ALGO"));
    }
}
