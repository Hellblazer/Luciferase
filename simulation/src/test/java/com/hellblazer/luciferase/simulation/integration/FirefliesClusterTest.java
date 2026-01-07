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
import com.google.common.collect.Sets;
import com.hellblazer.delos.archipelago.*;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.*;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fireflies 6-node cluster test (copied from Delos E2ETest).
 * <p>
 * Tests basic cluster formation using LocalServer (in-process routing).
 *
 * @author hal.hildebrand
 */
public class FirefliesClusterTest {

    private static final int                                                         BIAS        = 2;
    private static final int                                                         CARDINALITY = 12;  // E2ETest default
    private static final double                                                      P_BYZ       = 0.1;
    private static       Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static       KERL.AppendKERL                                             kerl;

    private final List<Router>                            communications = new ArrayList<>();
    private final List<Router>                            gateways       = new ArrayList<>();
    private       Map<Digest, ControlledIdentifierMember> members;
    private       List<View>                              views;

    @BeforeAll
    public static void beforeClass() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{6, 6, 6});
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        identities = IntStream.range(0, CARDINALITY)
                              .mapToObj(i -> stereotomy.newIdentifier())
                              .collect(Collectors.toMap(
                                  controlled -> controlled.getIdentifier().getDigest(),
                                  controlled -> controlled,
                                  (a, b) -> a,
                                  TreeMap::new
                              ));
    }

    @AfterEach
    public void after() {
        if (views != null) {
            views.forEach(v -> v.stop());
            views.clear();
        }

        communications.forEach(e -> e.close(Duration.ofSeconds(0)));
        communications.clear();

        gateways.forEach(e -> e.close(Duration.ofSeconds(0)));
        gateways.clear();
    }

    @Test
    public void sixNodeCluster() throws Exception {
        initialize();
        long then = System.currentTimeMillis();

        // Bootstrap the kernel
        final var seeds = members.values()
                                 .stream()
                                 .map(m -> new Seed(m.getIdentifier().getIdentifier(), "0"))
                                 .limit(1)
                                 .toList();
        final var bootstrapSeed = seeds.subList(0, 1);

        final var gossipDuration = Duration.ofMillis(5);

        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());

        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Kernel did not bootstrap");

        var bootstrappers = views.subList(0, seeds.size());
        countdown.set(new CountDownLatch(seeds.size() - 1));
        bootstrappers.subList(1, bootstrappers.size())
                     .forEach(v -> v.start(() -> countdown.get().countDown(), gossipDuration, bootstrapSeed));

        // Test that all bootstrappers up
        var success = countdown.get().await(30, TimeUnit.SECONDS);
        var failed = bootstrappers.stream()
                                  .filter(e -> e.getContext().activeCount() != bootstrappers.size())
                                  .map(v -> String.format("%s : %s : %s",
                                                          v.getNodeId(),
                                                          v.getContext().activeCount(),
                                                          Sets.difference(
                                                              members.keySet(),
                                                              new HashSet<>(
                                                                  v.getContext()
                                                                   .activeMembers()
                                                                   .stream()
                                                                   .map(Participant::getId)
                                                                   .toList()
                                                              )
                                                          ).stream().toList()))
                                  .toList();
        assertTrue(success, " expected: " + bootstrappers.size() + " failed: " + failed.size() + " views: " + failed);

        // Start remaining views
        countdown.set(new CountDownLatch(views.size() - seeds.size()));
        views.forEach(v -> v.start(() -> countdown.get().countDown(), gossipDuration, seeds));

        success = countdown.get().await(30, TimeUnit.SECONDS);

        // Test that all views are up
        failed = views.stream()
                      .filter(e -> e.getContext().activeCount() != CARDINALITY)
                      .map(v -> String.format("%s : %s : %s",
                                              v.getNodeId(),
                                              v.getContext().activeCount(),
                                              Sets.difference(
                                                  members.keySet(),
                                                  new HashSet<>(
                                                      v.getContext()
                                                       .activeMembers()
                                                       .stream()
                                                       .map(Participant::getId)
                                                       .toList()
                                                  )
                                              ).stream().toList()))
                      .toList();
        assertTrue(success, "Views did not start, expected: " + views.size() + " failed: " + failed.size() + " views: " + failed);

        System.out.println("✅ View has stabilized in " + (System.currentTimeMillis() - then) + " ms across all " + views.size() + " members");
    }

    private void initialize() {
        var parameters = Parameters.newBuilder().setMaxPending(20).setMaximumTxfr(5).build();
        var registry = new MetricRegistry();

        members = identities.values()
                            .stream()
                            .map(ControlledIdentifierMember::new)
                            .collect(Collectors.toMap(m -> m.getId(), m -> m));
        var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                       .setBias(BIAS)
                                       .setpByz(P_BYZ)
                                       .setCardinality(CARDINALITY);

        AtomicBoolean frist = new AtomicBoolean(true);
        final var prefix = UUID.randomUUID().toString();
        final var gatewayPrefix = UUID.randomUUID().toString();
        views = members.values().stream().map(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            FireflyMetricsImpl metrics = new FireflyMetricsImpl(context.getId(), registry);
            var comms = new LocalServer(prefix, node).router(
                ServerConnectionCache.newBuilder()
                                     .setTarget(200)
                                     .setMetrics(new ServerConnectionCacheMetricsImpl(registry))
            );
            var gateway = new LocalServer(gatewayPrefix, node).router(
                ServerConnectionCache.newBuilder()
                                     .setTarget(200)
                                     .setMetrics(new ServerConnectionCacheMetricsImpl(registry))
            );
            comms.start();
            communications.add(comms);

            gateway.start();
            gateways.add(gateway);
            return new View(context, node, "0", null, Verifiers.from(kerl),
                            comms, parameters, gateway, DigestAlgorithm.DEFAULT, metrics);
        }).collect(Collectors.toList());
    }
}
