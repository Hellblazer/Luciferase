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

import com.google.protobuf.ByteString;

/**
 * Interface for serializing and deserializing content objects to/from Protocol Buffers.
 * 
 * This allows the ghost system to handle arbitrary content types by delegating
 * serialization to type-specific implementations. Implementations should handle
 * null content appropriately.
 * 
 * @param <Content> the type of content to serialize/deserialize
 * 
 * @author Hal Hildebrand
 */
public interface ContentSerializer<Content> {
    
    /**
     * Serializes content to bytes for Protocol Buffer transport.
     * 
     * @param content the content to serialize (may be null)
     * @return serialized bytes, or empty ByteString for null content
     * @throws SerializationException if serialization fails
     */
    ByteString serialize(Content content) throws SerializationException;
    
    /**
     * Deserializes content from bytes.
     * 
     * @param bytes the serialized bytes
     * @return the deserialized content, or null if bytes are empty
     * @throws SerializationException if deserialization fails
     */
    Content deserialize(ByteString bytes) throws SerializationException;
    
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
        public ByteString serialize(Void content) {
            return ByteString.EMPTY;
        }
        
        @Override
        public Void deserialize(ByteString bytes) {
            return null;
        }
        
        @Override
        public String getContentType() {
            return "void";
        }
    };
    
    /**
     * String content serializer for simple text content.
     */
    ContentSerializer<String> STRING_SERIALIZER = new ContentSerializer<>() {
        @Override
        public ByteString serialize(String content) {
            return content != null ? ByteString.copyFromUtf8(content) : ByteString.EMPTY;
        }
        
        @Override
        public String deserialize(ByteString bytes) {
            return bytes.isEmpty() ? null : bytes.toStringUtf8();
        }
        
        @Override
        public String getContentType() {
            return "string";
        }
    };
}