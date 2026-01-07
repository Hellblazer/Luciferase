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
import com.hellblazer.delos.comm.grpc.ClientContextSupplier;
import com.hellblazer.delos.comm.grpc.ServerContextSupplier;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.cert.CertificateWithPrivateKey;
import com.hellblazer.delos.cryptography.ssl.CertificateValidator;
import com.hellblazer.delos.fireflies.*;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.ControlledIdentifier;
import com.hellblazer.delos.stereotomy.Stereotomy;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;

import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Builder for Fireflies test clusters with MTLS.
 * <p>
 * Creates local Fireflies clusters for integration testing with:
 * - Dynamic port allocation (no hardcoded ports)
 * - MTLS certificates for secure communication
 * - Proper View and Router lifecycle management
 * - Clean shutdown via cleanup() method
 * <p>
 * Reference: Delos MtlsTest.java smoke() test method.
 *
 * @author hal.hildebrand
 */
public class TestClusterBuilder {

    /**
     * Test cluster with Views, Routers, Members, Endpoints, and cleanup.
     *
     * @param views     Fireflies Views (one per node)
     * @param routers   Routers for communication (one per node)
     * @param members   ControlledIdentifierMember instances (one per node)
     * @param endpoints endpoint strings (one per node, keyed by member digest)
     * @param cleanup   cleanup action to stop and close resources
     */
    public record TestCluster(
        List<View> views,
        List<Router> routers,
        List<ControlledIdentifierMember> members,
        Map<Digest, String> endpoints,
        Runnable cleanup
    ) {
    }

    /**
     * Build a local Fireflies test cluster.
     * <p>
     * Creates a cluster with specified number of nodes, each with:
     * - Dynamic port allocation
     * - MTLS certificate
     * - Fireflies View with DynamicContext
     * - Router for communication
     * <p>
     * Follows exact pattern from Delos MtlsTest.java
     *
     * @param nodeCount number of nodes in cluster
     * @return test cluster ready for use
     */
    public static TestCluster buildCluster(int nodeCount) {
        try {
            // Use deterministic entropy for reproducible tests
            var entropy = SecureRandom.getInstance("SHA1PRNG");
            entropy.setSeed(new byte[]{6, 6, 6});

            // Create Stereotomy identity manager
            var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

            // Generate controlled identifiers (one per node)
            var identities = IntStream.range(0, nodeCount)
                                      .mapToObj(i -> stereotomy.newIdentifier())
                                      .collect(Collectors.toMap(
                                          controlled -> controlled.getIdentifier().getDigest(),
                                          controlled -> controlled
                                      ));

            // Provision certificates and allocate endpoints
            var certs = new HashMap<Digest, CertificateWithPrivateKey>();
            var endpoints = new HashMap<Digest, String>();

            for (var entry : identities.entrySet()) {
                var digest = entry.getKey();
                var identity = entry.getValue();

                // Provision certificate
                certs.put(digest, identity.provision(Instant.now(), Duration.ofDays(1), SignatureAlgorithm.DEFAULT));

                // Allocate dynamic port
                var port = DynamicPortAllocator.allocatePort();
                endpoints.put(digest, "localhost:" + port);
            }

            // Create executor for communication
            var executor = Executors.newVirtualThreadPerTaskExecutor();

            // Create members from identities
            var members = identities.values()
                                    .stream()
                                    .map(ControlledIdentifierMember::new)
                                    .toList();

            // Build parameters
            var parameters = Parameters.newBuilder().setMaximumTxfr(20).build();
            var ctxBuilder = DynamicContext.<Participant>newBuilder().setCardinality(nodeCount);

            // Create views and routers
            var views = new ArrayList<View>();
            var routers = new ArrayList<Router>();
            var registry = new MetricRegistry();

            var clientContextSupplier = clientContextSupplier(certs);

            for (var member : members) {
                var digest = member.getId();
                var endpoint = endpoints.get(digest);
                var certWithKey = certs.get(digest);

                // Create context
                DynamicContext<Participant> context = ctxBuilder.build();

                // Create metrics
                var metrics = new FireflyMetricsImpl(context.getId(), registry);

                // Create endpoint provider (uses Participant.endpoint())
                EndpointProvider ep = new StandardEpProvider(
                    endpoint,
                    ClientAuth.REQUIRE,
                    CertificateValidator.NONE,
                    m -> endpoints.get(m.getId())  // Lookup by member digest
                );

                // Create router with MTLS
                Router router = new MtlsServer(
                    member,
                    ep,
                    clientContextSupplier,
                    serverContextSupplier(certWithKey)
                ).router(
                    ServerConnectionCache.newBuilder()
                                         .setTarget(30)
                                         .setMetrics(new ServerConnectionCacheMetricsImpl(registry)),
                    executor
                );

                routers.add(router);

                // Create View
                var view = new View(
                    context,
                    member,
                    endpoint,
                    null,  // EventValidation (use null for testing)
                    null,  // Verifiers (use null for testing)
                    router,
                    parameters,
                    DigestAlgorithm.DEFAULT,
                    metrics
                );
                views.add(view);
            }

            // Start routers
            routers.forEach(Router::start);

            // Create cleanup action
            Runnable cleanup = () -> {
                // Stop views
                views.forEach(View::stop);
                views.clear();

                // Close routers
                routers.forEach(r -> r.close(Duration.ofSeconds(1)));
                routers.clear();

                // Shutdown executor
                executor.shutdown();
            };

            return new TestCluster(views, routers, members, endpoints, cleanup);

        } catch (Exception e) {
            throw new RuntimeException("Failed to build test cluster", e);
        }
    }

    /**
     * Create client context supplier for MTLS.
     * <p>
     * Returns a Function that takes a Member and returns a ClientContextSupplier.
     */
    private static Function<Member, ClientContextSupplier> clientContextSupplier(
        Map<Digest, CertificateWithPrivateKey> certs) {

        return m -> new ClientContextSupplier() {
            @Override
            public SslContext forClient(ClientAuth clientAuth, String alias, CertificateValidator validator,
                                        String tlsVersion) {
                var certWithKey = certs.get(m.getId());
                return MtlsServer.forClient(clientAuth, alias, certWithKey.getX509Certificate(),
                                            certWithKey.getPrivateKey(), validator);
            }
        };
    }

    /**
     * Create server context supplier for MTLS.
     */
    private static ServerContextSupplier serverContextSupplier(CertificateWithPrivateKey certWithKey) {
        return new ServerContextSupplier() {
            @Override
            public SslContext forServer(ClientAuth clientAuth, String alias, CertificateValidator validator,
                                        Provider provider) {
                return MtlsServer.forServer(clientAuth, alias, certWithKey.getX509Certificate(),
                                            certWithKey.getPrivateKey(), validator);
            }

            @Override
            public Digest getMemberId(X509Certificate key) {
                // Extract digest from certificate
                return ((SelfAddressingIdentifier) Stereotomy.decode(key).get().identifier()).getDigest();
            }
        };
    }
}
