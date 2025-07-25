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

package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.ProtobufConverters;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.*;
import com.google.protobuf.Timestamp;
import com.google.protobuf.ByteString;

import javax.vecmath.Point3f;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a ghost element in a distributed spatial index.
 * 
 * A ghost element is a non-local element that has a neighbor relationship
 * with a local element. Ghost elements enable parallel computations by
 * providing access to neighboring data without explicit communication
 * during computation phases.
 * 
 * @param <Key> the type of spatial key used by the spatial index
 * @param <ID> the type of entity identifier
 * @param <Content> the type of content stored in entities
 * 
 * @author Hal Hildebrand
 */
public class GhostElement<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    private final Key spatialKey;
    private final ID entityId;
    private final Content content;
    private final Point3f position;
    private final int ownerRank;
    private final long globalTreeId;
    
    /**
     * Creates a new ghost element.
     * 
     * @param spatialKey the spatial key of this ghost element
     * @param entityId the entity identifier
     * @param content the entity content
     * @param position the position of the entity
     * @param ownerRank the rank of the process that owns this element
     * @param globalTreeId the global tree identifier
     */
    public GhostElement(Key spatialKey, ID entityId, Content content, 
                       Point3f position, int ownerRank, long globalTreeId) {
        this.spatialKey = Objects.requireNonNull(spatialKey, "Spatial key cannot be null");
        this.entityId = Objects.requireNonNull(entityId, "Entity ID cannot be null");
        this.content = content;
        this.position = Objects.requireNonNull(position, "Position cannot be null");
        this.ownerRank = ownerRank;
        this.globalTreeId = globalTreeId;
    }
    
    /**
     * Gets the spatial key of this ghost element.
     * 
     * @return the spatial key
     */
    public Key getSpatialKey() {
        return spatialKey;
    }
    
    /**
     * Gets the entity identifier.
     * 
     * @return the entity ID
     */
    public ID getEntityId() {
        return entityId;
    }
    
    /**
     * Gets the entity content.
     * 
     * @return the content, may be null
     */
    public Content getContent() {
        return content;
    }
    
    /**
     * Gets the position of the entity.
     * 
     * @return the position
     */
    public Point3f getPosition() {
        return new Point3f(position);
    }
    
    /**
     * Gets the rank of the process that owns this element.
     * 
     * @return the owner rank
     */
    public int getOwnerRank() {
        return ownerRank;
    }
    
    /**
     * Gets the global tree identifier.
     * 
     * @return the global tree ID
     */
    public long getGlobalTreeId() {
        return globalTreeId;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GhostElement)) return false;
        GhostElement<?, ?, ?> that = (GhostElement<?, ?, ?>) o;
        return ownerRank == that.ownerRank &&
               globalTreeId == that.globalTreeId &&
               spatialKey.equals(that.spatialKey) &&
               entityId.equals(that.entityId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(spatialKey, entityId, ownerRank, globalTreeId);
    }
    
    @Override
    public String toString() {
        return String.format("GhostElement[key=%s, id=%s, owner=%d, tree=%d]",
                           spatialKey, entityId, ownerRank, globalTreeId);
    }
    
    /**
     * Converts this ghost element to a Protocol Buffer message.
     * 
     * @param contentSerializer the serializer for the content type
     * @return the protobuf representation
     * @throws ContentSerializer.SerializationException if content serialization fails
     */
    public com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement toProtobuf(
            ContentSerializer<Content> contentSerializer) throws ContentSerializer.SerializationException {
        
        var builder = com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement.newBuilder()
            .setSpatialKey(ProtobufConverters.spatialKeyToProtobuf(spatialKey))
            .setEntityId(ProtobufConverters.entityIdToString(entityId))
            .setContent(contentSerializer.serialize(content))
            .setPosition(ProtobufConverters.point3fToProtobuf(position))
            .setOwnerRank(ownerRank)
            .setGlobalTreeId(globalTreeId)
            .setTimestamp(Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
                .setNanos((int) ((System.currentTimeMillis() % 1000) * 1_000_000))
                .build());
        
        return builder.build();
    }
    
    /**
     * Creates a ghost element from a Protocol Buffer message.
     * 
     * @param proto the protobuf message
     * @param contentSerializer the serializer for the content type
     * @param <K> the spatial key type
     * @param <I> the entity ID type
     * @param <C> the content type
     * @return the ghost element
     * @throws ContentSerializer.SerializationException if content deserialization fails
     */
    @SuppressWarnings("unchecked")
    public static <K extends SpatialKey<K>, I extends EntityID, C> GhostElement<K, I, C> fromProtobuf(
            com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement proto,
            ContentSerializer<C> contentSerializer,
            Class<I> entityIdClass) throws ContentSerializer.SerializationException {
        
        var spatialKey = (K) ProtobufConverters.spatialKeyFromProtobuf(proto.getSpatialKey());
        var entityId = ProtobufConverters.createEntityId(proto.getEntityId(), entityIdClass);
        var content = contentSerializer.deserialize(proto.getContent());
        var position = ProtobufConverters.point3fFromProtobuf(proto.getPosition());
        
        return new GhostElement<>(spatialKey, entityId, content, position, 
                                proto.getOwnerRank(), proto.getGlobalTreeId());
    }
}