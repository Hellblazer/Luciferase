package com.hellblazer.luciferase.simulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExternalBubbleTracker - VON-based bubble discovery and merge candidate identification.
 * <p>
 * Implements the "watchmen" pattern from VON research:
 * - Discover external bubbles through ghost entity interactions
 * - Track interaction frequency for affinity metrics
 * - Identify high-affinity bubbles as merge candidates
 * <p>
 * Use case: When a ghost entity from bubble B enters bubble A's ghost zone,
 * bubble A discovers bubble B and tracks the interaction frequency.
 *
 * @author hal.hildebrand
 */
class ExternalBubbleTrackerTest {

    private ExternalBubbleTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ExternalBubbleTracker();
    }

    @Test
    void testInitialState() {
        assertTrue(tracker.getDiscoveredBubbles().isEmpty(),
                  "Initially no bubbles should be discovered");
    }

    @Test
    void testRecordFirstInteraction() {
        var bubbleId = UUID.randomUUID();

        tracker.recordGhostInteraction(bubbleId);

        var discovered = tracker.getDiscoveredBubbles();
        assertEquals(1, discovered.size());
        assertTrue(discovered.contains(bubbleId));
    }

    @Test
    void testRecordMultipleInteractionsWithSameBubble() {
        var bubbleId = UUID.randomUUID();

        // Record 5 interactions
        for (int i = 0; i < 5; i++) {
            tracker.recordGhostInteraction(bubbleId);
        }

        var discovered = tracker.getDiscoveredBubbles();
        assertEquals(1, discovered.size());
        assertTrue(discovered.contains(bubbleId));

        // Verify interaction count
        assertEquals(5, tracker.getInteractionCount(bubbleId));
    }

    @Test
    void testDiscoverMultipleBubbles() {
        var bubbleA = UUID.randomUUID();
        var bubbleB = UUID.randomUUID();
        var bubbleC = UUID.randomUUID();

        tracker.recordGhostInteraction(bubbleA);
        tracker.recordGhostInteraction(bubbleB);
        tracker.recordGhostInteraction(bubbleC);

        var discovered = tracker.getDiscoveredBubbles();
        assertEquals(3, discovered.size());
        assertTrue(discovered.contains(bubbleA));
        assertTrue(discovered.contains(bubbleB));
        assertTrue(discovered.contains(bubbleC));
    }

    @Test
    void testGetMergeCandidates() {
        var bubbleA = UUID.randomUUID();
        var bubbleB = UUID.randomUUID();
        var bubbleC = UUID.randomUUID();

        // Bubble A: 10 interactions (high affinity)
        for (int i = 0; i < 10; i++) {
            tracker.recordGhostInteraction(bubbleA);
        }

        // Bubble B: 5 interactions (medium affinity)
        for (int i = 0; i < 5; i++) {
            tracker.recordGhostInteraction(bubbleB);
        }

        // Bubble C: 2 interactions (low affinity)
        for (int i = 0; i < 2; i++) {
            tracker.recordGhostInteraction(bubbleC);
        }

        // Threshold 5: Should return bubbles with >= 5 interactions
        var candidates = tracker.getMergeCandidates(5);

        assertEquals(2, candidates.size());
        assertTrue(candidates.contains(bubbleA));
        assertTrue(candidates.contains(bubbleB));
        assertFalse(candidates.contains(bubbleC));
    }

    @Test
    void testGetMergeCandidatesOrdered() {
        var bubbleA = UUID.randomUUID();
        var bubbleB = UUID.randomUUID();
        var bubbleC = UUID.randomUUID();

        // Different interaction counts
        for (int i = 0; i < 15; i++) tracker.recordGhostInteraction(bubbleA);
        for (int i = 0; i < 8; i++) tracker.recordGhostInteraction(bubbleB);
        for (int i = 0; i < 20; i++) tracker.recordGhostInteraction(bubbleC);

        var candidates = tracker.getMergeCandidates(5);

        // Should be ordered by interaction count (descending)
        assertEquals(3, candidates.size());
        assertEquals(bubbleC, candidates.get(0), "Highest count should be first");
        assertEquals(bubbleA, candidates.get(1), "Medium count should be second");
        assertEquals(bubbleB, candidates.get(2), "Lowest count should be third");
    }

    @Test
    void testGetMergeCandidatesHighThreshold() {
        var bubbleA = UUID.randomUUID();
        var bubbleB = UUID.randomUUID();

        for (int i = 0; i < 5; i++) tracker.recordGhostInteraction(bubbleA);
        for (int i = 0; i < 10; i++) tracker.recordGhostInteraction(bubbleB);

        // High threshold filters out low-affinity bubbles
        var candidates = tracker.getMergeCandidates(8);

        assertEquals(1, candidates.size());
        assertTrue(candidates.contains(bubbleB));
    }

    @Test
    void testGetMergeCandidatesNoMatches() {
        var bubbleA = UUID.randomUUID();

        for (int i = 0; i < 3; i++) tracker.recordGhostInteraction(bubbleA);

        // Threshold higher than any interaction count
        var candidates = tracker.getMergeCandidates(10);

        assertTrue(candidates.isEmpty(), "No candidates should meet high threshold");
    }

    @Test
    void testGetInteractionCount() {
        var bubbleId = UUID.randomUUID();

        assertEquals(0, tracker.getInteractionCount(bubbleId),
                    "Unknown bubble should have 0 interactions");

        for (int i = 0; i < 7; i++) {
            tracker.recordGhostInteraction(bubbleId);
        }

        assertEquals(7, tracker.getInteractionCount(bubbleId));
    }

    @Test
    void testVONDiscoveryPattern() {
        // Simulate VON "watchmen" pattern
        // Local bubble A discovers neighbors through ghost entities

        var bubbleB = UUID.randomUUID();  // Neighbor to the north
        var bubbleC = UUID.randomUUID();  // Neighbor to the east
        var bubbleD = UUID.randomUUID();  // Distant bubble (few interactions)

        // Ghost entities from bubble B frequently enter our ghost zone
        for (int i = 0; i < 20; i++) {
            tracker.recordGhostInteraction(bubbleB);
        }

        // Moderate interaction with bubble C
        for (int i = 0; i < 10; i++) {
            tracker.recordGhostInteraction(bubbleC);
        }

        // Rare interaction with bubble D
        for (int i = 0; i < 2; i++) {
            tracker.recordGhostInteraction(bubbleD);
        }

        // High affinity bubbles (candidates for merge)
        var mergeCandidates = tracker.getMergeCandidates(10);

        assertEquals(2, mergeCandidates.size());
        assertTrue(mergeCandidates.contains(bubbleB),
                  "High interaction bubble should be merge candidate");
        assertTrue(mergeCandidates.contains(bubbleC),
                  "Medium interaction bubble should be merge candidate");
    }

    @Test
    void testGetDiscoveredBubblesIsUnmodifiable() {
        var bubbleId = UUID.randomUUID();
        tracker.recordGhostInteraction(bubbleId);

        var discovered = tracker.getDiscoveredBubbles();

        // Should not be able to modify returned set
        assertThrows(UnsupportedOperationException.class,
                    () -> discovered.add(UUID.randomUUID()));
    }

    @Test
    void testConcurrentInteractionRecording() throws InterruptedException {
        var bubbleId = UUID.randomUUID();
        int threadCount = 10;
        int interactionsPerThread = 100;

        var threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < interactionsPerThread; j++) {
                    tracker.recordGhostInteraction(bubbleId);
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        // All interactions should be counted (thread-safe)
        assertEquals(threadCount * interactionsPerThread,
                    tracker.getInteractionCount(bubbleId),
                    "Concurrent interactions should all be recorded");
    }
}
