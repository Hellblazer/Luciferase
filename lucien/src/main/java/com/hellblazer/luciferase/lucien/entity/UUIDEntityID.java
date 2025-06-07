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
import java.util.UUID;

/**
 * UUID-based entity identifier for globally unique IDs.
 * Suitable for distributed systems or persistent storage.
 * 
 * @author hal.hildebrand
 */
public final class UUIDEntityID implements EntityID {
    private final UUID id;
    
    public UUIDEntityID() {
        this.id = UUID.randomUUID();
    }
    
    public UUIDEntityID(UUID id) {
        this.id = Objects.requireNonNull(id, "UUID cannot be null");
    }
    
    public UUIDEntityID(String uuid) {
        this.id = UUID.fromString(uuid);
    }
    
    public UUID getValue() {
        return id;
    }
    
    @Override
    public String toDebugString() {
        return "Entity[" + id.toString() + "]";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UUIDEntityID that)) return false;
        return id.equals(that.id);
    }
    
    @Override
    public int hashCode() {
        return id.hashCode();
    }
    
    @Override
    public String toString() {
        return id.toString();
    }
}