/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.distributed.network;

import com.hellblazer.luciferase.common.grpc.GrpcCredentialFactory;
import com.hellblazer.luciferase.common.grpc.PeerVerifier;
import com.hellblazer.luciferase.lucien.distributed.migration.proto.BubbleMigrationServiceGrpc;
import com.hellblazer.luciferase.lucien.distributed.migration.proto.HealthCheckRequest;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.events.EntityDepartureEvent;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mTLS + peer-identity binding for GrpcBubbleNetworkChannel migration RPCs (Luciferase-l9dny, RDR-023).
 *
 * <p>Verifies the wiring added by l9dny on the now-proven mTLS transport (RDR-023): when credentials are
 * configured via {@link GrpcBubbleNetworkChannel#setCredentials}, the server installs the {@code ServerAuth}
 * mTLS credentials + the {@code PeerAuthInterceptor}, and outbound channels use mutual-TLS — so (a) an
 * authorized peer's migration RPC round-trips end-to-end and (b) a peer whose certificate identity the
 * {@link PeerVerifier} rejects is denied with {@code UNAUTHENTICATED}.
 *
 * <p>No {@code overrideAuthority} workaround is needed: the RDR-023 trust-manager fix disables client TLS
 * hostname verification (the cert CN is not a trust principal in the KERI model), so the handshake completes
 * even though the dialed address (e.g. {@code localhost}) ≠ the cert CN. The {@link PeerVerifier} double here
 * exercises the transport+interceptor wiring; the real KERI verifier's correctness is covered by
 * {@code FirefliesPeerVerifierTest}.
 *
 * @author hal.hildebrand
 */
class GrpcBubbleNetworkChannelMtlsTest {

    private static final String CERT_PEM = """
        -----BEGIN CERTIFICATE-----
        MIIBxTCCAWugAwIBAgIUSJNl7QU354hZbcNYf3iUuWi26HswCgYIKoZIzj0EAwIw
        NzEYMBYGA1UEAwwPbHVjaWZlcmFzZS10ZXN0MRswGQYKCZImiZPyLGQBAQwLdGVz
        dC1tZW1iZXIwIBcNMjYwNTI1MTU0MTU5WhgPMjEyNjA1MDExNTQxNTlaMDcxGDAW
        BgNVBAMMD2x1Y2lmZXJhc2UtdGVzdDEbMBkGCgmSJomT8ixkAQEMC3Rlc3QtbWVt
        YmVyMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAElsNuMitnEFzulXZDXJhkHqbC
        kWDr/hcDRLvPtXrXSYpJOmEKB7YDBzTtZsiWxlAKP281bMzDRixltHkGNm/w7KNT
        MFEwHQYDVR0OBBYEFENQByxeLJtrYTtNlKKWrIBhg5HkMB8GA1UdIwQYMBaAFENQ
        ByxeLJtrYTtNlKKWrIBhg5HkMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwID
        SAAwRQIhALpV3QSUouQ6F5vNtCF5H7jEGwHFzmVeXQpBIfYdoFulAiAMkz16j7tV
        rrMLPIOdjMe2d4NGkUSfnwX/S9EW1TQntw==
        -----END CERTIFICATE-----
        """;
    private static final String KEY_B64 =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgsAplYmfGNfNKlgBP"
        + "7Mo+lZNJFSrI0ydYoGs3NYyvpKmhRANCAASWw24yK2cQXO6VdkNcmGQepsKRYOv+"
        + "FwNEu8+1etdJikk6YQoHtgMHNO1myJbGUAo/bzVszMNGLGW0eQY2b/Ds";

    private static X509Certificate cert() throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(CERT_PEM.getBytes(StandardCharsets.UTF_8)));
    }
    private static PrivateKey key() throws Exception {
        return KeyFactory.getInstance("EC").generatePrivate(
            new PKCS8EncodedKeySpec(Base64.getDecoder().decode(KEY_B64)));
    }

    @Test
    void authorizedPeerCompletesMtlsMigrationRpc() throws Exception {
        var key = key();
        var cert = cert();
        PeerVerifier accept = peerCert -> Optional.of("authorized-peer");

        var sourceId = UUID.randomUUID();
        var targetId = UUID.randomUUID();
        var source = new GrpcBubbleNetworkChannel();
        var target = new GrpcBubbleNetworkChannel();
        // Both sides on mTLS (no plaintext opt-in): server installs creds + PeerAuthInterceptor, client uses
        // mutual-TLS channel credentials. No overrideAuthority — the RDR-023 fix makes the handshake work.
        source.setCredentials(GrpcCredentialFactory.serverAuth(key, cert, accept),
                              GrpcCredentialFactory.mtlsChannel(key, cert));
        target.setCredentials(GrpcCredentialFactory.serverAuth(key, cert, accept),
                              GrpcCredentialFactory.mtlsChannel(key, cert));
        try {
            source.initialize(sourceId, "localhost:0");
            target.initialize(targetId, "localhost:0");
            source.registerNode(targetId, target.getLocalAddress());
            target.registerNode(sourceId, source.getLocalAddress());

            var received = new CountDownLatch(1);
            var receivedSourceId = new AtomicReference<UUID>();
            target.setEntityDepartureListener((sid, ev) -> {
                receivedSourceId.set(sid);
                received.countDown();
            });

            var event = new EntityDepartureEvent(UUID.randomUUID(), sourceId, targetId,
                                                 EntityMigrationState.MIGRATING_OUT, 1_000L);
            assertTrue(source.sendEntityDeparture(targetId, event), "send must dispatch over mTLS");
            assertTrue(received.await(5, TimeUnit.SECONDS),
                       "an authorized mTLS peer's migration RPC must be delivered to the target listener");
            assertEquals(sourceId, receivedSourceId.get(), "delivered event must carry the source node id");
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void unauthorizedPeerIsRejectedWithUnauthenticated() throws Exception {
        var key = key();
        var cert = cert();
        // Verifier rejects every peer — the PeerAuthInterceptor must deny the call before the service runs.
        PeerVerifier reject = peerCert -> Optional.empty();

        var target = new GrpcBubbleNetworkChannel();
        target.setCredentials(GrpcCredentialFactory.serverAuth(key, cert, reject), null);
        ManagedChannel clientChannel = null;
        try {
            target.initialize(UUID.randomUUID(), "localhost:0");
            var parts = target.getLocalAddress().split(":");
            var host = parts[0];
            var port = Integer.parseInt(parts[1]);

            // Direct mTLS blocking stub: deterministic — the interceptor rejects pre-handler for ANY method,
            // and we observe the status synchronously (the channel's async send would swallow it into a log).
            clientChannel = Grpc.newChannelBuilderForAddress(host, port,
                                                             GrpcCredentialFactory.mtlsChannel(key, cert)).build();
            var stub = BubbleMigrationServiceGrpc.newBlockingStub(clientChannel)
                .withDeadlineAfter(10, TimeUnit.SECONDS);
            var ex = assertThrows(StatusRuntimeException.class,
                                  () -> stub.healthCheck(HealthCheckRequest.newBuilder().build()),
                                  "a peer rejected by the PeerVerifier must be denied, not served");
            assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode(),
                         "the handshake completes and rejection surfaces as UNAUTHENTICATED from the interceptor");
        } finally {
            if (clientChannel != null) {
                clientChannel.shutdownNow();
                clientChannel.awaitTermination(2, TimeUnit.SECONDS);
            }
            target.close();
        }
    }
}
