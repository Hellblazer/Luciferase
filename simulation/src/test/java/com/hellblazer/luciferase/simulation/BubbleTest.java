package com.hellblazer.luciferase.simulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Bubble - entity membership and affinity tracking.
 * <p>
 * Bubble manages:
 * - Entity membership (which entities belong to this bubble)
 * - Affinity tracking (internal vs external interactions per entity)
 * - Migration candidate identification (entities with low affinity)
 *
 * @author hal.hildebrand
 */
class BubbleTest {

    private Bubble bubble;
    private String entityA;
    private String entityB;
    private String entityC;

    @BeforeEach
    void setUp() {
        bubble = new Bubble(UUID.randomUUID());
        entityA = "entity-A";
        entityB = "entity-B";
        entityC = "entity-C";
    }

    @Test
    void testBubbleCreation() {
        var id = UUID.randomUUID();
        var b = new Bubble(id);

        assertEquals(id, b.id());
        assertTrue(b.getMembers().isEmpty());
    }

    @Test
    void testAddMember() {
        assertFalse(bubble.isMember(entityA));

        bubble.addMember(entityA);

        assertTrue(bubble.isMember(entityA));
        assertEquals(1, bubble.getMembers().size());
        assertTrue(bubble.getMembers().contains(entityA));
    }

    @Test
    void testAddMultipleMembers() {
        bubble.addMember(entityA);
        bubble.addMember(entityB);
        bubble.addMember(entityC);

        assertEquals(3, bubble.getMembers().size());
        assertTrue(bubble.isMember(entityA));
        assertTrue(bubble.isMember(entityB));
        assertTrue(bubble.isMember(entityC));
    }

    @Test
    void testAddDuplicateMember() {
        bubble.addMember(entityA);
        bubble.addMember(entityA); // Duplicate

        assertEquals(1, bubble.getMembers().size(),
                    "Adding duplicate member should not increase count");
    }

    @Test
    void testRemoveMember() {
        bubble.addMember(entityA);
        bubble.addMember(entityB);

        assertTrue(bubble.isMember(entityA));

        bubble.removeMember(entityA);

        assertFalse(bubble.isMember(entityA));
        assertTrue(bubble.isMember(entityB));
        assertEquals(1, bubble.getMembers().size());
    }

    @Test
    void testRemoveNonMember() {
        bubble.addMember(entityA);

        // Removing non-member should not throw
        assertDoesNotThrow(() -> bubble.removeMember(entityB));

        assertEquals(1, bubble.getMembers().size());
    }

    @Test
    void testInitialAffinity() {
        bubble.addMember(entityA);

        // New member should have 0/0 affinity (boundary classification)
        var affinity = bubble.getAffinity(entityA);
        assertNotNull(affinity);
        assertEquals(0, affinity.internal());
        assertEquals(0, affinity.external());
        assertEquals(0.5f, affinity.affinity(), 0.001f);
        assertTrue(affinity.isBoundary());
    }

    @Test
    void testRecordInternalInteraction() {
        bubble.addMember(entityA);

        bubble.recordInteraction(entityA, true);

        var affinity = bubble.getAffinity(entityA);
        assertEquals(1, affinity.internal());
        assertEquals(0, affinity.external());
        assertEquals(1.0f, affinity.affinity(), 0.001f);
    }

    @Test
    void testRecordExternalInteraction() {
        bubble.addMember(entityA);

        bubble.recordInteraction(entityA, false);

        var affinity = bubble.getAffinity(entityA);
        assertEquals(0, affinity.internal());
        assertEquals(1, affinity.external());
        assertEquals(0.0f, affinity.affinity(), 0.001f);
    }

    @Test
    void testRecordMixedInteractions() {
        bubble.addMember(entityA);

        // 7 internal, 3 external = 0.7 affinity (boundary)
        for (int i = 0; i < 7; i++) {
            bubble.recordInteraction(entityA, true);
        }
        for (int i = 0; i < 3; i++) {
            bubble.recordInteraction(entityA, false);
        }

        var affinity = bubble.getAffinity(entityA);
        assertEquals(7, affinity.internal());
        assertEquals(3, affinity.external());
        assertEquals(0.7f, affinity.affinity(), 0.001f);
        assertTrue(affinity.isBoundary());
    }

