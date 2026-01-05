package com.hellblazer.luciferase.simulation.entity;

import com.hellblazer.luciferase.simulation.entity.*;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DistributedIDGenerator - collision-free ID generation across nodes.
 * <p>
 * ID Format: 64-bit long (as hex string)
 * - High 16 bits: Node prefix (unique per node)
 * - Low 48 bits: Sequence counter (0 to 2^48 - 1)
 * <p>
 * Properties:
 * - Globally unique across all nodes
 * - Monotonically increasing within a node
 * - Thread-safe (atomic sequence counter)
 * - No coordination required between nodes
 *
 * @author hal.hildebrand
 */
class DistributedIDGeneratorTest {

    @Test
    void testGenerateUniqueIDs() {
        var generator = new DistributedIDGenerator(1);

        var id1 = generator.generate();
        var id2 = generator.generate();
        var id3 = generator.generate();

        // All IDs should be unique
        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);
        assertNotEquals(id1, id3);
    }

    @Test
    void testNodePrefixEncoding() {
        // Node prefix 0x1234 should appear in high 16 bits
        var generator = new DistributedIDGenerator(0x1234);

        var id = generator.generate();

        // Decode the ID
        long idLong = Long.parseUnsignedLong(id, 16);
        long nodePrefix = idLong >>> 48;  // Extract high 16 bits

        assertEquals(0x1234, nodePrefix, "Node prefix should be encoded in high 16 bits");
    }

    @Test
    void testSequenceCounterIncrement() {
        var generator = new DistributedIDGenerator(0x0001);

        // Generate first ID (sequence = 1)
        var id1 = generator.generate();
        long id1Long = Long.parseUnsignedLong(id1, 16);
        long seq1 = id1Long & 0xFFFF_FFFF_FFFFL;  // Extract low 48 bits

        // Generate second ID (sequence = 2)
        var id2 = generator.generate();
        long id2Long = Long.parseUnsignedLong(id2, 16);
        long seq2 = id2Long & 0xFFFF_FFFF_FFFFL;

        assertEquals(seq1 + 1, seq2, "Sequence counter should increment");
    }

    @Test
    void testDifferentNodesGenerateDifferentIDs() {
        var generator1 = new DistributedIDGenerator(1);
        var generator2 = new DistributedIDGenerator(2);

        var id1 = generator1.generate();
        var id2 = generator2.generate();

        assertNotEquals(id1, id2, "Different nodes should generate different IDs");

        // Verify different node prefixes
        long id1Long = Long.parseUnsignedLong(id1, 16);
        long id2Long = Long.parseUnsignedLong(id2, 16);

        long prefix1 = id1Long >>> 48;
        long prefix2 = id2Long >>> 48;

        assertEquals(1, prefix1);
        assertEquals(2, prefix2);
    }

    @Test
    void testZeroNodePrefix() {
        var generator = new DistributedIDGenerator(0);

        var id = generator.generate();
        long idLong = Long.parseUnsignedLong(id, 16);
        long prefix = idLong >>> 48;

        assertEquals(0, prefix, "Node prefix 0 should be valid");
    }

    @Test
    void testMaxNodePrefix() {
        // Max 16-bit value
        var generator = new DistributedIDGenerator(0xFFFF);

        var id = generator.generate();
        long idLong = Long.parseUnsignedLong(id, 16);
        long prefix = idLong >>> 48;

        assertEquals(0xFFFF, prefix, "Max node prefix should be encoded correctly");
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        var generator = new DistributedIDGenerator(1);
        int threadCount = 10;
        int idsPerThread = 1000;

        Set<String> allIds = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // Generate IDs concurrently
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < idsPerThread; j++) {
                        allIds.add(generator.generate());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // All IDs should be unique (no collisions)
        assertEquals(threadCount * idsPerThread, allIds.size(),
                    "All generated IDs should be unique (thread-safe)");
    }

    @Test
    void testMonotonicIncreaseWithinNode() {
        var generator = new DistributedIDGenerator(1);

        long prevSeq = 0;
        for (int i = 0; i < 100; i++) {
            var id = generator.generate();
            long idLong = Long.parseUnsignedLong(id, 16);
            long seq = idLong & 0xFFFF_FFFF_FFFFL;

            assertTrue(seq > prevSeq, "Sequence should be monotonically increasing");
            prevSeq = seq;
        }
    }

    @Test
    void testNoCollisionsBetweenNodes() {
        // Simulate multiple nodes generating IDs
        var generator1 = new DistributedIDGenerator(1);
        var generator2 = new DistributedIDGenerator(2);
        var generator3 = new DistributedIDGenerator(3);

        Set<String> allIds = new HashSet<>();

        // Each node generates 100 IDs
        for (int i = 0; i < 100; i++) {
            allIds.add(generator1.generate());
            allIds.add(generator2.generate());
            allIds.add(generator3.generate());
        }

        // All 300 IDs should be unique
        assertEquals(300, allIds.size(), "No collisions across nodes");
    }

    @Test
    void testHexStringFormat() {
        var generator = new DistributedIDGenerator(0xABCD);

        var id = generator.generate();

        // Should be valid hex string
        assertDoesNotThrow(() -> Long.parseUnsignedLong(id, 16),
                          "ID should be valid hex string");

        // Should contain node prefix
        assertTrue(id.startsWith("abcd"), "Hex ID should start with node prefix");
    }

    @Test
    void testSequenceCapacity() {
        var generator = new DistributedIDGenerator(1);

        // Generate many IDs to verify sequence counter works
        // 48 bits = ~281 trillion max, test a smaller subset
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            ids.add(generator.generate());
        }

        assertEquals(10000, ids.size(), "Should handle large sequence counts");
    }

    @Test
    void testNodePrefixIsolation() {
        // Verify that even with same sequence, different nodes generate different IDs
        var generator1 = new DistributedIDGenerator(100);
        var generator2 = new DistributedIDGenerator(200);

        // Generate first ID from each
        var id1 = generator1.generate();
        var id2 = generator2.generate();

        // Both have sequence = 1, but different node prefixes
        long id1Long = Long.parseUnsignedLong(id1, 16);
        long id2Long = Long.parseUnsignedLong(id2, 16);

        long seq1 = id1Long & 0xFFFF_FFFF_FFFFL;
        long seq2 = id2Long & 0xFFFF_FFFF_FFFFL;

        assertEquals(seq1, seq2, "Sequences should match (both first ID)");
        assertNotEquals(id1, id2, "IDs should differ due to node prefix");
    }
}
