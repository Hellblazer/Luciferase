/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus.committee;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for Luciferase-0frcy.19.
 * <p>
 * {@code CommitteeProtoConverter.hexToDigest()} previously hardcoded {@link DigestAlgorithm#DEFAULT}. Because
 * {@code Digest.equals()} is algorithm-aware, a Digest produced with a non-DEFAULT algorithm (e.g. SHA2_256 when
 * DEFAULT is BLAKE-family, or vice-versa) round-tripped through proto would come back tagged DEFAULT and never
 * compare equal to the original — silently discarding votes and stalling consensus (RDR-004 class).
 * <p>
 * Expected behaviour after fix: digest hex round-trip preserves the algorithm code; the reconstructed Digest equals
 * the original for ANY algorithm.
 *
 * @author hal.hildebrand
 */
public class CommitteeProtoConverterDigestAlgorithmTest {

    @Test
    public void roundTripPreservesNonDefaultAlgorithm() {
        // Pick an algorithm that is provably NOT the DEFAULT so the bug is exercised.
        DigestAlgorithm nonDefault = DigestAlgorithm.SHA2_256;
        if (nonDefault.digestCode() == DigestAlgorithm.DEFAULT.digestCode()) {
            nonDefault = DigestAlgorithm.BLAKE2B_256;
        }

        Digest original = nonDefault.digest("voter-member-id".getBytes());

        String hex = CommitteeProtoConverter.digestToHex(original);
        Digest restored = CommitteeProtoConverter.hexToDigest(hex);

        assertEquals(original.getAlgorithm(), restored.getAlgorithm(),
                     "Algorithm tag must survive the hex round-trip");
        assertEquals(original, restored,
                     "Algorithm-aware Digest.equals() must hold after round-trip (else votes are silently dropped)");
    }

    @Test
    public void roundTripPreservesDefaultAlgorithm() {
        Digest original = DigestAlgorithm.DEFAULT.digest("view-id".getBytes());

        Digest restored = CommitteeProtoConverter.hexToDigest(CommitteeProtoConverter.digestToHex(original));

        assertEquals(original, restored, "DEFAULT-algorithm digests must still round-trip");
    }
}
