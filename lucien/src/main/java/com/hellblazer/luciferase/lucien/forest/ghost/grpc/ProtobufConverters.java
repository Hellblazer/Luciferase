/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.tetree.CompactTetreeKey;
import com.hellblazer.luciferase.lucien.tetree.ExtendedTetreeKey;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.*;

import javax.vecmath.Point3f;
import java.util.UUID;

/**
 * Utility class for converting between domain objects and Protocol Buffer messages.
 * 
 * This class provides static methods for bidirectional conversion between
 * lucien domain objects and their protobuf representations.
 * 
 * @author Hal Hildebrand
 */
public final class ProtobufConverters {
    
    private ProtobufConverters() {
        // Utility class - no instances
    }
    
    /**
     * Converts a spatial key to protobuf format.
     * 
     * @param key the spatial key to convert
     * @return the protobuf representation
     * @throws IllegalArgumentException if the key type is unsupported
     */
    public static com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey spatialKeyToProtobuf(
            com.hellblazer.luciferase.lucien.SpatialKey<?> key) {
        
        var builder = com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey.newBuilder();
        
        if (key instanceof MortonKey mortonKey) {
            builder.setMorton(com.hellblazer.luciferase.lucien.forest.ghost.proto.MortonKey.newBuilder()
                .setMortonCode(mortonKey.getMortonCode())
                .setLevel(mortonKey.getLevel())
                .build());
        } else if (key instanceof TetreeKey<?> tetreeKey) {
            builder.setTetree(com.hellblazer.luciferase.lucien.forest.ghost.proto.TetreeKey.newBuilder()
                .setLow(tetreeKey.getLowBits())
                .setHigh(tetreeKey.getHighBits())
                .setLevel(tetreeKey.getLevel())
                .build());
        } else {
            throw new IllegalArgumentException("Unsupported spatial key type: " + key.getClass());
        }
        
        return builder.build();
    }
    
    /**
     * Converts a protobuf spatial key to domain object.
     * 
     * @param proto the protobuf spatial key
     * @return the domain spatial key
     * @throws IllegalArgumentException if the key type is not set or unsupported
     */
    public static com.hellblazer.luciferase.lucien.SpatialKey<?> spatialKeyFromProtobuf(
            com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey proto) {
        
        return switch (proto.getKeyTypeCase()) {
            case MORTON -> new MortonKey(proto.getMorton().getMortonCode(), (byte) proto.getMorton().getLevel());
            case TETREE -> {
                var tetree = proto.getTetree();
                yield createTetreeKey(tetree.getLow(), tetree.getHigh(), tetree.getLevel());
            }
            case KEYTYPE_NOT_SET -> throw new IllegalArgumentException("Spatial key type not set");
        };
    }
    
    /**
     * Creates a TetreeKey from protobuf values.
     * Automatically chooses between CompactTetreeKey and ExtendedTetreeKey based on level.
     * 
     * @param low the low bits
     * @param high the high bits
     * @param level the level
     * @return the appropriate TetreeKey implementation
     */
    private static TetreeKey<?> createTetreeKey(long low, long high, int level) {
        if (level <= 10) {
            // Use CompactTetreeKey for levels 0-10
            return new CompactTetreeKey((byte) level, low);
        } else {
            // Use ExtendedTetreeKey for levels 11-21
            return new ExtendedTetreeKey((byte) level, low, high);
        }
    }
    
    /**
     * Converts a Point3f to protobuf format.
     * 
     * @param point the point to convert
     * @return the protobuf representation
     */
    public static com.hellblazer.luciferase.lucien.forest.ghost.proto.Point3f point3fToProtobuf(javax.vecmath.Point3f point) {
        return com.hellblazer.luciferase.lucien.forest.ghost.proto.Point3f.newBuilder()
            .setX(point.x)
            .setY(point.y)
            .setZ(point.z)
            .build();
    }
    
    /**
     * Converts a protobuf Point3f to domain object.
     * 
     * @param proto the protobuf point
     * @return the domain point
     */
    public static javax.vecmath.Point3f point3fFromProtobuf(
            com.hellblazer.luciferase.lucien.forest.ghost.proto.Point3f proto) {
        return new javax.vecmath.Point3f(proto.getX(), proto.getY(), proto.getZ());
    }
    
    /**
     * Creates an EntityID from string representation.
     * 
     * @param entityIdString the string representation
     * @param entityIdClass the target EntityID class
     * @param <I> the EntityID type
     * @return the EntityID instance
     * @throws IllegalArgumentException if the EntityID class is unsupported
     */
    @SuppressWarnings("unchecked")
    public static <I extends EntityID> I createEntityId(String entityIdString, Class<I> entityIdClass) {
        if (entityIdClass == LongEntityID.class) {
            return (I) new LongEntityID(Long.parseLong(entityIdString));
        } else if (entityIdClass == UUIDEntityID.class) {
            return (I) new UUIDEntityID(UUID.fromString(entityIdString));
        } else {
            throw new IllegalArgumentException("Unsupported EntityID class: " + entityIdClass);
        }
    }
    
    /**
     * Converts an EntityID to string representation.
     * 
     * @param entityId the entity ID to convert
     * @return the string representation
     */
    public static String entityIdToString(EntityID entityId) {
        if (entityId instanceof LongEntityID longId) {
            return String.valueOf(longId.getValue());
        } else if (entityId instanceof UUIDEntityID uuidId) {
            return uuidId.getValue().toString();
        } else {
            // Fallback to toString for other implementations
            return entityId.toString();
        }
    }
}