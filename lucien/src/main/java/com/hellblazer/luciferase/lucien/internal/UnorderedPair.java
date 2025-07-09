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
package com.hellblazer.luciferase.lucien.internal;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.Objects;

/**
 * An unordered pair of entities for collision detection tracking.
 * This class ensures consistent ordering and equality for entity pairs
 * regardless of the order they are added.
 *
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public class UnorderedPair<ID extends EntityID> {
    private final ID first;
    private final ID second;

    /**
     * Create an unordered pair with consistent ordering
     */
    public UnorderedPair(ID id1, ID id2) {
        if (id1.compareTo(id2) < 0) {
            this.first = id1;
            this.second = id2;
        } else {
            this.first = id2;
            this.second = id1;
        }
    }

    public ID getFirst() {
        return first;
    }

    public ID getSecond() {
        return second;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UnorderedPair<?> that = (UnorderedPair<?>) o;
        return Objects.equals(first, that.first) && Objects.equals(second, that.second);
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, second);
    }

    @Override
    public String toString() {
        return "(" + first + ", " + second + ")";
    }
}