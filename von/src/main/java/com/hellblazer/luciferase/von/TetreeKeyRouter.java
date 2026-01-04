/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.von;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

/**
 * Routes TetreeKey spatial coordinates to responsible Fireflies members
 * using consistent hashing on the Space-Filling Curve (SFC) index.
 * <p>
 * This provides a fallback routing mechanism when VON overlay discovery
 * is unavailable or during initial join. Uses the TetreeKey's consecutive
 * index (SFC position) to deterministically map spatial locations to
 * cluster members via Fireflies ring topology.
 *
 * @author hal.hildebrand
 */
public class TetreeKeyRouter {
    private final Context<?>      context;
    private final int             ringIndex;
    private final DigestAlgorithm digestAlgo;

    /**
     * Create a new TetreeKey router
     *
     * @param context     Fireflies context for member lookup
     * @param ringIndex   Ring index to use for successor lookup (typically 0)
     * @param digestAlgo  Digest algorithm for hashing SFC indices
     */
    public TetreeKeyRouter(Context<?> context, int ringIndex, DigestAlgorithm digestAlgo) {
        this.context = context;
        this.ringIndex = ringIndex;
        this.digestAlgo = digestAlgo;
    }

    /**
     * Route a TetreeKey to the responsible member
     *
     * @param key Tetrahedral spatial key
     * @return Fireflies member responsible for this spatial region
     */
    public Member routeToKey(TetreeKey<?> key) {
        // Hash the TM-index (tetrahedral SFC position) to a digest
        // Use low bits which contain the TM-index for both compact and extended keys
        long tmIndex = key.getLowBits();
        byte[] indexBytes = longToBytes(tmIndex);
        Digest keyDigest = digestAlgo.digest(indexBytes);

        // Find successor on the ring (consistent hashing)
        return context.successor(ringIndex, keyDigest);
    }

    /**
     * Convert long to byte array for hashing
     */
    private byte[] longToBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return bytes;
    }
}
