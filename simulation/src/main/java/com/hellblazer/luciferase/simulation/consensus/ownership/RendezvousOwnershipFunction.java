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

package com.hellblazer.luciferase.simulation.consensus.ownership;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import java.util.List;
import java.util.Objects;

/**
 * Rendezvous / highest-random-weight (HRW) implementation of {@link SpatialOwnershipFunction}.
 * <p>
 * For each active member {@code m} in {@code activeMembers}, computes a stable 64-bit weight
 * {@code w(key, m)} using a well-mixed bitwise hash of the spatial key bits combined with
 * the member digest bytes. Returns the member with the maximum weight; ties broken
 * deterministically by {@link Digest#compareTo} natural order, ensuring identical results
 * across all nodes given the same inputs.
 * <p>
 * <b>Weight function design:</b> uses a 64-bit finalizer-mix (Murmur3-style) applied to
 * {@code keyBits XOR memberBits} so that small changes in either input produce large,
 * uniform changes in the weight. No random seed, no clock dependency — purely deterministic
 * from inputs.
 * <p>
 * <b>Key stable inputs:</b> {@code key.getLevel()}, {@code key.getLowBits()},
 * {@code key.getHighBits()}. Member stable input: all available {@code long} words from the
 * member {@link Digest} bytes (big-endian), folded via a chained mix so the full digest
 * entropy participates (for a 32-byte SHA-256 digest all four 8-byte words are included).
 */
public final class RendezvousOwnershipFunction implements SpatialOwnershipFunction {

    /**
     * {@inheritDoc}
     */
    @Override
    public Digest owner(TetreeKey<?> key, List<Digest> activeMembers) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(activeMembers, "activeMembers must not be null");
        if (activeMembers.isEmpty()) {
            throw new IllegalStateException("Cannot determine owner: activeMembers is empty");
        }

        // Stable key fingerprint: mix level + low bits + high bits
        long keyFp = fingerprint(key.getLevel(), key.getLowBits(), key.getHighBits());

        Digest maxMember = null;
        long maxWeight = Long.MIN_VALUE;

        for (var member : activeMembers) {
            long memberFp = memberFingerprint(member);
            long weight = mix64(keyFp ^ memberFp);
            if (weight > maxWeight || (weight == maxWeight && (maxMember == null || member.compareTo(maxMember) > 0))) {
                maxWeight = weight;
                maxMember = member;
            }
        }
        return maxMember;
    }

    /**
     * Combine key parts into a single stable fingerprint.
     */
    private static long fingerprint(byte level, long lowBits, long highBits) {
        // Spread the level byte into the high word so it participates in the mix
        long levelSpread = ((long) (level & 0xFF)) * 0x9E3779B97F4A7C15L;
        return mix64(levelSpread ^ lowBits) ^ mix64(highBits + 1L);
    }

    /**
     * Extract a stable 64-bit word from a {@link Digest} by folding all available
     * 8-byte words (big-endian) into a single value using a chained mix. For a standard
     * 32-byte SHA-256 digest this folds all four 8-byte words; shorter digests degrade
     * gracefully because {@link #readLong} zero-pads missing bytes.
     * <p>
     * Using all available entropy prevents two members that differ only in bytes 16–31
     * from mapping to the same fingerprint and degrading to compareTo tie-breaking for
     * every key.
     */
    private static long memberFingerprint(Digest member) {
        byte[] bytes = member.getBytes();
        long w0 = readLong(bytes, 0);
        long w1 = readLong(bytes, 8);
        long w2 = readLong(bytes, 16);
        long w3 = readLong(bytes, 24);
        return w0 ^ mix64(w1 ^ mix64(w2 ^ mix64(w3)));
    }

    /**
     * Read up to 8 bytes from {@code bytes} starting at {@code offset} as a big-endian
     * {@code long}. Missing bytes are treated as zero.
     */
    private static long readLong(byte[] bytes, int offset) {
        long v = 0;
        int end = Math.min(offset + 8, bytes.length);
        for (int i = offset; i < end; i++) {
            v = (v << 8) | (bytes[i] & 0xFF);
        }
        return v;
    }

    /**
     * 64-bit Murmur3 finalizer mix: avalanche effect so every input bit affects every
     * output bit. No external state, no randomness.
     */
    private static long mix64(long h) {
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return h;
    }
}
