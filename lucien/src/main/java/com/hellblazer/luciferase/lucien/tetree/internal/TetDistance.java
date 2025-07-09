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
package com.hellblazer.luciferase.lucien.tetree.internal;

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

/**
 * Helper class to store tetrahedral node index with distance for priority queue ordering
 * in ray traversal operations.
 *
 * @author hal.hildebrand
 */
public class TetDistance implements Comparable<TetDistance> {
    private final TetreeKey<? extends TetreeKey> tetIndex;
    private final float distance;

    public TetDistance(TetreeKey<? extends TetreeKey> tetIndex, float distance) {
        this.tetIndex = tetIndex;
        this.distance = distance;
    }

    public TetreeKey<? extends TetreeKey> getTetIndex() {
        return tetIndex;
    }

    public float getDistance() {
        return distance;
    }

    @Override
    public int compareTo(TetDistance other) {
        return Float.compare(this.distance, other.distance);
    }
}