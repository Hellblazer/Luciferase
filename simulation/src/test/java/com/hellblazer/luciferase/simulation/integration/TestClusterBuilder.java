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

import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.MicrometerServerConnectionCacheMetrics;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.MicrometerFireflyMetrics;
import com.hellblazer.delos.fireflies.Parameters;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.ControlledIdentifier;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.EventValidation;
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Builder for Fireflies test clusters using LocalServer (in-process routing).
 * <p>
 * Creates local Fireflies clusters for integration testing with:
 * - In-process communication via LocalServer (no network, no MTLS)
 * - Proper gateway router for view changes
 * - Automatic bootstrap and stabilization
 * - Clean shutdown via close() method
 * <p>
 * Based on Delos E2ETest.java pattern.
 *
 * @author hal.hildebrand
 */
public class TestClusterBuilder {

    private static final int    DEFAULT_BIAS  = 2;
    private static final double DEFAULT_P_BYZ = 0.1;

    /**
     * Test cluster with Views, Routers, Members, and lifecycle management.
     */
    public static class TestCluster implements AutoCloseable {
        private final List<View>                               views;
        private final List<Router>                             communications;
        private final List<Router>                             gateways;
        private final Map<Digest, ControlledIdentifierMember>  members;
        private final KERL.AppendKERL                          kerl;
        private final int                                      cardinality;

        TestCluster(List<View> views,
                    List<Router> communications,
                    List<Router> gateways,
                    Map<Digest, ControlledIdentifierMember> members,
                    KERL.AppendKERL kerl,
                    int cardinality) {
            this.views = views;
            this.communications = communications;
            this.gateways = gateways;
            this.members = members;
            this.kerl = kerl;
            this.cardinality = cardinality;
        }

        public List<View> getViews() {
            return Collections.unmodifiableList(views);
        }

        public View getView(int index) {
            return views.get(index);
        }

        public Map<Digest, ControlledIdentifierMember> getMembers() {
            return Collections.unmodifiableMap(members);
        }

        public ControlledIdentifierMember getMember(int index) {
            return new ArrayList<>(members.values()).get(index);
        }

        public KERL.AppendKERL getKerl() {
            return kerl;
        }

        public int getCardinality() {
            return cardinality;
        }

        /**
         * Bootstrap and start all views in the cluster.
         * <p>
         * Uses the first node as the bootstrap seed, then starts all other nodes.
         * Waits for all views to stabilize with full membership.
         *
         * @param gossipDuration duration between gossip rounds
         * @param timeoutSeconds maximum time to wait for stabilization
         * @return true if cluster stabilized successfully
         */
        public boolean bootstrapAndStart(Duration gossipDuration, int timeoutSeconds) throws InterruptedException {
            // Create seeds from first member
            var seeds = members.values()
                               .stream()
                               .map(m -> new Seed(m.getIdentifier().getIdentifier(), "0"))
                               .limit(1)
                               .toList();

            // Bootstrap first node (seed node)
            var countdown = new AtomicReference<>(new CountDownLatch(1));
            views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());

            if (!countdown.get().await(timeoutSeconds, TimeUnit.SECONDS)) {
                return false;  // Kernel did not bootstrap
            }

            // Start remaining views with seed
            countdown.set(new CountDownLatch(views.size() - 1));
            views.subList(1, views.size())
                 .forEach(v -> v.start(() -> countdown.get().countDown(), gossipDuration, seeds));

            if (!countdown.get().await(timeoutSeconds, TimeUnit.SECONDS)) {
                return false;  // Views did not start
            }

            // Wait for full stabilization
            return Utils.waitForCondition(timeoutSeconds * 1000, 100, () ->
                views.stream().allMatch(view -> view.getContext().activeCount() == cardinality)
            );
        }

        /**
         * Get nodes that haven't reached full cardinality.
         */
        public List<String> getUnstableNodes() {
            return views.stream()
                        .filter(v -> v.getContext().activeCount() != cardinality)
                        .map(v -> String.format("%s: %d/%d",
                                                v.getNode().getId(),
                                                v.getContext().activeCount(),
                                                cardinality))
                        .toList();
        }

