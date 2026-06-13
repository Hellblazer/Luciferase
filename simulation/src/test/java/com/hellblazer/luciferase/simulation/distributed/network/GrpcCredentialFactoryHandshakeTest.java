/**
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.network;

import com.hellblazer.luciferase.common.grpc.GrpcCredentialFactory;
import com.hellblazer.luciferase.common.grpc.PeerVerifier;
import com.hellblazer.luciferase.lucien.distributed.migration.proto.BubbleMigrationServiceGrpc;
import com.hellblazer.luciferase.lucien.distributed.migration.proto.HealthCheckRequest;
import com.hellblazer.luciferase.lucien.distributed.migration.proto.HealthCheckResponse;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real gRPC mTLS handshake over the production credential path (RDR-023, Luciferase-7m9kh).
 *
 * <p>This is the artifact RDR-023 mandates: a REAL TLS handshake (not a mocked SSL session) exercising
 * {@link GrpcCredentialFactory}'s mTLS credentials + {@link io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
 * shaded-netty} transport, so the mTLS path can never silently regress. It lives in {@code simulation}
 * (not {@code common}) because {@code common} is grpc-api-only — it has no netty/service to stand up a real
 * server; {@code BubbleMigrationServiceGrpc} here provides one.
 *
 * <p><b>No {@code overrideAuthority}.</b> The client dials {@code localhost} while the cert CN is
 * {@code luciferase-test}. Before the RDR-023 fix this failed with
 * {@code CertificateException: No name matching localhost found} → {@code UNAVAILABLE}; the fix makes
 * {@code ACCEPT_ANY_CERT} an {@code X509ExtendedTrustManager} with no-op endpoint-identification overloads,
 * so the handshake completes. Authentication is then the SERVER verifying the CLIENT via the
 * {@link PeerVerifier} (the load-bearing client→server direction) — proven by the rejected-peer case.
 *
 * @author hal.hildebrand
 */
class GrpcCredentialFactoryHandshakeTest {

    // Throwaway EC self-signed cert (CN=luciferase-test) + PKCS8 key. Test fixtures only.
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

    private static final class HealthImpl extends BubbleMigrationServiceGrpc.BubbleMigrationServiceImplBase {
        @Override public void healthCheck(HealthCheckRequest req, StreamObserver<HealthCheckResponse> obs) {
            obs.onNext(HealthCheckResponse.newBuilder().build());
            obs.onCompleted();
        }
    }

    private Server startServer(PeerVerifier verifier) throws Exception {
        var auth = GrpcCredentialFactory.serverAuth(key(), cert(), verifier);
        return Grpc.newServerBuilderForPort(0, auth.credentials())
            .addService(new HealthImpl()).intercept(auth.interceptor()).build().start();
    }

    private ManagedChannel mtlsChannel(int port) throws Exception {
        // NB: NO overrideAuthority — dials "localhost" though the cert CN is "luciferase-test".
        return Grpc.newChannelBuilderForAddress("localhost", port,
            GrpcCredentialFactory.mtlsChannel(key(), cert())).build();
    }

    @Test
    void authorizedPeerCompletesRealMtlsHandshake() throws Exception {
        var server = startServer(c -> Optional.of("authorized-member"));   // verifier accepts
        ManagedChannel ch = null;
        try {
            ch = mtlsChannel(server.getPort());
            var stub = BubbleMigrationServiceGrpc.newBlockingStub(ch).withDeadlineAfter(10, TimeUnit.SECONDS);
            var resp = stub.healthCheck(HealthCheckRequest.newBuilder().build());
            // Reaching here means the real TLS handshake completed AND the server authenticated the client
            // (any handshake/auth failure throws StatusRuntimeException before this returns). A non-null
            // response is the completion proof.
            assertNotNull(resp, "healthCheck must return a response after a successful mTLS handshake");
        } finally {
            if (ch != null) { ch.shutdownNow(); ch.awaitTermination(2, TimeUnit.SECONDS); }
            server.shutdownNow(); server.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void peerRejectedByVerifierGetsUnauthenticated() throws Exception {
        var server = startServer(c -> Optional.empty());   // verifier rejects every peer
        ManagedChannel ch = null;
        try {
            ch = mtlsChannel(server.getPort());
            var stub = BubbleMigrationServiceGrpc.newBlockingStub(ch).withDeadlineAfter(10, TimeUnit.SECONDS);
            var ex = assertThrows(StatusRuntimeException.class,
                () -> stub.healthCheck(HealthCheckRequest.newBuilder().build()),
                "a peer the PeerVerifier rejects must be denied, not served");
            assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode(),
                "TLS handshake must complete (so the interceptor runs) and rejection surfaces as UNAUTHENTICATED");
        } finally {
            if (ch != null) { ch.shutdownNow(); ch.awaitTermination(2, TimeUnit.SECONDS); }
            server.shutdownNow(); server.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
