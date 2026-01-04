package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BubbleEvent sealed interface and its event record types.
 * <p>
 * Validates:
 * - Event creation and immutability
 * - Helper methods (smallerBubble, totalSize, etc.)
 * - Validation logic (affinity ranges, component counts)
 * - Event type identification
 *
 * @author hal.hildebrand
 */
class BubbleEventTest {

    // Simple EntityID for testing
    static class TestEntityID implements EntityID {
        private final String id;

        TestEntityID(String id) {
            this.id = id;
        }

        @Override
        public String toDebugString() {
            return id;
        }

        @Override
        public int compareTo(EntityID other) {
            return id.compareTo(other.toDebugString());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TestEntityID that)) return false;
            return id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    @Test
    void testMergeEvent() {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        var merge = new BubbleEvent.Merge(
            bubble1,
            bubble2,
            bubble2,  // bubble2 is result (larger)
            100L,
            50,   // bubble1 size
            150   // bubble2 size
        );

        assertEquals(bubble1, merge.bubble1());
        assertEquals(bubble2, merge.bubble2());
        assertEquals(bubble2, merge.result());
        assertEquals(100L, merge.bucket());
        assertEquals(50, merge.size1());
        assertEquals(150, merge.size2());
        assertEquals(200, merge.totalSize());

        // Verify smaller/larger detection
        assertEquals(bubble1, merge.smallerBubble());
        assertEquals(bubble2, merge.largerBubble());

        assertEquals("Merge", merge.eventType());
    }

    @Test
    void testMergeEventEqualSizes() {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        var merge = new BubbleEvent.Merge(
            bubble1,
            bubble2,
            bubble2,
            100L,
            100,  // Equal sizes
            100
        );

        // When equal, bubble1 is returned as smaller (by logic: size1 < size2 ? bubble2 : bubble1)
        assertEquals(bubble1, merge.largerBubble());
        assertEquals(bubble2, merge.smallerBubble());
    }

    @Test
    void testSplitEvent() {
        var source = UUID.randomUUID();
        var component1 = UUID.randomUUID();
        var component2 = UUID.randomUUID();

        var split = new BubbleEvent.Split(
            source,
            List.of(source, component1, component2),
            100L,
            List.of(50, 30, 20)
        );

        assertEquals(source, split.source());
        assertEquals(3, split.componentCount());
        assertEquals(100, split.totalSize());
        assertEquals(50, split.sizeOf(0));
        assertEquals(30, split.sizeOf(1));
        assertEquals(20, split.sizeOf(2));
        assertEquals(100L, split.bucket());

        assertTrue(split.components().contains(source),
                  "Components must include source bubble");

        assertEquals("Split", split.eventType());
    }

