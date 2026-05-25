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

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.Optional;

/**
 * gRPC server interceptor that admits a call only when the peer's mTLS certificate is verified by an
 * injected {@link PeerVerifier} (RDR-005). This is the auth <em>mechanism</em>; the cryptographic
 * <em>policy</em> (KERL signature check + Fireflies view membership) lives in the injected verifier.
 * <p>
 * On a verified call the resolved member identity is placed in {@link #PEER_IDENTITY} for downstream
 * handlers. A call with no TLS session, no peer certificate, or a certificate the verifier rejects is
 * closed with {@link Status#UNAUTHENTICATED}.
 *
 * @author hal.hildebrand
 */
public final class PeerAuthInterceptor implements ServerInterceptor {

    /** Context key carrying the verified peer member identity to downstream handlers. */
    public static final Context.Key<String> PEER_IDENTITY = Context.key("luciferase-peer-identity");

    private final PeerVerifier verifier;

    public PeerAuthInterceptor(PeerVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        var peerCertificate = peerCertificate(call);
        if (peerCertificate.isEmpty()) {
            return deny(call, "no peer certificate presented");
        }
        var identity = verifier.verifiedIdentity(peerCertificate.get());
        if (identity.isEmpty()) {
            return deny(call, "peer certificate failed verification");
        }
        var context = Context.current().withValue(PEER_IDENTITY, identity.get());
        return Contexts.interceptCall(context, call, headers, next);
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> deny(ServerCall<ReqT, RespT> call, String reason) {
        call.close(Status.UNAUTHENTICATED.withDescription(reason), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }

    private static Optional<X509Certificate> peerCertificate(ServerCall<?, ?> call) {
        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (session == null) {
            return Optional.empty();
        }
        try {
            Certificate[] chain = session.getPeerCertificates();
            if (chain.length > 0 && chain[0] instanceof X509Certificate x509) {
                return Optional.of(x509);
            }
            return Optional.empty();
        } catch (SSLPeerUnverifiedException e) {
            return Optional.empty();
        }
    }
}
