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
     * <p>As of Luciferase-7wzml.53, implementations that use the 1-byte presence-flag wire
     * format (e.g. {@link #STRING_SERIALIZER}) accept {@code null} bytes as equivalent to
     * an absent payload and return {@code null}. The {@code bytes} parameter may therefore
     * be {@code null} (treated as absent content) or a non-empty array whose first byte is
     * the presence flag (0 = null value, 1 = value follows).
     *
     * @param bytes the serialized bytes, or {@code null} to indicate an absent payload;
     *              implementations using the presence-flag format also treat an empty array
     *              or a leading {@code 0x00} flag as a null-valued payload
     * @return the deserialized content, or {@code null} when the payload encodes a null value
     *         (presence flag = 0, null bytes, or empty array)
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
     * <p>Wire format: a 1-byte presence flag (0 = null, 1 = present) followed by the UTF-8 bytes of the
     * string content. This disambiguates {@code null} from the empty string {@code ""}, which would both
     * serialize to an empty byte array under a flag-free scheme (Luciferase-7wzml.53).
     */
    ContentSerializer<String> STRING_SERIALIZER = new ContentSerializer<>() {
        @Override
        public byte[] serialize(String content) {
            if (content == null) {
                return new byte[] { 0 };
            }
            var utf8 = content.getBytes(StandardCharsets.UTF_8);
            var result = new byte[1 + utf8.length];
            result[0] = 1; // presence flag
            System.arraycopy(utf8, 0, result, 1, utf8.length);
            return result;
        }

        @Override
        public String deserialize(byte[] bytes) {
            if (bytes == null || bytes.length == 0 || bytes[0] == 0) {
                return null;
            }
            return new String(bytes, 1, bytes.length - 1, StandardCharsets.UTF_8);
        }

        @Override
        public String getContentType() {
            return "string";
        }
    };
}
