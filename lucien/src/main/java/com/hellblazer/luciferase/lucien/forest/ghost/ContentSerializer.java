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

import java.nio.charset.StandardCharsets;

/**
 * Interface for serializing and deserializing content objects to/from raw bytes.
 *
 * This allows the ghost system to handle arbitrary content types by delegating
 * serialization to type-specific implementations. Implementations should handle
 * null content appropriately.
 *
 * <p>The byte-array contract (rather than a transport-specific container such as protobuf's
 * {@code ByteString}) keeps lucien core free of any transport/serialization-framework dependency;
 * the gRPC transport in {@code lucien-distributed} bridges {@code byte[]} to its wire types at the
 * proto boundary. This matches the {@code byte[]} convention already used by {@code SpatialKeySerde}.
 *
 * @param <Content> the type of content to serialize/deserialize
 *
 * @author Hal Hildebrand
 */
public interface ContentSerializer<Content> {

    /**
     * Serializes content to bytes for transport.
     *
     * @param content the content to serialize (may be null)
     * @return serialized bytes, or an empty array for null content
     * @throws SerializationException if serialization fails
     */
    byte[] serialize(Content content) throws SerializationException;

    /**
     * Deserializes content from bytes.
     *
     * @param bytes the serialized bytes; may be {@code null} or empty, which implementations
     *              treat as absent content (returning {@code null})
     * @return the deserialized content, or {@code null} for null/empty input
     * @throws SerializationException if deserialization fails
     */
    Content deserialize(byte[] bytes) throws SerializationException;

    /**
     * Gets the content type identifier for this serializer.
     * Used for type checking and registry lookup.
     *
     * @return a unique identifier for the content type
     */
    String getContentType();

    /**
     * Exception thrown when serialization or deserialization fails.
     */
    class SerializationException extends Exception {
        public SerializationException(String message) {
            super(message);
        }

        public SerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * No-op serializer for null or void content types.
     */
    ContentSerializer<Void> NULL_SERIALIZER = new ContentSerializer<>() {
        @Override
        public byte[] serialize(Void content) {
            return new byte[0];
        }

        @Override
        public Void deserialize(byte[] bytes) {
            return null;
        }

        @Override
        public String getContentType() {
            return "void";
        }
    };

    /**
     * String content serializer for simple text content.
     *
     * <p>Wire format: the raw UTF-8 bytes of the string, with no framing. The serialized bytes ARE the
     * content (the gRPC ghost transport copies them verbatim into the protobuf {@code content} field),
     * so consumers — including non-Java peers — read them as the literal payload.
     *
     * <p><strong>Known limitation (Luciferase-7wzml.53):</strong> {@code null} and the empty string
     * {@code ""} are indistinguishable on the wire — both serialize to an empty byte array and
     * deserialize to {@code null}. Disambiguating them would require a framing byte prepended to
     * <em>every</em> payload, changing the on-wire representation of all ghost string content and
     * breaking raw-bytes consumers (and cross-version peers). For a P3 with no production path that
     * relies on round-tripping {@code ""} distinctly from {@code null}, that wire-format change is not
     * justified; callers needing the distinction must use a richer content type. Round-trip of all
     * non-empty strings is exact.
     */
    ContentSerializer<String> STRING_SERIALIZER = new ContentSerializer<>() {
        @Override
        public byte[] serialize(String content) {
            return content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String deserialize(byte[] bytes) {
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public String getContentType() {
            return "string";
        }
    };
}
