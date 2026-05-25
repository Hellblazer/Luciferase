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

package com.hellblazer.luciferase.common.grpc;

import io.grpc.Attributes;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerCredentials;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code common} gRPC auth mechanism (RDR-005): {@link GrpcCredentialFactory} and
 * {@link PeerAuthInterceptor}. The Delos/KERL verification <em>policy</em> ({@link PeerVerifier}) is
 * stubbed here; its real implementation and the forged-cert-rejection spike live where Delos lives.
 *
 * @author hal.hildebrand
 */
class GrpcAuthTest {

    // Throwaway EC self-signed cert + PKCS8 key (CN=luciferase-test, UID=test-member). Test fixtures only.
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

    private static final String KEY_PKCS8_BASE64 =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgsAplYmfGNfNKlgBP"
        + "7Mo+lZNJFSrI0ydYoGs3NYyvpKmhRANCAASWw24yK2cQXO6VdkNcmGQepsKRYOv+"
        + "FwNEu8+1etdJikk6YQoHtgMHNO1myJbGUAo/bzVszMNGLGW0eQY2b/Ds";

    private static X509Certificate testCertificate() throws Exception {
        var factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(
            new ByteArrayInputStream(CERT_PEM.getBytes(StandardCharsets.UTF_8)));
    }