    @Test
    void testAffinityProgression() {
        bubble.addMember(entityA);

        // Start as boundary (0/0)
        assertTrue(bubble.getAffinity(entityA).isBoundary());

        // Record internal interactions → core
        for (int i = 0; i < 9; i++) {
            bubble.recordInteraction(entityA, true);
        }
        bubble.recordInteraction(entityA, false);

        var affinity = bubble.getAffinity(entityA);
        assertTrue(affinity.isCore(), "9 internal + 1 external = 0.9 affinity (core)");

        // Record external interactions → boundary
        for (int i = 0; i < 3; i++) {
            bubble.recordInteraction(entityA, false);
        }

        affinity = bubble.getAffinity(entityA);
        assertTrue(affinity.isBoundary(), "9 internal + 4 external = 0.69 affinity (boundary)");

        // Record more external → drifting
        for (int i = 0; i < 6; i++) {
            bubble.recordInteraction(entityA, false);
        }

        affinity = bubble.getAffinity(entityA);
        assertTrue(affinity.isDrifting(), "9 internal + 10 external = 0.47 affinity (drifting)");
    }

    @Test
    void testGetAffinityForNonMember() {
        // Non-member should return null or throw - implementation choice
        var affinity = bubble.getAffinity(entityA);
        assertNull(affinity, "Affinity for non-member should be null");
    }

    @Test
    void testRecordInteractionForNonMember() {
        // Recording interaction for non-member should be no-op or throw
        assertDoesNotThrow(() -> bubble.recordInteraction(entityA, true));

        // Should not add member implicitly
        assertFalse(bubble.isMember(entityA));
    }

    @Test
    void testMultipleEntitiesIndependentAffinity() {
        bubble.addMember(entityA);
        bubble.addMember(entityB);

        // Entity A: mostly internal
        for (int i = 0; i < 8; i++) {
            bubble.recordInteraction(entityA, true);
        }
        bubble.recordInteraction(entityA, false);

        // Entity B: mostly external
        bubble.recordInteraction(entityB, true);
        for (int i = 0; i < 8; i++) {
            bubble.recordInteraction(entityB, false);
        }

        var affinityA = bubble.getAffinity(entityA);
        var affinityB = bubble.getAffinity(entityB);

        assertTrue(affinityA.isCore(), "Entity A should be core");
        assertTrue(affinityB.isDrifting(), "Entity B should be drifting");

        // Independent tracking
        assertEquals(8, affinityA.internal());
        assertEquals(1, affinityA.external());
        assertEquals(1, affinityB.internal());
        assertEquals(8, affinityB.external());
    }

    @Test
    void testRemoveMemberClearsAffinity() {
        bubble.addMember(entityA);
        bubble.recordInteraction(entityA, true);
        bubble.recordInteraction(entityA, true);

        assertNotNull(bubble.getAffinity(entityA));

        bubble.removeMember(entityA);

        // Affinity should be cleared after removal
        assertNull(bubble.getAffinity(entityA));
    }

    @Test
    void testGetMembersIsUnmodifiable() {
        bubble.addMember(entityA);

        var members = bubble.getMembers();

        // Should not be able to modify returned set
        assertThrows(UnsupportedOperationException.class,
                    () -> members.add(entityB));
    }

    @Test
    void testIdentifyMigrationCandidates() {
        // Add entities with different affinity profiles
        bubble.addMember(entityA);
        bubble.addMember(entityB);
        bubble.addMember(entityC);

        // Entity A: core (9 internal, 1 external = 0.9)
        for (int i = 0; i < 9; i++) bubble.recordInteraction(entityA, true);
        bubble.recordInteraction(entityA, false);

        // Entity B: boundary (6 internal, 4 external = 0.6)
        for (int i = 0; i < 6; i++) bubble.recordInteraction(entityB, true);
        for (int i = 0; i < 4; i++) bubble.recordInteraction(entityB, false);

        // Entity C: drifting (3 internal, 7 external = 0.3)
        for (int i = 0; i < 3; i++) bubble.recordInteraction(entityC, true);
        for (int i = 0; i < 7; i++) bubble.recordInteraction(entityC, false);

        // Verify classification
        assertTrue(bubble.getAffinity(entityA).isCore());
        assertTrue(bubble.getAffinity(entityB).isBoundary());
        assertTrue(bubble.getAffinity(entityC).isDrifting());

        // Entity C is a migration candidate (drifting)
        var migrationCandidates = bubble.getMembers().stream()
            .filter(id -> {
                var affinity = bubble.getAffinity(id);
                return affinity != null && affinity.isDrifting();
            })
            .toList();

        assertEquals(1, migrationCandidates.size());
        assertTrue(migrationCandidates.contains(entityC));
    }
}
