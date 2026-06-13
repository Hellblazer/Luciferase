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

import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ServerCredentials;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Objects;

/**
 * Builds gRPC transport credentials for the distributed control plane (RDR-005).
 * <p>
 * The mTLS credentials present the local cluster member's certificate and require a client certificate,
 * but <b>trust any structurally-valid certificate at the TLS layer</b>: there is no shared CA — member
 * certificates are provisioned on demand via the KERI/KERL substrate. The real, cryptographic
 * peer-identity decision is deferred to {@link PeerAuthInterceptor} plus an injected {@link PeerVerifier}
 * (KERL signature check + Fireflies view membership). Trusting all certs here is therefore intentional
 * and safe <em>only</em> when the interceptor is installed on the server.
 * <p>
 * Tests that do not exercise the auth path should use {@link #insecureServer()} / {@link #insecureChannel()}
 * (plaintext) rather than branching production code on an environment variable.
 *
 * @author hal.hildebrand
 */
public final class GrpcCredentialFactory {

    private GrpcCredentialFactory() {
    }

    /**
     * Server credentials presenting {@code certificate}/{@code key} and requiring (but not CA-validating)
     * a client certificate, so {@link PeerAuthInterceptor} can verify it.
     * <p>
     * <b>WARNING — must be paired with {@link PeerAuthInterceptor}.</b> These credentials require a client
     * certificate but trust <em>any</em> certificate at the TLS layer (no shared CA). Used without the
     * interceptor, the resulting server is encrypted but <b>UNAUTHENTICATED</b> — it admits any caller that
     * presents any certificate. Prefer {@link #serverAuth(PrivateKey, X509Certificate, PeerVerifier)}, which
     * returns the credentials and a matching interceptor together so the two cannot be separated by mistake.
     */
    public static ServerCredentials mtlsServer(PrivateKey key, X509Certificate certificate) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(certificate, "certificate");
        return TlsServerCredentials.newBuilder()
                                   .keyManager(keyManagers(key, certificate))
                                   .trustManager(ACCEPT_ANY_CERT)
                                   .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                                   .build();
    }

    /**
     * Channel credentials presenting {@code certificate}/{@code key} for mutual TLS.
     * <p>
     * <b>WARNING — no client-side server-identity check.</b> Like the server side there is no shared CA, so
     * this channel trusts <em>any</em> server certificate at the TLS layer. Authorization is currently
     * enforced only at the server (the server verifies the client). The client therefore has no
     * cryptographic assurance that the endpoint it dialed is a real cluster member — it relies on the
     * endpoint address being trusted (e.g. resolved from the Fireflies view). Symmetric client-side
     * verification is part of the RDR-005 pre-implementation spike (the {@code FirefliesPeerVerifier} work);
     * until it lands, treat {@code mtlsChannel} as "encrypt + present my identity," not "authenticate the
     * server."
     */
    public static ChannelCredentials mtlsChannel(PrivateKey key, X509Certificate certificate) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(certificate, "certificate");
        return TlsChannelCredentials.newBuilder()
                                    .keyManager(keyManagers(key, certificate))
                                    .trustManager(ACCEPT_ANY_CERT)
                                    .build();
    }

    /**
     * The two halves of server-side mTLS auth that must always be used together: the transport
     * {@link ServerCredentials} and the {@link PeerAuthInterceptor} that actually authenticates peers.
     */
    public record ServerAuth(ServerCredentials credentials, PeerAuthInterceptor interceptor) {
    }

    /**
     * Build server credentials and the matching {@link PeerAuthInterceptor} as a single unit. This is the
     * recommended way to stand up an authenticated server: it is impossible to obtain the trust-any
     * credentials without also obtaining the interceptor that performs the real verification.
     *
     * @param verifier the cryptographic peer-verification policy (see {@link PeerVerifier})
     */
    public static ServerAuth serverAuth(PrivateKey key, X509Certificate certificate, PeerVerifier verifier) {
        return new ServerAuth(mtlsServer(key, certificate), new PeerAuthInterceptor(verifier));
    }

    /**
     * Plaintext server credentials for in-process / test transports.
     */
    public static ServerCredentials insecureServer() {
        return InsecureServerCredentials.create();
    }

    /**
     * Plaintext channel credentials for in-process / test transports.
     */
    public static ChannelCredentials insecureChannel() {
        return InsecureChannelCredentials.create();
    }

    /**
     * Adapt an in-memory {@link PrivateKey} + certificate into {@code KeyManager[]} for gRPC's
     * {@code keyManager(KeyManager...)} (grpc-api accepts {@code KeyManager}/PEM, not raw key+cert).
     */
    private static KeyManager[] keyManagers(PrivateKey key, X509Certificate certificate) {
        // Transient in-memory keystore, never persisted. A fixed non-empty password is used for both the
        // key entry and the factory init: empty PKCS12 key-entry passwords are an edge case on some JVMs
        // (J9 / FIPS), whereas a consistent non-empty password is handled uniformly everywhere.
        var password = "luciferase-transient".toCharArray();
        try {
            var keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry("member", key, password, new X509Certificate[] { certificate });
            var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore, password);
            return factory.getKeyManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Unable to build key manager from member certificate", e);
        }
    }

    /**
     * Trusts any presented certificate at the TLS layer ON PURPOSE: there is no shared CA, so the
     * cryptographic peer-identity decision is made by {@link PeerAuthInterceptor} + {@link PeerVerifier},
     * not by CA pinning. See RDR-005.
     * <p>
     * This is an {@link X509ExtendedTrustManager} (not a plain {@code X509TrustManager}) specifically so
     * that the {@code SSLEngine}/{@code Socket} overloads — which JSSE uses for endpoint identification —
     * are no-ops. A plain {@code X509TrustManager} is wrapped by JSSE in a manager that ADDS hostname
     * verification on those paths, which rejected legitimate peers whose cert CN ≠ the dialed address
     * (RDR-023: {@code CertificateException: No name matching localhost found}). Disabling hostname
     * verification is correct here: a Delos/KERI member cert encodes cryptographic identity, not network
     * topology, so the dialed host is not a trust principal — peer identity is enforced by the KERL-backed
     * {@link PeerVerifier}, not the cert CN. (RDR-023, accepted 2026-06-13.)
     */
    private static final X509ExtendedTrustManager ACCEPT_ANY_CERT = new X509ExtendedTrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
