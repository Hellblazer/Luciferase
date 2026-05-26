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
package com.hellblazer.luciferase.simulation.delos.fireflies;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.proto.Sig;
import com.hellblazer.delos.membership.Util;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.stereotomy.event.proto.Ident;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.QualifiedBase64Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.luciferase.common.grpc.PeerVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The {@link PeerVerifier} for a Delos/Fireflies cluster (RDR-005). Cryptographically proves that an inbound mTLS
 * peer certificate was issued by a member whose KERI key the local KERL commits to, and that the member is in the
 * current view — never trusting any key material the certificate asserts about itself.
 *
 * <h2>What a Delos member certificate carries</h2>
 * {@code ControlledIdentifierMember.getCertificateWithPrivateKey} (→ {@code StereotomyImpl.provision}) generates a
 * <em>fresh ephemeral TLS keypair</em> for the cert, has the member's KERI signer sign
 * {@code qb64(BasicIdentifier(tlsPublicKey))}, and encodes the subject DN as
 * {@code UID=base64url(identifier.toIdent()), DC=base64url(keriSignature.toSig())}. So the certificate is a chain of
 * two facts: the TLS handshake proves the presenter holds the cert key; the {@code DC} KERI signature proves the
 * member's committed key endorsed that cert key. Fact two is only meaningful when verified against the member's
 * committed key obtained from the KERL.
 *
 * <h2>Verification ({@link #verifiedIdentity})</h2>
 * <ol>
 *   <li>reject a certificate outside its validity window (the transport trusts any cert — no CA — so temporal
 *       validity is enforced here, not by TLS);</li>
 *   <li>parse the claimed {@link Identifier} from the DN {@code UID} (public, untrusted);</li>
 *   <li><b>view-membership gate</b> — the claimed member's {@link Digest} must be in the current view;</li>
 *   <li>parse the {@link JohnHancock} KERI signature from the DN {@code DC};</li>
 *   <li><b>cryptographic gate</b> — that signature must verify, over {@code qb64(BasicIdentifier(cert.getPublicKey()))},
 *       against the KERL-committed key for the claimed identifier ({@link Verifiers#from(KERL)});</li>
 *   <li>all gates pass ⇒ the verified member identity; otherwise reject.</li>
 * </ol>
 * A match on the DN/UID alone authenticates nothing — member identifiers are public, so a DN match is forgeable. The
 * accompanying {@code FirefliesPeerVerifierTest} proves a forged certificate (a legitimate member's identifier carried
 * by an attacker-generated key) is rejected, as the SPI requires.
 *
 * <p><b>Fail-closed.</b> Any malformed DN, unparseable field, unexpected identifier shape, missing KERL key state, or
 * verification error results in rejection ({@link Optional#empty()}); this method never throws.
 *
 * @author hal.hildebrand
 */
public final class FirefliesPeerVerifier implements PeerVerifier {

    private static final Logger log = LoggerFactory.getLogger(FirefliesPeerVerifier.class);

    private final Verifiers          verifiers;
    private final Predicate<Digest>  inCurrentView;

    /**
     * @param kerl          the local key event receipt log — the trust anchor for members' committed keys
     * @param inCurrentView predicate answering whether a member {@link Digest} is in the current Fireflies view;
     *                      evaluated per call so membership reflects the live view, not a snapshot. The production
     *                      binding MUST be built from the view's <em>active</em> members (not all tracked members), so
     *                      that an evicted node's still-valid certificate is rejected here.
     */
    public FirefliesPeerVerifier(KERL kerl, Predicate<Digest> inCurrentView) {
        this.verifiers = Verifiers.from(Objects.requireNonNull(kerl, "kerl"));
        this.inCurrentView = Objects.requireNonNull(inCurrentView, "inCurrentView");
    }

    @Override
    public Optional<String> verifiedIdentity(X509Certificate peerCertificate) {
        // (1) temporal validity — the transport trusts any cert (no CA), so notBefore/notAfter is enforced here
        try {
            peerCertificate.checkValidity();
        } catch (CertificateExpiredException | CertificateNotYetValidException e) {
            log.debug("Peer cert rejected: outside validity period");
            return Optional.empty();
        }

        try {
            Map<String, String> dn = Util.decodeDN(peerCertificate.getSubjectX500Principal().getName());
            String uid = dn.get("UID");
            String dc = dn.get("DC");
            if (uid == null || dc == null) {
                log.debug("Peer cert rejected: DN missing UID and/or DC");
                return Optional.empty();
            }

            // (2) the claimed identity, parsed from the cert DN — public, untrusted
            Identifier claimed = Identifier.from(Ident.parseFrom(Base64.getUrlDecoder().decode(uid)));
            if (!(claimed instanceof SelfAddressingIdentifier said)) {
                log.debug("Peer cert rejected: UID is not a self-addressing identifier: {}", claimed);
                return Optional.empty();
            }

            // (3) view-membership gate (cheap): is this even a current member?
            if (!inCurrentView.test(said.getDigest())) {
                log.debug("Peer cert rejected: identifier {} not in current view", said);
                return Optional.empty();
            }

            // (4) the KERI signature the cert asserts over its own TLS key
            JohnHancock certBinding = JohnHancock.from(Sig.parseFrom(Base64.getUrlDecoder().decode(dc)));
            // the message that signature must cover: the cert's actual TLS public key
            String signedOverTlsKey = QualifiedBase64Identifier.qb64(new BasicIdentifier(peerCertificate.getPublicKey()));

            // (5) cryptographic gate: verify against the KERL-committed key — NOT against anything the cert asserts.
            // Verifiers.from(kerl).verifierFor(...) always wraps the identity in a KerlVerifier; an identifier with no
            // KERL key state yields verify()==false (not an empty Optional). The orElse(false) keeps this fail-closed
            // even if a future Verifiers implementation signals the miss with an empty Optional instead.
            boolean verified = verifiers.verifierFor(claimed)
                                        .map(v -> v.verify(certBinding, signedOverTlsKey))
                                        .orElse(false);
            if (!verified) {
                log.debug("Peer cert rejected: DC signature does not verify against committed key for {}", said);
                return Optional.empty();
            }

            // all gates passed: the presenter cryptographically proved key-possession for a current member
            return Optional.of(said.getDigest().toString());
        } catch (Exception e) {
            // fail-closed: a verifier must never admit a peer because of a parsing or crypto error
            log.debug("Peer cert rejected: verification error", e);
            return Optional.empty();
        }
    }
}
