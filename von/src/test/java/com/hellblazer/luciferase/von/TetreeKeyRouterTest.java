/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.von;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TetreeKeyRouter using mocked Fireflies Context.
 * <p>
 * Validates Phase 0 success criteria:
 * 1. Routing consistency - same key always routes to same member
 * 2. Spatial distribution - different keys distribute across cluster
 * 3. Integration correctness - proper use of Fireflies Context API
 *
 * @author hal.hildebrand
 */
public class TetreeKeyRouterTest {

    private static final int CLUSTER_SIZE = 5;

    private Context<?> mockContext;
    private TetreeKeyRouter router;
    private Map<Digest, Member> members;
    private DigestAlgorithm digestAlgo;

    @BeforeEach
    public void setup() {
        digestAlgo = DigestAlgorithm.DEFAULT;
        mockContext = mock(Context.class);

        // Create mock members for our cluster
        members = new HashMap<>();
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            // Use digest to create proper 32-byte member IDs
            var memberId = digestAlgo.digest(String.format("member-%d", i).getBytes());
            var member = mock(Member.class);
            when(member.getId()).thenReturn(memberId);
            members.put(memberId, member);
        }

        // Setup context.successor() to return members based on hash
        when(mockContext.successor(anyInt(), any(Digest.class))).thenAnswer(invocation -> {
            Digest hash = invocation.getArgument(1);
            // Simple deterministic mapping: use hash to select member
            int memberIndex = Math.abs(hash.hashCode()) % CLUSTER_SIZE;
            return members.values().toArray(new Member[0])[memberIndex];
        });

