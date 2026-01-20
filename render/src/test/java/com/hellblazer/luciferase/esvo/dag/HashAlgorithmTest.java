package com.hellblazer.luciferase.esvo.dag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for HashAlgorithm enum - validates SHA-256 availability and hasher creation
 */
class HashAlgorithmTest {

    @Test
    void testSHA256Available() {
        var algo = HashAlgorithm.SHA256;
        assertNotNull(algo);
    }

    @Test
    void testCreateHasher() {
        var algo = HashAlgorithm.SHA256;
        var hasher = algo.createHasher();
        assertNotNull(hasher);
    }

    @Test
    void testHasherConsistency() {
        var algo = HashAlgorithm.SHA256;
        var hasher1 = algo.createHasher();
        var hasher2 = algo.createHasher();

        hasher1.update(0x12345678);
        hasher2.update(0x12345678);

        assertEquals(hasher1.digest(), hasher2.digest());
    }

    @Test
    void testMultipleHashers() {
        var algo = HashAlgorithm.SHA256;
        var hashers = new ArrayList<Hasher>();
        for (int i = 0; i < 10; i++) {
            hashers.add(algo.createHasher());
        }
        assertEquals(10, hashers.size());
    }
}
