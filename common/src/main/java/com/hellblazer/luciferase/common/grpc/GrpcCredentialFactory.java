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
import javax.net.ssl.X509TrustManager;
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
     * Channel credentials presenting {@code certificate}/{@code key} for mutual TLS, trusting any server
     * certificate at the TLS layer (peer identity is verified by the server-side interceptor).
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
        try {
            var keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry("member", key, new char[0], new X509Certificate[] { certificate });
            var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore, new char[0]);
            return factory.getKeyManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Unable to build key manager from member certificate", e);
        }
    }

    /**
     * Trusts any presented certificate at the TLS layer ON PURPOSE: there is no shared CA, so the
     * cryptographic peer-identity decision is made by {@link PeerAuthInterceptor} + {@link PeerVerifier},
     * not by CA pinning. See RDR-005.
     */
    private static final X509TrustManager ACCEPT_ANY_CERT = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
