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
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.cert.BcX500NameDnImpl;
import com.hellblazer.delos.cryptography.cert.CertExtension;
import com.hellblazer.delos.cryptography.cert.Certificates;
import com.hellblazer.delos.membership.Util;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.QualifiedBase64Identifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * SPI-mandated regression test for {@link FirefliesPeerVerifier} (RDR-005). Promotes the merged forged-cert spike
 * (PR #104) into a test of the production verifier, and adds the view-membership cases the spike did not cover.
 *
 * <p>The verifier has two independent gates — current-view membership and the cryptographic KERI binding. These tests
 * exercise each in isolation:
 * <ul>
 *   <li>{@link #acceptsLegitimateMemberCert()} — both gates pass.</li>
 *   <li>{@link #rejectsForgery()} — membership passes (victim's public UID), crypto fails (attacker key). The
 *       load-bearing proof: a DN-UID match authenticates nothing.</li>
 *   <li>{@link #rejectsValidCertOutsideCurrentView()} — crypto would pass, membership fails.</li>
 *   <li>{@link #rejectsCertWithNoMemberDn()} / {@link #neverThrowsOnGarbageCert()} — fail-closed robustness.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class FirefliesPeerVerifierTest {

    private static final Instant  VALID_FROM = Instant.now();
    private static final Duration VALID_FOR  = Duration.ofHours(1);

    private MemKERL                    kerl;
    private ControlledIdentifierMember member;
    private Digest                     memberDigest;
    private X509Certificate            legitimateCert;
    private Predicate<Digest>          memberInView;

    @BeforeEach
    void provisionMember() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 0x5, 0x1, 0x5 });
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        memberDigest = member.getId();
        memberInView = Set.of(memberDigest)::contains;
        legitimateCert = member.getCertificateWithPrivateKey(VALID_FROM, VALID_FOR, SignatureAlgorithm.DEFAULT)
                               .getX509Certificate();
    }

    @Test
    void acceptsLegitimateMemberCert() {
        var verifier = new FirefliesPeerVerifier(kerl, memberInView);
        var identity = verifier.verifiedIdentity(legitimateCert);
        assertTrue(identity.isPresent(), "legitimate member cert must verify");
        assertEquals(memberDigest.toString(), identity.get(), "verified identity must be the member digest");
    }

    /**
     * THE LOAD-BEARING ASSERTION. A cert carrying the victim's public UID but signed/keyed by an attacker is rejected:
     * the DC signature does not verify against the victim's KERL-committed key. Membership passes (same UID), so the
     * rejection is purely the cryptographic gate — exactly the gate independence the verifier promises.
     */
    @Test
    void rejectsForgery() {
        var forged = forgeCertImpersonating(legitimateCert);
        // the forgery genuinely names the victim member (so the reject below is crypto, not identity-mismatch)
        assertTrue(memberInView.test(memberDigest), "precondition: victim is a current member");
        var verifier = new FirefliesPeerVerifier(kerl, memberInView);
        assertFalse(verifier.verifiedIdentity(forged).isPresent(),
                    "forged cert (victim UID + attacker key) must be REJECTED by KERL-committed-key verification");
    }

    /**
     * A cryptographically valid cert from a member NOT in the current view is rejected by the membership gate, even
     * though its DC signature would verify against the KERL.
     */
    @Test
    void rejectsValidCertOutsideCurrentView() {
        Predicate<Digest> emptyView = d -> false;
        var verifier = new FirefliesPeerVerifier(kerl, emptyView);
        assertFalse(verifier.verifiedIdentity(legitimateCert).isPresent(),
                    "valid cert whose member is not in the current view must be REJECTED");
    }

    @Test
    void rejectsCertWithNoMemberDn() {
        // a plain self-signed cert with no UID/DC encoding — decodeDN yields neither field
        KeyPair stranger = SignatureAlgorithm.DEFAULT.generateKeyPair();
        X509Certificate plain = Certificates.selfSign(false, new BcX500NameDnImpl("CN=stranger"), stranger, VALID_FROM,
                                                      VALID_FROM.plus(VALID_FOR), List.<CertExtension>of());
        var verifier = new FirefliesPeerVerifier(kerl, d -> true); // even with an accept-all view, no member DN
        assertFalse(verifier.verifiedIdentity(plain).isPresent(), "cert without member UID/DC must be REJECTED");
    }

    @Test
    void neverThrowsOnGarbageCert() {
        // syntactically valid base64url values that are NOT a valid Ident / Sig proto: exercises the proto-parse
        // path inside verifiedIdentity (not merely the DN-parse path) and must fail closed, not throw.
        X509Certificate garbage = Certificates.selfSign(false, new BcX500NameDnImpl("UID=AAAA, DC=AAAA"),
                                                        SignatureAlgorithm.DEFAULT.generateKeyPair(), VALID_FROM,
                                                        VALID_FROM.plus(VALID_FOR), List.<CertExtension>of());
        var verifier = new FirefliesPeerVerifier(kerl, d -> true);
        assertFalse(verifier.verifiedIdentity(garbage).isPresent(), "garbage UID/DC must be REJECTED without throwing");
    }

    @Test
    void rejectsExpiredCert() throws Exception {
        // Delos refuses to MINT an out-of-window cert (Certificates.sign calls checkValidity at build time), so an
        // expired cert only arises from time passing after issuance. Simulate that condition deterministically: a
        // cert whose checkValidity() reports expiry must be rejected on the validity gate, before any DN/crypto work.
        var expired = mock(X509Certificate.class);
        doThrow(new CertificateExpiredException("expired")).when(expired).checkValidity();
        var verifier = new FirefliesPeerVerifier(kerl, memberInView);
        assertFalse(verifier.verifiedIdentity(expired).isPresent(), "expired member cert must be REJECTED");
    }

    @Test
    void rejectsIdentifierAbsentFromKerl() throws Exception {
        // A real, valid, in-view cert whose member lives in a DIFFERENT KERL, unknown to this verifier's KERL. Even
        // with an accept-all view, the cryptographic gate rejects: no committed key state here for that identifier.
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 0x7, 0x7, 0x7 });
        var otherStereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        var stranger = new ControlledIdentifierMember(otherStereotomy.newIdentifier());
        var strangerCert = stranger.getCertificateWithPrivateKey(VALID_FROM, VALID_FOR, SignatureAlgorithm.DEFAULT)
                                   .getX509Certificate();
        var verifier = new FirefliesPeerVerifier(kerl, d -> true);
        assertFalse(verifier.verifiedIdentity(strangerCert).isPresent(),
                    "cert whose member has no key state in this KERL must be REJECTED");
    }

    // ---- the attacker: same UID, attacker key, internally-consistent-but-wrong DC binding (from the spike) ----

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
