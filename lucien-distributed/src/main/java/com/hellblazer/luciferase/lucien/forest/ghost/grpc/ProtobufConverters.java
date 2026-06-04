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

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.SpatialKeySerde;
import com.hellblazer.luciferase.lucien.SpatialKeySerdeRegistry;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
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
     * Converts a spatial key to its protobuf envelope (Luciferase-546).
     * <p>
     * Dispatches via {@link SpatialKeySerdeRegistry#forKey(SpatialKey)} —
     * no {@code instanceof} switch. To add support for a new SpatialKey type,
     * register a {@link SpatialKeySerde} for it; no edits to this method
     * are required.
     *
     * @param key the spatial key to convert
     * @return the protobuf envelope (type_id + opaque payload)
     * @throws IllegalArgumentException if no serde is registered for the key's class
     */
    public static com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey spatialKeyToProtobuf(
            com.hellblazer.luciferase.lucien.SpatialKey<?> key) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        SpatialKeySerde serde = SpatialKeySerdeRegistry.forKey((com.hellblazer.luciferase.lucien.SpatialKey) key);
        if (serde == null) {
            throw new IllegalArgumentException("No SpatialKeySerde registered for key class: " + key.getClass());
        }
        @SuppressWarnings("unchecked")
        var payload = serde.serialize(key);
        return com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey.newBuilder()
            .setTypeId(serde.typeId())
            .setPayload(ByteString.copyFrom(payload))
            .build();
    }

    /**
     * Converts a protobuf spatial key envelope back to a domain object
     * (Luciferase-546). Dispatches via
     * {@link SpatialKeySerdeRegistry#forTypeId(String)}.
     *
     * @param proto the protobuf envelope
     * @return the deserialised spatial key
     * @throws IllegalArgumentException if no serde is registered for the
     *                                  envelope's {@code type_id}
     */
    public static com.hellblazer.luciferase.lucien.SpatialKey<?> spatialKeyFromProtobuf(
            com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey proto) {
        var typeId = proto.getTypeId();
        if (typeId.isEmpty()) {
            throw new IllegalArgumentException("SpatialKey protobuf has no type_id set");
        }
        var serde = SpatialKeySerdeRegistry.forTypeId(typeId);
        if (serde == null) {
            throw new IllegalArgumentException(
                "No SpatialKeySerde registered for type_id '" + typeId + "'");
        }
        return serde.deserialize(proto.getPayload().toByteArray());
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
            // Whole-batch configuration error, not a per-element data error (Luciferase-m2k3u): the same
            // entityIdClass applies to every element, so an unsupported type fails ALL of them. The previous
            // IllegalArgumentException was swallowed by the per-element catch in GhostServiceClient /
            // GhostExchangeServiceImpl, silently dropping the entire batch (RDR-004 D3 / 7pias class). Throw a
            // dedicated unchecked type those catches re-throw, so the misconfiguration surfaces loudly.
            throw new UnsupportedEntityIdTypeException(entityIdClass);
        }
    }

    /**
     * Signals an unsupported configured {@link EntityID} type in ghost (de)serialization (Luciferase-m2k3u). This is
     * a configuration/programming error affecting the whole batch — per-element catches must re-throw it rather than
     * log-and-continue (which would silently drop every element). Extend {@link #createEntityId} (or migrate to an
     * SPI registry) to add a type.
     */
    public static final class UnsupportedEntityIdTypeException extends RuntimeException {
        public UnsupportedEntityIdTypeException(Class<?> entityIdClass) {
            super("Unsupported EntityID class for ghost serialization: " + entityIdClass
                  + " (supported: LongEntityID, UUIDEntityID)");
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

    /**
     * Converts a ghost element to its Protocol Buffer representation.
     *
     * @param element the ghost element to convert
     * @param contentSerializer the serializer for the content type
     * @param nowMillis the wall-clock time in milliseconds to stamp the element (caller captures once)
     * @param <K> the spatial key type
     * @param <I> the entity ID type
     * @param <C> the content type
     * @return the protobuf representation
     * @throws ContentSerializer.SerializationException if content serialization fails
     */
    public static <K extends SpatialKey<K>, I extends EntityID, C>
            com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement ghostElementToProtobuf(
            GhostElement<K, I, C> element,
            ContentSerializer<C> contentSerializer,
            long nowMillis) throws ContentSerializer.SerializationException {

        var builder = com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement.newBuilder()
            .setSpatialKey(spatialKeyToProtobuf(element.getSpatialKey()))
            .setEntityId(entityIdToString(element.getEntityId()))
            .setContent(ByteString.copyFrom(contentSerializer.serialize(element.getContent())))
            .setPosition(point3fToProtobuf(element.getPosition()))
            .setOwnerRank(element.getOwnerRank())
            .setGlobalTreeId(element.getGlobalTreeId())
            .setTimestamp(Timestamp.newBuilder()
                .setSeconds(nowMillis / 1000)
                .setNanos((int) ((nowMillis % 1000) * 1_000_000))
                .build());

        return builder.build();
    }

    /**
     * Creates a ghost element from its Protocol Buffer representation.
     *
     * @param proto the protobuf message
     * @param contentSerializer the serializer for the content type
     * @param entityIdClass the class for entity IDs
     * @param <K> the spatial key type
     * @param <I> the entity ID type
     * @param <C> the content type
     * @return the ghost element
     * @throws ContentSerializer.SerializationException if content deserialization fails
     */
    @SuppressWarnings("unchecked")
    public static <K extends SpatialKey<K>, I extends EntityID, C> GhostElement<K, I, C> ghostElementFromProtobuf(
            com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement proto,
            ContentSerializer<C> contentSerializer,
            Class<I> entityIdClass) throws ContentSerializer.SerializationException {

        var spatialKey = (K) spatialKeyFromProtobuf(proto.getSpatialKey());
        var entityId = createEntityId(proto.getEntityId(), entityIdClass);
        var content = contentSerializer.deserialize(proto.getContent().toByteArray());
        var position = point3fFromProtobuf(proto.getPosition());

        return new GhostElement<>(spatialKey, entityId, content, position,
                                  proto.getOwnerRank(), proto.getGlobalTreeId());
    }

    /**
     * Converts all ghost elements in a layer to a Protocol Buffer batch.
     *
     * @param layer the ghost layer
     * @param sourceRank the rank of this process
     * @param sourceTreeId the tree ID of this process
     * @param contentSerializer the serializer for content
     * @param nowMillis the wall-clock time in milliseconds to stamp the batch and all its elements (caller captures once)
     * @param <K> the spatial key type
     * @param <I> the entity ID type
     * @param <C> the content type
     * @return the protobuf ghost batch
     * @throws ContentSerializer.SerializationException if serialization fails
     */
    public static <K extends SpatialKey<K>, I extends EntityID, C> GhostBatch ghostLayerToProtobufBatch(
            GhostLayer<K, I, C> layer, int sourceRank, long sourceTreeId,
            ContentSerializer<C> contentSerializer,
            long nowMillis) throws ContentSerializer.SerializationException {

        var batch = GhostBatch.newBuilder()
            .setSourceRank(sourceRank)
            .setSourceTreeId(sourceTreeId)
            .setTimestamp(Timestamp.newBuilder()
                .setSeconds(nowMillis / 1000)
                .setNanos((int) ((nowMillis % 1000) * 1_000_000))
                .build());

        for (var element : layer.getAllGhostElements()) {
            batch.addElements(ghostElementToProtobuf(element, contentSerializer, nowMillis));
        }

        return batch.build();
    }
}