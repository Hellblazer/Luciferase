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

package com.hellblazer.luciferase.simulation.integration;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.cert.CertificateWithPrivateKey;
import com.hellblazer.delos.stereotomy.ControlledIdentifier;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;

import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * MTLS certificate generator for integration tests.
 * <p>
 * Generates self-signed X.509 certificates for secure communication
 * in test clusters, following Delos Stereotomy patterns.
 * <p>
 * Each certificate has:
 * - Unique node ID (UUID)
 * - Self-addressing identifier (SelfAddressingIdentifier)
 * - 1-day validity period (sufficient for tests)
 * - Default signature algorithm (Ed25519)
 * <p>
 * Reference: Delos MtlsTest.java beforeClass() method.
 *
 * @author hal.hildebrand
 */
public class TestCertificateGenerator {

    /**
     * Certificate with private key pair.
     *
     * @param cert       the X.509 certificate
     * @param privateKey the private key
     */
    public record CertificateWithKey(X509Certificate cert, PrivateKey privateKey) {
    }

    /**
     * Generate MTLS certificates for a test cluster.
     * <p>
     * Creates unique identities and provisions certificates for each node.
     *
     * @param nodeCount number of nodes to generate certificates for
     * @return map of node UUID to certificate+key pair
     */
    public static Map<UUID, CertificateWithKey> generateCertificates(int nodeCount) {
        try {
            // Use deterministic entropy for reproducible tests
            var entropy = SecureRandom.getInstance("SHA1PRNG");
            entropy.setSeed(new byte[]{6, 6, 6});

            // Create Stereotomy identity manager
            var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

            // Generate controlled identifiers (one per node)
            var identities = IntStream.range(0, nodeCount)
                                      .mapToObj(i -> stereotomy.newIdentifier())
                                      .toList();

            // Provision certificates with 1-day validity
            var certificates = new HashMap<UUID, CertificateWithKey>();
            for (var identity : identities) {
                var certWithKey = identity.provision(Instant.now(), Duration.ofDays(1), SignatureAlgorithm.DEFAULT);

                // Use a deterministic UUID based on the digest
                var digest = ((SelfAddressingIdentifier) identity.getIdentifier()).getDigest();
                var nodeId = UUID.nameUUIDFromBytes(digest.getBytes());

                certificates.put(nodeId, new CertificateWithKey(
                    certWithKey.getX509Certificate(),
                    certWithKey.getPrivateKey()
                ));
            }

            return certificates;

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test certificates", e);
        }
    }

    /**
     * Convert Delos CertificateWithPrivateKey to our CertificateWithKey.
     *
     * @param delos the Delos certificate wrapper
     * @return our test certificate wrapper
     */
    public static CertificateWithKey fromDelos(CertificateWithPrivateKey delos) {
        return new CertificateWithKey(delos.getX509Certificate(), delos.getPrivateKey());
    }
}
