package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SimulationGhostEntity - wrapper around GhostEntity with simulation metadata.
 *
 * @author hal.hildebrand
 */
class SimulationGhostEntityTest {

    private GhostZoneManager.GhostEntity<TestEntityID, String> ghostEntity;
    private UUID sourceBubbleId;
    private Point3f position;
    private EntityBounds bounds;

    // Simple EntityID implementation for testing
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

    @BeforeEach
    void setUp() {
        var entityId = new TestEntityID("entity-123");
        position = new Point3f(1.0f, 2.0f, 3.0f);
        bounds = new EntityBounds(position, 0.5f);  // center, radius
        sourceBubbleId = UUID.randomUUID();

        ghostEntity = new GhostZoneManager.GhostEntity<>(
            entityId,
            "test-content",
            position,
            bounds,
            "tree-A"
        );
    }

    @Test
    void testSimulationGhostCreation() {
        var simGhost = new SimulationGhostEntity<>(
            ghostEntity,
            sourceBubbleId,
            100L,  // bucket
            5L,    // epoch
            10L    // version
        );

        assertNotNull(simGhost);
        assertEquals(ghostEntity, simGhost.ghost());
        assertEquals(sourceBubbleId, simGhost.sourceBubbleId());
        assertEquals(100L, simGhost.bucket());
        assertEquals(5L, simGhost.epoch());
        assertEquals(10L, simGhost.version());
    }

    @Test
    void testConvenienceAccessors() {
        var simGhost = new SimulationGhostEntity<>(
            ghostEntity,
            sourceBubbleId,
            100L, 5L, 10L
        );

        // Verify delegation to ghost entity
        assertEquals(ghostEntity.getEntityId(), simGhost.entityId());
        assertEquals(ghostEntity.getContent(), simGhost.content());
        assertEquals(ghostEntity.getPosition(), simGhost.position());
        assertEquals(ghostEntity.getBounds(), simGhost.bounds());
        assertEquals(ghostEntity.getSourceTreeId(), simGhost.sourceTreeId());
        assertEquals(ghostEntity.getTimestamp(), simGhost.timestamp());
    }

    @Test
    void testAuthorityAccessor() {
        var simGhost = new SimulationGhostEntity<>(
            ghostEntity,
            sourceBubbleId,
            100L, 5L, 10L
        );

        var authority = simGhost.authority();

        assertNotNull(authority);
        assertEquals(5L, authority.epoch());
        assertEquals(10L, authority.version());
    }

    @Test
    void testIsNewerThan() {
        var simGhost1 = new SimulationGhostEntity<>(
            ghostEntity,
            sourceBubbleId,
            100L, 5L, 10L  // epoch 5, version 10
        );

        var simGhost2 = new SimulationGhostEntity<>(
            ghostEntity,
            sourceBubbleId,
            101L, 5L, 9L   // epoch 5, version 9 (older)
        );

        assertTrue(simGhost1.isNewerThan(simGhost2),
                  "Ghost with higher version should be newer");
        assertFalse(simGhost2.isNewerThan(simGhost1),
                   "Ghost with lower version should not be newer");
    }

    @Test
    void testIsNewerThan_DifferentEpochs() {
        var simGhost1 = new SimulationGhostEntity<>(
            ghostEntity,
            sourceBubbleId,
            100L, 6L, 0L   // epoch 6, version 0
        );

        var simGhost2 = new SimulationGhostEntity<>(
            ghostEntity,
            sourceBubbleId,
            101L, 5L, 100L  // epoch 5, version 100 (older epoch)
        );

        assertTrue(simGhost1.isNewerThan(simGhost2),
                  "Higher epoch should be newer, even with lower version");
    }

    @Test
    void testIsFromBubble() {
        var bubbleA = UUID.randomUUID();
        var bubbleB = UUID.randomUUID();

        var simGhost = new SimulationGhostEntity<>(
            ghostEntity,
            bubbleA,
            100L, 5L, 10L
        );

        assertTrue(simGhost.isFromBubble(bubbleA),
                  "Should match source bubble");
        assertFalse(simGhost.isFromBubble(bubbleB),
                   "Should not match different bubble");
    }

    @Test
    void testRecordSemantics() {
        var bubbleId = UUID.randomUUID();

        var simGhost1 = new SimulationGhostEntity<>(
            ghostEntity,
            bubbleId,
            100L, 5L, 10L
        );

        var simGhost2 = new SimulationGhostEntity<>(
            ghostEntity,
            bubbleId,
            100L, 5L, 10L
        );

        // Records with same values should be equal
        assertEquals(simGhost1, simGhost2);
        assertEquals(simGhost1.hashCode(), simGhost2.hashCode());
    }

    @Test
    void testDifferentMetadata() {
        var bubbleId = UUID.randomUUID();

        var simGhost1 = new SimulationGhostEntity<>(
            ghostEntity,
            bubbleId,
            100L, 5L, 10L
        );

        var simGhost2 = new SimulationGhostEntity<>(
            ghostEntity,
            bubbleId,
            101L, 5L, 10L  // Different bucket
        );

        assertNotEquals(simGhost1, simGhost2,
                       "Different metadata should produce different records");
    }

    @Test
    void testMultipleBubbleScenario() {
        var bubbleA = UUID.randomUUID();
        var bubbleB = UUID.randomUUID();

        // Ghost from bubble A
        var ghostA = new SimulationGhostEntity<>(
            ghostEntity,
            bubbleA,
            100L, 1L, 5L
        );

        // Ghost from bubble B (newer epoch after migration)
        var ghostB = new SimulationGhostEntity<>(
            ghostEntity,
            bubbleB,
            101L, 2L, 0L
        );

        assertTrue(ghostA.isFromBubble(bubbleA));
        assertTrue(ghostB.isFromBubble(bubbleB));
        assertFalse(ghostA.isFromBubble(bubbleB));
        assertFalse(ghostB.isFromBubble(bubbleA));

        assertTrue(ghostB.isNewerThan(ghostA),
                  "Ghost from new bubble should have newer authority");
    }
}
