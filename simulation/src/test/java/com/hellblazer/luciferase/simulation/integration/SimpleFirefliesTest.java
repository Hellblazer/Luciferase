/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.integration;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.archipelago.*;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.*;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simple Fireflies cluster test - 6 nodes using LocalServer (in-process routing).
 * <p>
 * Pattern from Delos E2ETest.java - validates basic cluster membership formation.
 *
 * @author hal.hildebrand
 */
public class SimpleFirefliesTest {

    private static final int CARDINALITY = 6;

    private final List<Router> communications = new ArrayList<>();
    private final List<Router> gateways       = new ArrayList<>();
    private final List<View>   views          = new ArrayList<>();

    private List<ControlledIdentifierMember> members;
    private MemKERL                          kerl;

    @BeforeEach
    public void setup() throws Exception {
        // Use deterministic entropy for reproducible tests
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{6, 6, 6});

        // Create Stereotomy identity manager
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);

        // Generate controlled identifiers (one per node)
        var identities = IntStream.range(0, CARDINALITY)
                                  .mapToObj(i -> stereotomy.newIdentifier())
                                  .toList();

        // Create members from identities
        members = identities.stream()
                            .map(ControlledIdentifierMember::new)
                            .collect(Collectors.toList());

        // Build parameters (MUST match E2ETest for cluster convergence)
        var parameters = Parameters.newBuilder()
                                   .setMaxPending(20)    // CRITICAL: E2ETest uses 20
                                   .setMaximumTxfr(5)
                                   .build();

        // Create context builder (EACH View gets its own context from this builder)
        var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                       .setBias(2)         // CRITICAL: E2ETest uses BIAS=2
                                       .setpByz(0.1)       // CRITICAL: E2ETest uses P_BYZ=0.1
                                       .setCardinality(CARDINALITY);

        // Use unique prefixes for this cluster (isolates test runs)
        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();
        var registry = new MetricRegistry();

        // Create Views and Routers using LocalServer
        for (var member : members) {
            // CRITICAL: Each View gets its own context from the builder
            DynamicContext<Participant> context = ctxBuilder.build();

            // Create metrics
            var metrics = new FireflyMetricsImpl(context.getId(), registry);

            // Create LocalServer routers (comms + gateway with separate prefixes)
            var comms = new LocalServer(prefix, member).router(
                ServerConnectionCache.newBuilder()
                                     .setTarget(30)
                                     .setMetrics(new ServerConnectionCacheMetricsImpl(registry))
            );

            var gateway = new LocalServer(gatewayPrefix, member).router(
                ServerConnectionCache.newBuilder()
                                     .setTarget(30)
                                     .setMetrics(new ServerConnectionCacheMetricsImpl(registry))
            );

            comms.start();
            communications.add(comms);

            gateway.start();
            gateways.add(gateway);

            // Create View with both comms and gateway routers
            var view = new View(
                context,
                member,
                "0",  // OS dynamic port allocation
                null,  // No validation for testing
                Verifiers.from(kerl),
                comms,
                parameters,
                gateway,  // Gateway router
                DigestAlgorithm.DEFAULT,
                metrics
            );
            views.add(view);
        }
    }

    @AfterEach
    public void cleanup() {
        // Stop views
        if (views != null) {
            views.forEach(View::stop);
            views.clear();
        }

        // Close routers
        communications.forEach(r -> r.close(Duration.ofSeconds(1)));
        communications.clear();

        gateways.forEach(r -> r.close(Duration.ofSeconds(1)));
        gateways.clear();
    }

    @Test
    void testSixNodeCluster_allMembersConverge() throws Exception {
        // Given: 6-node cluster with LocalServer routing
        var gossipDuration = Duration.ofMillis(5);

        // Create seed from first member
        var kernel = List.of(new Seed(
            members.get(0).getIdentifier().getIdentifier(),
            "0"
        ));

        // When: Bootstrap kernel node (view 0) with no seeds
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());

        // Then: Kernel node should bootstrap within 60s
        assertTrue(countdown.get().await(60, TimeUnit.SECONDS), "Kernel node did not bootstrap");

        // When: Start ALL views (including kernel again) with kernel as seed (E2ETest pattern)
        countdown.set(new CountDownLatch(CARDINALITY));
        views.forEach(view ->
            view.start(() -> countdown.get().countDown(), gossipDuration, kernel)
        );

        // Then: All nodes should start within 60s
        assertTrue(countdown.get().await(60, TimeUnit.SECONDS), "Not all nodes started");

        // Then: Wait for all nodes to see full membership (6 members)
        var stabilized = waitForCondition(30_000, 1000, () ->
            views.stream().filter(v -> v.getContext().activeCount() == CARDINALITY).count() == CARDINALITY
        );

        // Verify: All 6 nodes see all 6 members
        var failed = views.stream()
                          .filter(v -> v.getContext().activeCount() != CARDINALITY)
                          .map(v -> String.format("Node %s sees %d members (expected %d)",
                                                  v.getNodeId(),
                                                  v.getContext().activeCount(),
                                                  CARDINALITY))
                          .toList();

        assertTrue(stabilized,
                   "Cluster did not stabilize. Failed nodes: " + failed.size() + "\n" + String.join("\n", failed));

        System.out.println("✅ 6-node cluster successfully converged - all members see all 6 nodes");
    }

    /**
     * Wait for condition with periodic checks.
     *
     * @param maxWaitMs  maximum wait time in milliseconds
     * @param checkMs    check interval in milliseconds
     * @param condition  condition to check
     * @return true if condition met, false if timeout
     */
    private boolean waitForCondition(long maxWaitMs, long checkMs, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(checkMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
