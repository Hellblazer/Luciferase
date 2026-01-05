package com.hellblazer.luciferase.simulation.entity;

import com.hellblazer.luciferase.simulation.entity.*;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates globally unique entity IDs in a distributed system without coordination.
 * <p>
 * ID Structure (64-bit):
 * - High 16 bits: Node prefix (unique per node, 0-65535)
 * - Low 48 bits: Monotonic sequence counter (0 to 2^48 - 1 = ~281 trillion)
 * <p>
 * Properties:
 * - Collision-free across all nodes (node prefix ensures uniqueness)
 * - Monotonically increasing within a node (sequence counter)
 * - Thread-safe (AtomicLong for concurrent generation)
 * - No network coordination required
 * - Compact hex string representation
 * <p>
 * Capacity:
 * - Up to 65,536 nodes (2^16)
 * - Up to 281 trillion IDs per node (2^48)
 * - Total: 18+ quintillion unique IDs
 *
 * @author hal.hildebrand
 */
public class DistributedIDGenerator {

    private final long nodePrefix;  // High 16 bits
    private final AtomicLong sequence;  // Low 48 bits

    /**
     * Create an ID generator for a specific node.
     *
     * @param nodePrefix Unique node identifier (0-65535)
     * @throws IllegalArgumentException if nodePrefix exceeds 16 bits
     */
    public DistributedIDGenerator(long nodePrefix) {
        if (nodePrefix < 0 || nodePrefix > 0xFFFF) {
            throw new IllegalArgumentException("Node prefix must be in range [0, 65535], got: " + nodePrefix);
        }
        this.nodePrefix = nodePrefix;
        this.sequence = new AtomicLong(0);
    }

    /**
     * Generate a new globally unique ID.
     * <p>
     * Format: [16-bit node prefix][48-bit sequence]
     * <p>
     * Thread-safe: can be called concurrently from multiple threads.
     *
     * @return Hex string representation of the 64-bit ID
     */
    public String generate() {
        long seq = sequence.incrementAndGet();

        // Combine node prefix (high 16 bits) with sequence (low 48 bits)
        long id = (nodePrefix << 48) | (seq & 0xFFFF_FFFF_FFFFL);

        return Long.toHexString(id);
    }

    /**
     * Get the node prefix for this generator.
     *
     * @return Node prefix (0-65535)
     */
    public long getNodePrefix() {
        return nodePrefix;
    }

    /**
     * Get the current sequence counter value.
     * <p>
     * Note: The returned value may be stale due to concurrent generation.
     *
     * @return Current sequence count
     */
    public long getCurrentSequence() {
        return sequence.get();
    }

    @Override
    public String toString() {
        return "DistributedIDGenerator{nodePrefix=" + nodePrefix +
               ", sequence=" + sequence.get() + "}";
    }
}