    private static PrivateKey testKey() throws Exception {
        var der = Base64.getDecoder().decode(KEY_PKCS8_BASE64);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    // ---- GrpcCredentialFactory ------------------------------------------------------------------

    @Test
    void insecureCredentialsAreCreated() {
        ServerCredentials server = GrpcCredentialFactory.insecureServer();
        ChannelCredentials channel = GrpcCredentialFactory.insecureChannel();
        assertNotNull(server, "insecure server credentials");
        assertNotNull(channel, "insecure channel credentials");
    }

    @Test
    void mtlsCredentialsBuildFromKeyAndCert() throws Exception {
        var key = testKey();
        var cert = testCertificate();
        assertNotNull(GrpcCredentialFactory.mtlsServer(key, cert), "mTLS server credentials");
        assertNotNull(GrpcCredentialFactory.mtlsChannel(key, cert), "mTLS channel credentials");
    }

    @Test
    void serverAuthBundlesCredentialsAndInterceptor() throws Exception {
        var bundle = GrpcCredentialFactory.serverAuth(testKey(), testCertificate(), acceptingVerifier("m1"));
        assertNotNull(bundle.credentials(), "bundle must carry server credentials");
        assertNotNull(bundle.interceptor(), "bundle must carry the matching interceptor");
    }

    // ---- PeerAuthInterceptor --------------------------------------------------------------------

    @Test
    void deniesWhenNoSslSession() {
        var handler = new RecordingHandler();
        var call = new FakeServerCall(Attributes.EMPTY);
        var verifier = rejectingVerifier();

        new PeerAuthInterceptor(verifier).interceptCall(call, new Metadata(), handler);

        assertFalse(handler.started, "handler must not be invoked without a TLS session");
        assertEquals(Status.Code.UNAUTHENTICATED, call.closedStatus.getCode());
    }

    @Test
    void deniesWhenPeerUnverifiedAtTls() {
        var handler = new RecordingHandler();
        var call = new FakeServerCall(attributesWith(unverifiedSession()));

        // An accepting verifier is used deliberately: the denial must come from the missing peer cert
        // (getPeerCertificates throws SSLPeerUnverifiedException), NOT the verifier — which is never called.
        new PeerAuthInterceptor(acceptingVerifier("anyone")).interceptCall(call, new Metadata(), handler);

        assertFalse(handler.started, "handler must not be invoked when no peer cert is available");
        assertEquals(Status.Code.UNAUTHENTICATED, call.closedStatus.getCode());
    }

    @Test
    void deniesWhenPeerCertificateChainIsEmpty() {
        var handler = new RecordingHandler();
        var call = new FakeServerCall(attributesWith(sessionWithCerts())); // zero-length chain

        new PeerAuthInterceptor(acceptingVerifier("anyone")).interceptCall(call, new Metadata(), handler);

        assertFalse(handler.started, "handler must not be invoked when the peer cert chain is empty");
        assertEquals(Status.Code.UNAUTHENTICATED, call.closedStatus.getCode());
    }

    @Test
    void deniesWhenVerifierRejects() throws Exception {
        var handler = new RecordingHandler();
        var call = new FakeServerCall(attributesWith(sessionWithCerts(testCertificate())));

        new PeerAuthInterceptor(rejectingVerifier()).interceptCall(call, new Metadata(), handler);

        assertFalse(handler.started, "handler must not be invoked when the verifier rejects the cert");
        assertEquals(Status.Code.UNAUTHENTICATED, call.closedStatus.getCode());
    }

    @Test
    void admitsVerifiedPeerAndExposesIdentity() throws Exception {
        var handler = new RecordingHandler();
        var call = new FakeServerCall(attributesWith(sessionWithCerts(testCertificate())));

        new PeerAuthInterceptor(acceptingVerifier("member-42")).interceptCall(call, new Metadata(), handler);

        assertTrue(handler.started, "handler must be invoked for a verified peer");
        assertNull(call.closedStatus, "a verified call must not be closed by the interceptor");
        assertEquals("member-42", handler.capturedIdentity, "verified identity must be exposed in context");
    }

    // ---- stubs / fakes --------------------------------------------------------------------------

    private static PeerVerifier acceptingVerifier(String identity) {
        return cert -> Optional.of(identity);
    }

    private static PeerVerifier rejectingVerifier() {
        return cert -> Optional.empty();
    }

    private static Attributes attributesWith(SSLSession session) {
        return Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build();
    }

    private static SSLSession sessionWithCerts(Certificate... certs) {
        return proxySession((method, args) -> {
            if (method.getName().equals("getPeerCertificates")) {
                return certs;
            }
            return objectMethodOrNull(method, args);
        });
    }

    private static SSLSession unverifiedSession() {
        return proxySession((method, args) -> {
            if (method.getName().equals("getPeerCertificates")) {
                throw new SSLPeerUnverifiedException("no peer certificate");
            }
            return objectMethodOrNull(method, args);
        });
    }

    private interface SessionHandler {
        Object handle(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static SSLSession proxySession(SessionHandler handler) {
        return (SSLSession) Proxy.newProxyInstance(GrpcAuthTest.class.getClassLoader(),
                                                   new Class<?>[] { SSLSession.class },
                                                   (proxy, method, args) -> handler.handle(method, args));
    }

    private static Object objectMethodOrNull(java.lang.reflect.Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "fake-ssl-session";
            case "hashCode" -> System.identityHashCode(method);
            case "equals" -> false;
            default -> null;
        };
    }

    private static final class RecordingHandler implements ServerCallHandler<byte[], byte[]> {
        boolean started;
        String  capturedIdentity;

        @Override
        public ServerCall.Listener<byte[]> startCall(ServerCall<byte[], byte[]> call, Metadata headers) {
            started = true;
            capturedIdentity = PeerAuthInterceptor.PEER_IDENTITY.get();
            return new ServerCall.Listener<>() {
            };
        }
    }

    private static final class FakeServerCall extends ServerCall<byte[], byte[]> {
        private final Attributes attributes;
        Status closedStatus;

        FakeServerCall(Attributes attributes) {
            this.attributes = attributes;
        }

        @Override
        public Attributes getAttributes() {
            return attributes;
        }

        @Override
        public void request(int numMessages) {
        }

        @Override
        public void sendHeaders(Metadata headers) {
        }

        @Override
        public void sendMessage(byte[] message) {
        }

        @Override
        public void close(Status status, Metadata trailers) {
            this.closedStatus = status;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public MethodDescriptor<byte[], byte[]> getMethodDescriptor() {
            return null;
        }
    }
}
