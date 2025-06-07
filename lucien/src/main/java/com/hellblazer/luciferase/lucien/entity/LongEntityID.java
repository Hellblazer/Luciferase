/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the Luciferase.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.entity;

import java.util.Objects;

/**
 * Long-based entity identifier for sequential ID generation.
 * Suitable for auto-generated IDs similar to C++ vector indices.
 * 
 * @author hal.hildebrand
 */
public final class LongEntityID implements EntityID, Comparable<LongEntityID> {
    private final long id;
    
    public LongEntityID(long id) {
        this.id = id;
    }
    
    public long getValue() {
        return id;
    }
    
    @Override
    public String toDebugString() {
        return "Entity[" + id + "]";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LongEntityID that)) return false;
        return id == that.id;
    }
    
    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }
    
    @Override
    public int compareTo(LongEntityID other) {
        return Long.compare(this.id, other.id);
    }
    
    @Override
    public String toString() {
        return String.valueOf(id);
    }
}