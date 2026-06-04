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

package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;

import javax.vecmath.Point3i;
import java.io.Serializable;

/**
 * Serializable wire representation of {@link BubbleBounds} (Luciferase-vzyrf, completes "Phase 6B").
 * <p>
 * {@code BubbleBounds} carries a {@code TetreeKey} root and two {@code Point3i} RDGCS corners — none
 * of which are on the strict RDR-004 deserialization allow-list ({@link
 * com.hellblazer.luciferase.simulation.von.transport.VonTransportFilter}). Rather than admit those
 * gadget-surface types into the network deserialization path, this record decomposes the bounds into
 * primitives only (long/int), exactly as {@code TransportVonMessage}/{@code TransportNeighborInfo}
 * decompose {@code Point3f}/{@code Point3d}. The {@code TetreeKey} round-trips losslessly via its
 * {@code (level, lowBits, highBits)} identity ({@link TetreeKey#create(byte, long, long)}).
 * <p>
 * Wire identity: {@code level}, {@code lowBits}, {@code highBits} reconstruct the root key;
 * {@code rdgMinX/Y/Z} and {@code rdgMaxX/Y/Z} reconstruct the RDGCS AABB corners verbatim.
 *
 * @author hal.hildebrand
 */
public record TransportBubbleBounds(
    byte level,
    long lowBits,
    long highBits,
    int rdgMinX,
    int rdgMinY,
    int rdgMinZ,
    int rdgMaxX,
    int rdgMaxY,
    int rdgMaxZ
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Decompose a {@link BubbleBounds} into its primitive wire form.
     *
     * @param bounds the bounds to serialize, or {@code null}
     * @return the wire form, or {@code null} if {@code bounds} is {@code null}
     */
    public static TransportBubbleBounds from(BubbleBounds bounds) {
        if (bounds == null) {
            return null;
        }
        var key = bounds.rootKey();
        var min = bounds.rdgMin();
        var max = bounds.rdgMax();
        return new TransportBubbleBounds(
            key.getLevel(), key.getLowBits(), key.getHighBits(),
            min.x, min.y, min.z,
            max.x, max.y, max.z
        );
    }

    /**
     * Reconstruct the {@link BubbleBounds} from its primitive wire form.
     *
     * @return the reconstructed bounds (round-trips {@code equals} with the original)
     */
    public BubbleBounds toBubbleBounds() {
        TetreeKey<?> key = TetreeKey.create(level, lowBits, highBits);
        return BubbleBounds.of(key, new Point3i(rdgMinX, rdgMinY, rdgMinZ),
                               new Point3i(rdgMaxX, rdgMaxY, rdgMaxZ));
    }
}
