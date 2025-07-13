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

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry for managing content serializers for different content types.
 * 
 * This registry allows the ghost system to handle multiple content types
 * by mapping content type identifiers to their respective serializers.
 * 
 * @author Hal Hildebrand
 */
public class ContentSerializerRegistry {
    
    private static final ContentSerializerRegistry INSTANCE = new ContentSerializerRegistry();
    
    private final Map<String, ContentSerializer<?>> serializers;
    
    private ContentSerializerRegistry() {
        this.serializers = new ConcurrentHashMap<>();
        
        // Register default serializers
        register(ContentSerializer.NULL_SERIALIZER);
        register(ContentSerializer.STRING_SERIALIZER);
    }
    
    /**
     * Gets the singleton instance of the registry.
     * 
     * @return the content serializer registry
     */
    public static ContentSerializerRegistry getInstance() {
        return INSTANCE;
    }
    
    /**
     * Registers a content serializer for a specific content type.
     * 
     * @param serializer the serializer to register
     * @param <Content> the content type
     * @throws IllegalArgumentException if a serializer for this type is already registered
     */
    public <Content> void register(ContentSerializer<Content> serializer) {
        Objects.requireNonNull(serializer, "Serializer cannot be null");
        
        var contentType = serializer.getContentType();
        Objects.requireNonNull(contentType, "Content type cannot be null");
        
        var existing = serializers.putIfAbsent(contentType, serializer);
        if (existing != null && existing != serializer) {
            throw new IllegalArgumentException(
                "Serializer already registered for content type: " + contentType);
        }
    }
    
    /**
     * Gets a content serializer for the specified content type.
     * 
     * @param contentType the content type identifier
     * @param <Content> the content type
     * @return the serializer for the content type
     * @throws IllegalArgumentException if no serializer is registered for the type
     */
    @SuppressWarnings("unchecked")
    public <Content> ContentSerializer<Content> getSerializer(String contentType) {
        Objects.requireNonNull(contentType, "Content type cannot be null");
        
        var serializer = serializers.get(contentType);
        if (serializer == null) {
            throw new IllegalArgumentException(
                "No serializer registered for content type: " + contentType);
        }
        
        return (ContentSerializer<Content>) serializer;
    }
    
    /**
     * Gets a content serializer for the specified content class.
     * 
     * @param contentClass the content class
     * @param <Content> the content type
     * @return the serializer for the content type
     * @throws IllegalArgumentException if no serializer is registered for the type
     */
    public <Content> ContentSerializer<Content> getSerializer(Class<Content> contentClass) {
        Objects.requireNonNull(contentClass, "Content class cannot be null");
        
        // Try exact class name first
        var contentType = contentClass.getName();
        var serializer = serializers.get(contentType);
        
        if (serializer != null) {
            return getSerializer(contentType);
        }
        
        // Try simple class name
        contentType = contentClass.getSimpleName().toLowerCase();
        serializer = serializers.get(contentType);
        
        if (serializer != null) {
            return getSerializer(contentType);
        }
        
        // Special cases for common types
        if (contentClass == String.class) {
            return getSerializer("string");
        }
        
        if (contentClass == Void.class || contentClass == void.class) {
            return getSerializer("void");
        }
        
        throw new IllegalArgumentException(
            "No serializer registered for content class: " + contentClass.getName());
    }
    
    /**
     * Checks if a serializer is registered for the specified content type.
     * 
     * @param contentType the content type identifier
     * @return true if a serializer is registered, false otherwise
     */
    public boolean hasSerializer(String contentType) {
        return serializers.containsKey(contentType);
    }
    
    /**
     * Checks if a serializer is registered for the specified content class.
     * 
     * @param contentClass the content class
     * @return true if a serializer is registered, false otherwise
     */
    public boolean hasSerializer(Class<?> contentClass) {
        try {
            getSerializer(contentClass);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
    
    /**
     * Removes a serializer for the specified content type.
     * 
     * @param contentType the content type identifier
     * @return the removed serializer, or null if none was registered
     */
    public ContentSerializer<?> unregister(String contentType) {
        return serializers.remove(contentType);
    }
    
    /**
     * Gets all registered content types.
     * 
     * @return a set of registered content type identifiers
     */
    public java.util.Set<String> getRegisteredTypes() {
        return java.util.Set.copyOf(serializers.keySet());
    }
}