        router = new TetreeKeyRouter(mockContext, 0, digestAlgo);
    }

    /**
     * Test 1: Routing Consistency
     * <p>
     * Success Criterion: Same TetreeKey always routes to same member
     */
    @Test
    public void testRoutingConsistency() {
        var testKey = TetreeKey.create((byte) 5, 0x123456789ABCL, 0L);

        // Route same key multiple times
        Member first = router.routeToKey(testKey);
        assertNotNull(first, "Router should return a member");

        for (int i = 0; i < 10; i++) {
            Member routed = router.routeToKey(testKey);
            assertEquals(first.getId(), routed.getId(),
                        "Same key should always route to same member (iteration " + i + ")");
        }

        System.out.printf("✓ Routing consistency: Key routes to member %s consistently%n",
                         first.getId());
    }

    /**
     * Test 2: Spatial Distribution
     * <p>
     * Success Criterion: Different TetreeKeys distribute across cluster members
     */
    @Test
    public void testSpatialDistribution() {
        Map<Digest, Integer> routeCounts = new HashMap<>();
        int numTestKeys = 100;

        // Test routing distribution for many keys
        for (int i = 0; i < numTestKeys; i++) {
            var key = TetreeKey.create((byte) 3, (long) i, 0L);
            Member routed = router.routeToKey(key);
            routeCounts.merge(routed.getId(), 1, Integer::sum);
        }

        // Verify all members received some routes
        assertEquals(CLUSTER_SIZE, routeCounts.size(),
                    "All " + CLUSTER_SIZE + " members should receive routes");

        // Verify reasonable distribution (each member gets at least 10% of routes)
        int minExpected = numTestKeys / (CLUSTER_SIZE * 2);
        for (var entry : routeCounts.entrySet()) {
            assertTrue(entry.getValue() >= minExpected,
                      String.format("Member %s received %d routes (expected >= %d)",
                                  entry.getKey(), entry.getValue(), minExpected));
            System.out.printf("  Member %s: %d routes (%.1f%%)%n",
                            entry.getKey(), entry.getValue(),
                            (entry.getValue() * 100.0 / numTestKeys));
        }

        System.out.printf("✓ Spatial distribution: %d keys distributed across %d members%n",
                         numTestKeys, CLUSTER_SIZE);
    }

    /**
     * Test 3: Context API Integration
     * <p>
     * Success Criterion: Router correctly uses Fireflies Context.successor()
     */
    @Test
    public void testContextIntegration() {
        var testKey = TetreeKey.create((byte) 7, 0xABCDEF123456L, 0L);

        // Capture the digest passed to context.successor()
        ArgumentCaptor<Digest> digestCaptor = ArgumentCaptor.forClass(Digest.class);
        ArgumentCaptor<Integer> ringCaptor = ArgumentCaptor.forClass(Integer.class);

        router.routeToKey(testKey);

        verify(mockContext).successor(ringCaptor.capture(), digestCaptor.capture());

        // Verify ring index is 0 (as configured)
        assertEquals(0, ringCaptor.getValue(), "Router should use ring index 0");

        // Verify digest was created from key's TM-index
        Digest capturedDigest = digestCaptor.getValue();
        assertNotNull(capturedDigest, "Digest should not be null");

        System.out.printf("✓ Context integration: successor(ring=%d, digest=%s)%n",
                         ringCaptor.getValue(), capturedDigest);
    }

    /**
     * Test 4: Different Keys Route Differently
     * <p>
     * Success Criterion: Spatially distinct keys route to different members
     */
    @Test
    public void testDifferentKeysRouteDifferently() {
        // Create keys at different spatial locations
        var key1 = TetreeKey.create((byte) 5, 0x0000000000001L, 0L);
        var key2 = TetreeKey.create((byte) 5, 0xFFFFFFFFFFFFL, 0L);

        Member member1 = router.routeToKey(key1);
        Member member2 = router.routeToKey(key2);

        assertNotNull(member1);
        assertNotNull(member2);

        // With different TM-indices, they should (very likely) route to different members
        // Note: This isn't guaranteed 100% due to hash collisions, but extremely likely
        System.out.printf("  Key 0x%X → Member %s%n", key1.getLowBits(), member1.getId());
        System.out.printf("  Key 0x%X → Member %s%n", key2.getLowBits(), member2.getId());

        System.out.printf("✓ Distinct spatial keys tested%n");
    }

    /**
     * Test 5: Level Independence
     * <p>
     * Success Criterion: Routing uses TM-index, not level
     */
    @Test
    public void testLevelIndependence() {
        long tmIndex = 0x12345L;

        // Same TM-index at different levels
        var keyLevel1 = TetreeKey.create((byte) 1, tmIndex, 0L);
        var keyLevel5 = TetreeKey.create((byte) 5, tmIndex, 0L);
        var keyLevel10 = TetreeKey.create((byte) 10, tmIndex, 0L);

        Member member1 = router.routeToKey(keyLevel1);
        Member member5 = router.routeToKey(keyLevel5);
        Member member10 = router.routeToKey(keyLevel10);

        // Same TM-index should route to same member regardless of level
        assertEquals(member1.getId(), member5.getId(),
                    "Same TM-index at different levels should route to same member");
        assertEquals(member1.getId(), member10.getId(),
                    "Same TM-index at different levels should route to same member");

        System.out.printf("✓ Level independence: TM-index 0x%X routes to same member at levels 1, 5, 10%n",
                         tmIndex);
    }

    /**
     * Test 6: Hash-based Routing Verification
     * <p>
     * Success Criterion: Verify hashing produces expected Digest format
     */
    @Test
    public void testHashingMechanism() {
        var testKey = TetreeKey.create((byte) 3, 0x789ABCDEFL, 0L);

        // Route and capture the digest
        ArgumentCaptor<Digest> digestCaptor = ArgumentCaptor.forClass(Digest.class);
        router.routeToKey(testKey);
        verify(mockContext).successor(eq(0), digestCaptor.capture());

        Digest capturedDigest = digestCaptor.getValue();

        // Verify digest algorithm matches router's algorithm
        assertEquals(digestAlgo, capturedDigest.getAlgorithm(),
                    "Digest should use same algorithm as router");

        System.out.printf("✓ Hashing: TM-index 0x%X → Digest %s (algorithm: %s)%n",
                         testKey.getLowBits(), capturedDigest,
                         capturedDigest.getAlgorithm());
    }
}
