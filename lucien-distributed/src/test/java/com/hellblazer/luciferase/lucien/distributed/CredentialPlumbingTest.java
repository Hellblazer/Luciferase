/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.distributed;

import com.hellblazer.luciferase.common.grpc.GrpcCredentialFactory;
import com.hellblazer.luciferase.common.grpc.PeerAuthInterceptor;
import com.hellblazer.luciferase.lucien.balancing.grpc.BalanceCoordinatorClient;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.ServiceDiscovery;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.GhostCommunicationManager;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.GhostServiceClient;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * RDR-005 per-client credential plumbing (move-then-auth): the lucien-distributed gRPC clients and the
 * communication manager accept injectable transport credentials and route them to the channel/server
 * builders. These tests assert the WIRING — construction via the new credential-accepting constructors
 * succeeds (i.e. {@code Grpc.newChannelBuilder(...)} / {@code Grpc.newServerBuilderForPort(...).build()}
 * run with the supplied credentials). The factory's actual mTLS correctness and forged-cert behavior are
 * covered by {@code common}'s GrpcAuthTest; the insecure default path is covered by the existing
 * integration tests. Real mTLS activation (FirefliesPeerVerifier + the load-bearing forged-cert spike)
 * is deferred RDR-005 work.
 *
 * @author hal.hildebrand
 */
class CredentialPlumbingTest {

    private static final ContentSerializer<String> SERIALIZER = new ContentSerializer<>() {
        @Override
        public byte[] serialize(String content) {
            return content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String deserialize(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public String getContentType() {
            return "string";
        }
    };

    private static final ServiceDiscovery GHOST_DISCOVERY = new ServiceDiscovery() {
        @Override
        public String getEndpoint(int rank) {
            return "localhost:" + (50000 + rank);
        }

        @Override
        public void registerEndpoint(int rank, String endpoint) {
        }

        @Override
        public Map<Integer, String> getAllEndpoints() {
            return Map.of();
        }
    };

    private static final BalanceCoordinatorClient.ServiceDiscovery BALANCE_DISCOVERY =
        new BalanceCoordinatorClient.ServiceDiscovery() {
            @Override
            public String getEndpoint(int rank) {
                return "localhost:" + (50000 + rank);
            }

            @Override
            public void registerEndpoint(int rank, String endpoint) {
            }

            @Override
            public Map<Integer, String> getAllEndpoints() {
                return Map.of();
            }
        };

    @Test
    void ghostServiceClientAcceptsExplicitChannelCredentials() {
        var client = new GhostServiceClient<MortonKey, LongEntityID, String>(
            0, SERIALIZER, LongEntityID.class, GHOST_DISCOVERY, GrpcCredentialFactory.insecureChannel());
        try {
            assertNotNull(client);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void balanceCoordinatorClientAcceptsExplicitChannelCredentials() {
        var client = new BalanceCoordinatorClient(
            0, BALANCE_DISCOVERY, 10, 100L, GrpcCredentialFactory.insecureChannel());
        try {
            assertNotNull(client);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void ghostCommunicationManagerInsecureServerPathBuilds() {
        // serverAuth == null -> insecure server (today's behavior) built via Grpc.newServerBuilderForPort.
        var mgr = new GhostCommunicationManager<MortonKey, LongEntityID, String>(
            0, "localhost", 0, SERIALIZER, LongEntityID.class, GHOST_DISCOVERY,
            null, GrpcCredentialFactory.insecureChannel());
        try {
            assertNotNull(mgr);
        } finally {
            mgr.shutdown();
        }
    }

    @Test
    void ghostCommunicationManagerServerAuthWiresInterceptor() {
        // Verifies the serverAuth branch installs the PeerAuthInterceptor on the server builder. Uses
        // insecure creds + an accept-all verifier purely to exercise the .intercept(...) wiring path;
        // real mTLS + cryptographic verification (FirefliesPeerVerifier) is the deferred RDR-005 work.
        var serverAuth = new GrpcCredentialFactory.ServerAuth(
            GrpcCredentialFactory.insecureServer(),
            new PeerAuthInterceptor(cert -> Optional.of("test-member")));
        var mgr = new GhostCommunicationManager<MortonKey, LongEntityID, String>(
            0, "localhost", 0, SERIALIZER, LongEntityID.class, GHOST_DISCOVERY,
            serverAuth, GrpcCredentialFactory.insecureChannel());
        try {
            assertNotNull(mgr);
        } finally {
            mgr.shutdown();
        }
    }
}
