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
package com.hellblazer.luciferase.simulation.delos.security;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.cryptography.cert.BcX500NameDnImpl;
import com.hellblazer.delos.cryptography.cert.CertExtension;
import com.hellblazer.delos.cryptography.cert.Certificates;
import com.hellblazer.delos.cryptography.proto.Sig;
import com.hellblazer.delos.membership.Util;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.stereotomy.event.proto.Ident;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.QualifiedBase64Identifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-005 load-bearing SPIKE (throwaway). Settles the pre-implementation question the RDR flags before any
 * FirefliesPeerVerifier production wiring: <b>what key material does step 3 (cryptographic cert verification)
 * trust, and does a forged cert actually get rejected?</b>
 *
 * <h2>What a Delos member cert really carries</h2>
 * {@code ControlledIdentifierMember.getCertificateWithPrivateKey} delegates to
 * {@code StereotomyImpl.ControlledIdentifierImpl.provision}, which (verified against delos-0.5.1 sources):
 * <ol>
 *   <li>generates a <em>fresh ephemeral TLS keypair</em> ({@code algo.generateKeyPair}) — this is the cert's
 *       subject key, and the TLS handshake proves the presenter holds its private half;</li>
 *   <li>has the member's <em>KERI establishment-key signer</em> sign {@code qb64(BasicIdentifier(tlsPublicKey))};</li>
 *   <li>encodes the subject DN as {@code UID=<base64url(identifier.toIdent())>, DC=<base64url(keriSignature.toSig())>}
 *       and self-signs the X.509 cert with the ephemeral TLS keypair.</li>
 * </ol>
 * So the cert is a chain of two facts: (a) TLS handshake ⇒ presenter owns the cert key; (b) the {@code DC} KERI
 * signature ⇒ the member's KERI key endorsed that cert key. Fact (b) is only meaningful if verified against the
 * member's KERI-committed key <em>from the KERL</em> — never against any key material the cert asserts about itself.
 *
 * <h2>The forgeability the RDR warns about</h2>
 * {@code Member.getMemberIdentifier(cert)} simply parses {@code UID} → {@code Digest} (no crypto). Member digests
 * are public in KERI. An attacker can therefore mint a self-signed cert carrying a victim's {@code UID} plus their
 * own key, and the naive "DN UID matches a known member" check accepts it.
 *
 * <h2>What this spike proves</h2>
 * <ul>
 *   <li><b>Vulnerability:</b> {@link #naiveDnUidMatchAcceptsForgery()} — the DN-UID-match check accepts the forgery.</li>
 *   <li><b>Fix (Answer A):</b> {@link #cryptographicVerificationRejectsForgery()} — verifying the {@code DC}
 *       signature over the cert's TLS key against the KERL-committed key (looked up by the UID digest) rejects the
 *       forgery, while {@link #cryptographicVerificationAcceptsLegitimateCert()} accepts the legitimate cert.</li>
 * </ul>
 *
 * <b>Conclusion for FirefliesPeerVerifier:</b> the step-3 key material MUST come from a local KERL lookup keyed by
 * the member digest ({@code Verifiers.from(kerl).verifierFor(identifier)}). Trusting any key the cert carries about
 * itself (e.g. a {@code DC} public-key field, or the cert's own subject key in isolation) is forgeable.
 *
 * <p>This test is intentionally self-contained and disposable: it reproduces the relevant slice of
 * {@code provision()} inline so the proof does not depend on private Delos internals.
 *
 * @author hal.hildebrand
 */
class ForgedCertRejectionSpikeTest {

    private static final Instant  VALID_FROM = Instant.now();
    private static final Duration VALID_FOR  = Duration.ofHours(1);

    private MemKERL                  kerl;
    private ControlledIdentifierMember member;
    private Identifier               memberIdentifier;
    private X509Certificate          legitimateCert;

    @BeforeEach
    void provisionMember() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 0x5, 0x1, 0x5 });
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        memberIdentifier = member.getIdentifier().getIdentifier();
        legitimateCert = member.getCertificateWithPrivateKey(VALID_FROM, VALID_FOR, SignatureAlgorithm.DEFAULT)
                               .getX509Certificate();
    }

    /**
     * The legitimately provisioned cert verifies: the DC KERI signature over the cert's TLS key checks out against
     * the member's KERL-committed key.
     */
    @Test
    void cryptographicVerificationAcceptsLegitimateCert() throws Exception {
        var verified = verifyAgainstKerl(legitimateCert, kerl);
        assertTrue(verified.isPresent(), "legitimate member cert must verify against the KERL-committed key");
        assertEquals(memberIdentifier, verified.get(), "verified identity must be the provisioning member");
    }

    /**
     * THE LOAD-BEARING ASSERTION. A cert carrying the victim's public UID but signed/keyed by an attacker is
     * rejected, because the DC signature does not verify against the victim's KERL-committed key.
     */
    @Test
    void cryptographicVerificationRejectsForgery() throws Exception {
        var forged = forgeCertImpersonating(legitimateCert);
        // Pin the rejection to the signature check, not an accidental "identity not found" path: the forgery
        // genuinely names the victim (same UID resolves to the same identity) and the victim's committed key IS
        // present in the KERL. The ONLY remaining reason verifyAgainstKerl can reject is step (5) failing.
        assertTrue(naiveUidMatches(forged, memberIdentifier), "forgery must actually name the victim member");
        assertTrue(Verifiers.from(kerl).verifierFor(memberIdentifier).isPresent(),
                   "victim committed key must exist in the KERL (rules out 'identity not found' as the reject reason)");
        var verified = verifyAgainstKerl(forged, kerl);
        assertFalse(verified.isPresent(),
                    "forged cert (victim UID + attacker key) must be REJECTED by KERL-committed-key verification");
    }

    /**
     * Demonstrates the forgeability the RDR warns about: the naive "DN UID resolves to a known member" check
     * accepts the forgery outright. This is what FirefliesPeerVerifier must NOT do.
     */
    @Test
    void naiveDnUidMatchAcceptsForgery() throws Exception {
        var forged = forgeCertImpersonating(legitimateCert);
        assertTrue(naiveUidMatches(forged, memberIdentifier),
                   "naive DN-UID match accepts the forgery — this is the vulnerability");
        // sanity: the same naive check also accepts the legitimate cert (so the contrast is apples-to-apples)
        assertTrue(naiveUidMatches(legitimateCert, memberIdentifier));
    }

    // ---- candidate FirefliesPeerVerifier algorithm (Answer A): trust the KERL, never the cert's self-assertions ----

    private static Optional<Identifier> verifyAgainstKerl(X509Certificate cert, KERL kerl) throws Exception {
        Map<String, String> dn = Util.decodeDN(cert.getSubjectX500Principal().getName());
        String uid = dn.get("UID");
        String dc = dn.get("DC");
        if (uid == null || dc == null) {
            return Optional.empty();
        }
        // (1) the *claimed* identity, parsed from the cert DN (public, untrusted)
        Identifier claimed = Identifier.from(Ident.parseFrom(Base64.getUrlDecoder().decode(uid)));
        // (2) the KERI signature the cert asserts over its own TLS key
        JohnHancock certBinding = JohnHancock.from(Sig.parseFrom(Base64.getUrlDecoder().decode(dc)));
        // (3) the message that signature must cover: the cert's actual TLS public key
        String signedOverTlsKey = QualifiedBase64Identifier.qb64(new BasicIdentifier(cert.getPublicKey()));
        // (4) TRUST ANCHOR: the committed key from the local KERL, keyed by the claimed identity — not the cert
        Optional<Verifier> committed = Verifiers.from(kerl).verifierFor(claimed);
        if (committed.isEmpty()) {
            return Optional.empty();
        }
        // (5) the binding holds only if the committed key actually endorsed this cert's TLS key
        return committed.get().verify(certBinding, signedOverTlsKey) ? Optional.of(claimed) : Optional.empty();
    }

    // ---- the naive, forgeable check the RDR flags ----

    private static boolean naiveUidMatches(X509Certificate cert, Identifier knownMember) throws Exception {
        Map<String, String> dn = Util.decodeDN(cert.getSubjectX500Principal().getName());
        String uid = dn.get("UID");
        if (uid == null) {
            return false;
        }
        Identifier claimed = Identifier.from(Ident.parseFrom(Base64.getUrlDecoder().decode(uid)));
        return claimed.equals(knownMember); // accepts anything that merely *names* a known member
    }

    // ---- the attacker: same UID, attacker key, internally-consistent-but-wrong DC binding ----

    private static X509Certificate forgeCertImpersonating(X509Certificate victimCert) {
        Map<String, String> victimDn = Util.decodeDN(victimCert.getSubjectX500Principal().getName());
        String victimUid = victimDn.get("UID"); // public — lifted straight off the victim's cert

        KeyPair attacker = SignatureAlgorithm.DEFAULT.generateKeyPair();
        // The attacker signs their OWN TLS key with their OWN key: a structurally valid DC that verifies against the
        // attacker's key, but NOT against the victim's KERL-committed key.
        JohnHancock attackerBinding = new Signer.SignerImpl(attacker.getPrivate(), ULong.valueOf(0))
            .sign(QualifiedBase64Identifier.qb64(new BasicIdentifier(attacker.getPublic())));
        String dc = Base64.getUrlEncoder().withoutPadding().encodeToString(attackerBinding.toSig().toByteArray());

        String forgedDn = String.format("UID=%s, DC=%s", victimUid, dc);
        return Certificates.selfSign(false, new BcX500NameDnImpl(forgedDn), attacker, VALID_FROM,
                                     VALID_FROM.plus(VALID_FOR), List.<CertExtension>of());
    }
}
