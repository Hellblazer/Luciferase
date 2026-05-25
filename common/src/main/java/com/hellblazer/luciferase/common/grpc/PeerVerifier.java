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

import java.security.cert.X509Certificate;
import java.util.Optional;

/**
 * Strategy that decides whether an inbound mTLS peer certificate belongs to a trusted cluster member,
 * and if so, what that member's identity is (RDR-005).
 * <p>
 * This is the authorization <em>policy</em>, injected into {@link PeerAuthInterceptor} (the
 * <em>mechanism</em>). It is deliberately defined here, in the leaf {@code common} module, against only
 * {@link X509Certificate} so that {@code common} carries no Delos/Fireflies dependency. The concrete
 * implementation lives where the membership/KERL substrate already lives.
 * <p>
 * <b>Security contract.</b> Because the transport layer trusts any structurally-valid certificate (there
 * is no shared CA), an implementation MUST cryptographically prove that the presenter holds the private
 * key the member identity commits to — e.g. verify the certificate's signature against the member's
 * KERI-committed public key obtained from the KERL, then confirm membership in the current view. A match
 * on the certificate's DN/UID alone is <b>forbidden</b>: member identifiers are public, so a DN match
 * authenticates nothing and is forgeable.
 *
 * @author hal.hildebrand
 */
@FunctionalInterface
public interface PeerVerifier {

    /**
     * Verify a peer certificate and return the verified member identity.
     *
     * @param peerCertificate the certificate presented by the connecting peer at the TLS layer
     * @return the verified member identity if (and only if) the peer cryptographically proves it holds
     *         the key committed by a current cluster member; {@link Optional#empty()} to reject
     */
    Optional<String> verifiedIdentity(X509Certificate peerCertificate);
}