    @Test
    void testSplitEventValidation_MismatchedCounts() {
        var source = UUID.randomUUID();
        var component1 = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                    () -> new BubbleEvent.Split(
                        source,
                        List.of(source, component1),
                        100L,
                        List.of(50)  // Size count doesn't match component count
                    ),
                    "Should reject mismatched component/size counts");
    }

    @Test
    void testSplitEventValidation_EmptyComponents() {
        var source = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                    () -> new BubbleEvent.Split(
                        source,
                        List.of(),
                        100L,
                        List.of()
                    ),
                    "Should reject empty component list");
    }

    @Test
    void testSplitEventValidation_MissingSource() {
        var source = UUID.randomUUID();
        var component1 = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                    () -> new BubbleEvent.Split(
                        source,
                        List.of(component1),  // Missing source in components
                        100L,
                        List.of(50)
                    ),
                    "Should reject components that don't include source");
    }

    @Test
    void testEntityTransferEvent() {
        var entityId = new TestEntityID("entity-1");
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        var transfer = new BubbleEvent.EntityTransfer(
            entityId,
            sourceBubble,
            targetBubble,
            100L,
            0.7f,  // High affinity with target
            5L     // Epoch 5
        );

        assertEquals(entityId, transfer.entityId());
        assertEquals(sourceBubble, transfer.sourceBubble());
        assertEquals(targetBubble, transfer.targetBubble());
        assertEquals(100L, transfer.bucket());
        assertEquals(0.7f, transfer.affinity(), 0.01f);
        assertEquals(5L, transfer.epoch());

        assertFalse(transfer.isDrifting(),
                   "High affinity (0.7) should not be drifting");

        assertEquals("EntityTransfer", transfer.eventType());
    }

    @Test
    void testEntityTransferEvent_Drifting() {
        var entityId = new TestEntityID("entity-1");
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        var transfer = new BubbleEvent.EntityTransfer(
            entityId,
            sourceBubble,
            targetBubble,
            100L,
            0.3f,  // Low affinity with source
            5L
        );

        assertTrue(transfer.isDrifting(),
                  "Low affinity (0.3) should be drifting");
    }

    @Test
    void testEntityTransferValidation_SameBubble() {
        var entityId = new TestEntityID("entity-1");
        var bubbleId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                    () -> new BubbleEvent.EntityTransfer(
                        entityId,
                        bubbleId,
                        bubbleId,  // Same bubble
                        100L,
                        0.5f,
                        1L
                    ),
                    "Should reject transfer to same bubble");
    }

    @Test
    void testEntityTransferValidation_InvalidAffinity() {
        var entityId = new TestEntityID("entity-1");
        var sourceBubble = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                    () -> new BubbleEvent.EntityTransfer(
                        entityId,
                        sourceBubble,
                        targetBubble,
                        100L,
                        1.5f,  // Invalid affinity > 1.0
                        1L
                    ),
                    "Should reject affinity > 1.0");

        assertThrows(IllegalArgumentException.class,
                    () -> new BubbleEvent.EntityTransfer(
                        entityId,
                        sourceBubble,
                        targetBubble,
                        100L,
                        -0.1f,  // Invalid affinity < 0.0
                        1L
                    ),
                    "Should reject affinity < 0.0");
    }

    @Test
    void testBubbleMigrationEvent() {
        var bubbleId = UUID.randomUUID();
        var sourceNode = UUID.randomUUID();
        var targetNode = UUID.randomUUID();

        var migration = new BubbleEvent.BubbleMigration(
            bubbleId,
            sourceNode,
            targetNode,
            100L,
            150  // Entity count
        );

        assertEquals(bubbleId, migration.bubbleId());
        assertEquals(sourceNode, migration.sourceNode());
        assertEquals(targetNode, migration.targetNode());
        assertEquals(100L, migration.bucket());
        assertEquals(150, migration.entityCount());

        assertTrue(migration.isLocalMigration(),
                  "Migration with both nodes known is local");

        assertEquals("BubbleMigration", migration.eventType());
    }

    @Test
    void testBubbleMigrationEvent_RemoteMigration() {
        var bubbleId = UUID.randomUUID();

        var migration = new BubbleEvent.BubbleMigration(
            bubbleId,
            null,  // Unknown source
            null,  // Unknown target
            100L,
            150
        );

        assertFalse(migration.isLocalMigration(),
                   "Migration with unknown nodes is not local");
    }

    @Test
    void testPartitionDetectedEvent() {
        var partition = new BubbleEvent.PartitionDetected(
            100L,
            3,   // Known neighbors
            11,  // Expected neighbors
            0.27f // NC < 0.3 (severe)
        );

        assertEquals(100L, partition.bucket());
        assertEquals(3, partition.knownNeighbors());
        assertEquals(11, partition.expectedNeighbors());
        assertEquals(0.27f, partition.nc(), 0.01f);
        assertEquals(8, partition.missingNeighbors());

        assertTrue(partition.isSevere(),
                  "NC < 0.3 is severe partition");

        assertEquals("PartitionDetected", partition.eventType());
    }

    @Test
    void testPartitionDetectedEvent_NotSevere() {
        var partition = new BubbleEvent.PartitionDetected(
            100L,
            6,
            10,
            0.6f  // NC = 0.6 (not severe)
        );

        assertFalse(partition.isSevere(),
                   "NC >= 0.3 is not severe");
    }

    @Test
    void testPartitionDetectedValidation_InvalidNC() {
        assertThrows(IllegalArgumentException.class,
                    () -> new BubbleEvent.PartitionDetected(
                        100L,
                        3,
                        10,
                        1.5f  // Invalid NC > 1.0
                    ),
                    "Should reject NC > 1.0");

        assertThrows(IllegalArgumentException.class,
                    () -> new BubbleEvent.PartitionDetected(
                        100L,
                        3,
                        10,
                        -0.1f  // Invalid NC < 0.0
                    ),
                    "Should reject NC < 0.0");
    }

    @Test
    void testPartitionRecoveredEvent() {
        var recovery = new BubbleEvent.PartitionRecovered(
            200L,
            10,   // Fully recovered
            10,
            1.0f, // NC = 1.0
            50L   // Recovery took 50 buckets (5 seconds)
        );

        assertEquals(200L, recovery.bucket());
        assertEquals(10, recovery.knownNeighbors());
        assertEquals(10, recovery.expectedNeighbors());
        assertEquals(1.0f, recovery.nc(), 0.01f);
        assertEquals(50L, recovery.recoveryDuration());

        assertTrue(recovery.isFullRecovery(),
                  "NC = 1.0 is full recovery");

        assertEquals("PartitionRecovered", recovery.eventType());
    }

    @Test
    void testPartitionRecoveredEvent_PartialRecovery() {
        var recovery = new BubbleEvent.PartitionRecovered(
            200L,
            9,
            10,
            0.9f, // NC = 0.9 (partial recovery)
            50L
        );

        assertFalse(recovery.isFullRecovery(),
                   "NC < 1.0 is not full recovery");
    }

    @Test
    void testEventPolymorphism() {
        BubbleEvent mergeEvent = new BubbleEvent.Merge(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            100L,
            50,
            100
        );

        var sourceId = UUID.randomUUID();
        BubbleEvent splitEvent = new BubbleEvent.Split(
            sourceId,
            List.of(sourceId, UUID.randomUUID()),  // Include source in components
            100L,
            List.of(50, 50)
        );

        assertEquals("Merge", mergeEvent.eventType());
        assertEquals("Split", splitEvent.eventType());
        assertEquals(100L, mergeEvent.bucket());
        assertEquals(100L, splitEvent.bucket());
    }

    @Test
    void testEventImmutability() {
        var components = List.of(UUID.randomUUID(), UUID.randomUUID());
        var sizes = List.of(50, 50);

        var split = new BubbleEvent.Split(
            components.get(0),
            components,
            100L,
            sizes
        );

        // Records are immutable - components list cannot be modified
        assertThrows(UnsupportedOperationException.class,
                    () -> split.components().add(UUID.randomUUID()),
                    "Event components should be unmodifiable");
    }
}