        @Override
        public void close() {
            // Stop views
            views.forEach(View::stop);

            // Close communications
            communications.forEach(r -> r.close(Duration.ofSeconds(0)));

            // Close gateways
            gateways.forEach(r -> r.close(Duration.ofSeconds(0)));
        }
    }

    private int    cardinality = 12;
    private int    bias        = DEFAULT_BIAS;
    private double pByz        = DEFAULT_P_BYZ;
    private byte[] seed        = new byte[]{6, 6, 6};

    /**
     * Set the number of nodes in the cluster.
     */
    public TestClusterBuilder cardinality(int cardinality) {
        this.cardinality = cardinality;
        return this;
    }

    /**
     * Set the bias parameter for the DynamicContext.
     */
    public TestClusterBuilder bias(int bias) {
        this.bias = bias;
        return this;
    }

    /**
     * Set the Byzantine fault probability.
     */
    public TestClusterBuilder pByz(double pByz) {
        this.pByz = pByz;
        return this;
    }

    /**
     * Set the random seed for reproducible tests.
     */
    public TestClusterBuilder seed(byte[] seed) {
        this.seed = seed;
        return this;
    }

    /**
     * Build the test cluster.
     * <p>
     * Creates all Views and Routers but does NOT start them.
     * Call {@link TestCluster#bootstrapAndStart(Duration, int)} to start.
     *
     * @return the test cluster
     */
    public TestCluster build() {
        try {
            // Use deterministic entropy for reproducible tests
            var entropy = SecureRandom.getInstance("SHA1PRNG");
            entropy.setSeed(seed);

            // Create Stereotomy identity manager
            var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
            var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);

            // Generate controlled identifiers (one per node)
            Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities =
                IntStream.range(0, cardinality)
                         .mapToObj(i -> stereotomy.newIdentifier())
                         .collect(Collectors.toMap(
                             controlled -> controlled.getIdentifier().getDigest(),
                             controlled -> controlled,
                             (a, b) -> a,
                             TreeMap::new
                         ));

            // Create members from identities
            var members = identities.values()
                                    .stream()
                                    .map(ControlledIdentifierMember::new)
                                    .collect(Collectors.toMap(
                                        m -> m.getId(),
                                        m -> m,
                                        (a, b) -> a,
                                        LinkedHashMap::new
                                    ));

            // Build parameters
            var parameters = Parameters.newBuilder()
                                       .setMaxPending(20)
                                       .setMaximumTxfr(5)
                                       .build();

            // Context builder
            var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                           .setBias(bias)
                                           .setpByz(pByz)
                                           .setCardinality(cardinality);

            // Create views and routers
            var views = new ArrayList<View>();
            var communications = new ArrayList<Router>();
            var gateways = new ArrayList<Router>();
            var registry = new SimpleMeterRegistry();

            // Unique prefixes for this cluster
            var prefix = UUID.randomUUID().toString();
            var gatewayPrefix = UUID.randomUUID().toString();

            for (var member : members.values()) {
                DynamicContext<Participant> context = ctxBuilder.build();
                var metrics = new MicrometerFireflyMetrics(context.getId(), registry);

                // Create communication router (LocalServer for in-process)
                var comms = new LocalServer(prefix, member).router(
                    ServerConnectionCache.newBuilder()
                                         .setTarget(200)
                                         .setMetrics(new MicrometerServerConnectionCacheMetrics(registry))
                );

                // Create gateway router (for view changes)
                var gateway = new LocalServer(gatewayPrefix, member).router(
                    ServerConnectionCache.newBuilder()
                                         .setTarget(200)
                                         .setMetrics(new MicrometerServerConnectionCacheMetrics(registry))
                );

                comms.start();
                communications.add(comms);

                gateway.start();
                gateways.add(gateway);

                // Create View with gateway (matches E2ETest pattern)
                var view = new View(
                    context,
                    member,
                    "0",                    // endpoint - "0" for dynamic allocation
                    EventValidation.NONE,   // EventValidation - NONE for testing
                    Verifiers.from(kerl),
                    comms,
                    parameters,
                    gateway,                // gateway router
                    DigestAlgorithm.DEFAULT,
                    metrics
                );
                views.add(view);
            }

            return new TestCluster(views, communications, gateways, members, kerl, cardinality);

        } catch (Exception e) {
            throw new RuntimeException("Failed to build test cluster", e);
        }
    }

    /**
     * Convenience method: build and start a cluster with default settings.
     *
     * @param nodeCount number of nodes
     * @return started and stabilized cluster
     */
    public static TestCluster buildAndStart(int nodeCount) throws InterruptedException {
        var cluster = new TestClusterBuilder()
            .cardinality(nodeCount)
            .build();

        if (!cluster.bootstrapAndStart(Duration.ofMillis(5), 30)) {
            cluster.close();
            throw new RuntimeException("Cluster failed to stabilize: " + cluster.getUnstableNodes());
        }

        return cluster;
    }
}
