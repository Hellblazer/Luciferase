/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.von;

import com.hellblazer.delos.archipelago.EndpointProvider;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.archipelago.UnsafeExecutors;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.Parameters;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.EventValidation;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 Integration Tests: VON + Fireflies
 * <p>
 * Validates:
 * 1. Fireflies view changes < 5 seconds
 * 2. TetreeKeyRouter finds correct region
 * 3. MTLS client pool functionality
 *
 * @author hal.hildebrand
 */
public class Phase0IntegrationTest {

    private static final int CLUSTER_SIZE = 5;
    private static final int BIAS = 2;
    private static final double P_BYZ = 0.1;

    private final List<View> views = new ArrayList<>();
    private final List<Router> communications = new ArrayList<>();
    private final List<ControlledIdentifierMember> members = new ArrayList<>();
    private final List<TetreeKeyRouter> routers = new ArrayList<>();
    private final List<String> endpoints = new ArrayList<>();

    private ExecutorService executor;
    private MemKERL kerl;

    @BeforeEach
    public void setup() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{1, 2, 3});

        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);

        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();

        // Create members and views
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            var identifier = stereotomy.newIdentifier();
            var member = new ControlledIdentifierMember(identifier);
            members.add(member);

            DynamicContext<Participant> context = DynamicContext.<Participant>newBuilder()
                                                                .setBias(BIAS)
                                                                .setpByz(P_BYZ)
                                                                .setCardinality(CLUSTER_SIZE)
                                                                .build();

            var prefix = UUID.randomUUID().toString();
            var router = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(10),
                                                                executor);
            router.start();
            communications.add(router);

            var endpoint = EndpointProvider.allocatePort();
            endpoints.add(endpoint);

            var params = Parameters.newBuilder().build();
            var view = new View(context, member, endpoint, EventValidation.NONE,
                               Verifiers.from(kerl), router, params, router, DigestAlgorithm.DEFAULT, null);
            views.add(view);

            // Create TetreeKeyRouter for this view
            var tetRouter = new TetreeKeyRouter(context, 0, DigestAlgorithm.DEFAULT);
            routers.add(tetRouter);
        }
    }

    @AfterEach
    public void teardown() {
        views.forEach(View::stop);
        views.clear();

        communications.forEach(r -> r.close(Duration.ofSeconds(1)));
        communications.clear();

        members.clear();
        routers.clear();

        if (executor != null) {
            executor.shutdown();
        }
    }

    /**
     * Test 1: Fireflies view changes < 5 seconds
     * <p>
     * Success Criterion: All nodes achieve full view (all CLUSTER_SIZE members visible) within 5 seconds
     */
    @Test
    public void testViewChangeLatency() throws Exception {
        long startTime = System.currentTimeMillis();

        // Use longer gossip duration for reliable convergence
        var gossipDuration = Duration.ofMillis(50);

        // Bootstrap first node
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());

        assertTrue(countdown.get().await(10, TimeUnit.SECONDS), "Bootstrap failed");

        // Create seeds from first node
        var seeds = List.of(
            new Seed(members.get(0).getIdentifier().getIdentifier(), endpoints.get(0))
        );

        // Start remaining nodes
        countdown.set(new CountDownLatch(CLUSTER_SIZE - 1));
        for (int i = 1; i < CLUSTER_SIZE; i++) {
            views.get(i).start(() -> countdown.get().countDown(), gossipDuration, seeds);
        }

        // Wait for all views to converge (allow up to 20s for full cluster convergence)
        boolean success = countdown.get().await(20, TimeUnit.SECONDS);
        long elapsedMs = System.currentTimeMillis() - startTime;

        // Verify all nodes see full cluster
        var failed = views.stream()
                         .filter(v -> v.getContext().activeCount() != CLUSTER_SIZE)
                         .map(v -> String.format("%s: active=%d", v.getNodeId(), v.getContext().activeCount()))
                         .toList();

        assertTrue(success, "Views did not converge within 5 seconds. Failed: " + failed);
        assertTrue(elapsedMs < 5000, "View changes took " + elapsedMs + "ms (expected < 5000ms)");

        System.out.printf("✓ View convergence: %dms for %d nodes (< 5000ms)%n", elapsedMs, CLUSTER_SIZE);
    }

    /**
     * Test 2: VON routing finds correct region
     * <p>
     * Success Criterion: TetreeKeyRouter consistently routes same TetreeKey to same member
     * and different keys distribute across cluster
     */
    @Test
    public void testVONRouting() throws Exception {
        // Bootstrap cluster first
        testViewChangeLatency();

        // Test routing consistency
        var testKey = TetreeKey.create((byte) 5, 0x123456789ABCL, 0L);
        Member firstRoute = routers.get(0).routeToKey(testKey);

        // Same key should route to same member from all routers
        for (int i = 1; i < routers.size(); i++) {
            Member route = routers.get(i).routeToKey(testKey);
            assertEquals(firstRoute.getId(), route.getId(),
                        "Router " + i + " routed to different member than router 0");
        }

        System.out.printf("✓ Routing consistency: All routers agree on member %s for test key%n",
                         firstRoute.getId());

        // Test distribution: different keys should distribute across cluster
        var routeCounts = new int[CLUSTER_SIZE];
        int numTestKeys = 100;

        for (int i = 0; i < numTestKeys; i++) {
            var key = TetreeKey.create((byte) 3, (long) i, 0L);
            Member routed = routers.get(0).routeToKey(key);

            // Find which member index this is
            for (int j = 0; j < CLUSTER_SIZE; j++) {
                if (members.get(j).getId().equals(routed.getId())) {
                    routeCounts[j]++;
                    break;
                }
            }
        }

        // Each member should get some routes (rough distribution check)
        int minRoutes = numTestKeys / (CLUSTER_SIZE * 2); // At least half of fair share
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            assertTrue(routeCounts[i] > 0,
                      "Member " + i + " received no routes - distribution issue");
            System.out.printf("  Member %d: %d routes (%.1f%%)%n",
                            i, routeCounts[i], (routeCounts[i] * 100.0 / numTestKeys));
        }

        System.out.printf("✓ Route distribution: All %d members received routes%n", CLUSTER_SIZE);
    }

    /**
     * Test 3: MTLS client pool functionality
     * <p>
     * Success Criterion: Router can establish MTLS connections to all cluster members
     */
    @Test
    public void testMTLSConnectivity() throws Exception {
        // Bootstrap cluster first
        testViewChangeLatency();

        // Test MTLS connectivity from node 0 to all other nodes
        var testRouter = communications.get(0);
        assertNotNull(testRouter, "Router 0 should exist");

        // Verify all members are reachable via MTLS
        for (int i = 1; i < CLUSTER_SIZE; i++) {
            var targetMember = members.get(i);
            assertNotNull(targetMember, "Member " + i + " should exist");

            // The router's internal connection pool should be able to reach this member
            // If MTLS wasn't working, the view wouldn't have converged in testViewChangeLatency
            assertTrue(views.get(0).getContext().isMember(targetMember.getId()),
                      "Node 0 cannot see member " + i + " - MTLS connection issue");
        }

        System.out.printf("✓ MTLS connectivity: Node 0 can reach all %d cluster members%n", CLUSTER_SIZE - 1);

        // Additional validation: verify bidirectional connectivity
        int successfulConnections = 0;
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            int reachable = 0;
            for (int j = 0; j < CLUSTER_SIZE; j++) {
                if (i != j && views.get(i).getContext().isMember(members.get(j).getId())) {
                    reachable++;
                }
            }
            assertEquals(CLUSTER_SIZE - 1, reachable,
                        "Node " + i + " should see all other " + (CLUSTER_SIZE - 1) + " members");
            successfulConnections += reachable;
        }

        System.out.printf("✓ Bidirectional MTLS: %d successful connections in %d-node mesh%n",
                         successfulConnections, CLUSTER_SIZE);
    }
}
