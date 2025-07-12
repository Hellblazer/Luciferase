package com.hellblazer.luciferase.lucien.forest;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable metadata container for spatial index trees.
 * Stores essential information about a tree including its name, type, 
 * creation time, and custom properties.
 * 
 * @author hal.hildebrand
 */
public class TreeMetadata {
    
    /**
     * Enumeration of supported spatial index tree types
     */
    public enum TreeType {
        OCTREE,
        TETREE,
        PRISM
    }
    
    private final String name;
    private final Instant creationTimestamp;
    private final TreeType treeType;
    private final Map<String, Object> properties;
    
    private TreeMetadata(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "Tree name cannot be null");
        this.creationTimestamp = Objects.requireNonNull(builder.creationTimestamp, "Creation timestamp cannot be null");
        this.treeType = Objects.requireNonNull(builder.treeType, "Tree type cannot be null");
        this.properties = Collections.unmodifiableMap(new HashMap<>(builder.properties));
    }
    
    /**
     * Returns the name/label of the tree
     * 
     * @return the tree name
     */
    public String getName() {
        return name;
    }
    
    /**
     * Returns the timestamp when this tree was created
     * 
     * @return the creation timestamp
     */
    public Instant getCreationTimestamp() {
        return creationTimestamp;
    }
    
    /**
     * Returns the type of spatial index tree
     * 
     * @return the tree type
     */
    public TreeType getTreeType() {
        return treeType;
    }
    
    /**
     * Returns an immutable view of the custom properties map
     * 
     * @return unmodifiable map of custom properties
     */
    public Map<String, Object> getProperties() {
        return properties;
    }
    
    /**
     * Gets a specific property value
     * 
     * @param key the property key
     * @return the property value, or null if not present
     */
    public Object getProperty(String key) {
        return properties.get(key);
    }
    
    /**
     * Gets a specific property value with type casting
     * 
     * @param <T> the expected type
     * @param key the property key
     * @param type the expected class type
     * @return the property value cast to the expected type, or null if not present or wrong type
     */
    @SuppressWarnings("unchecked")
    public <T> T getProperty(String key, Class<T> type) {
        var value = properties.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }
    
    /**
     * Creates a new builder for TreeMetadata
     * 
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates a builder initialized with values from an existing TreeMetadata
     * 
     * @param metadata the metadata to copy values from
     * @return a new builder instance with copied values
     */
    public static Builder builder(TreeMetadata metadata) {
        return new Builder()
            .name(metadata.name)
            .creationTimestamp(metadata.creationTimestamp)
            .treeType(metadata.treeType)
            .properties(metadata.properties);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TreeMetadata that = (TreeMetadata) o;
        return Objects.equals(name, that.name) &&
               Objects.equals(creationTimestamp, that.creationTimestamp) &&
               treeType == that.treeType &&
               Objects.equals(properties, that.properties);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(name, creationTimestamp, treeType, properties);
    }
    
    @Override
    public String toString() {
        return String.format("TreeMetadata{name='%s', type=%s, created=%s, properties=%s}",
                           name, treeType, creationTimestamp, properties);
    }
    
    /**
     * Builder for creating TreeMetadata instances
     */
    public static class Builder {
        private String name;
        private Instant creationTimestamp = Instant.now();
        private TreeType treeType;
        private final Map<String, Object> properties = new HashMap<>();
        
        private Builder() {
        }
        
        /**
         * Sets the tree name/label
         * 
         * @param name the tree name
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        /**
         * Sets the creation timestamp
         * 
         * @param timestamp the creation timestamp
         * @return this builder
         */
        public Builder creationTimestamp(Instant timestamp) {
            this.creationTimestamp = timestamp;
            return this;
        }
        
        /**
         * Sets the tree type
         * 
         * @param type the tree type
         * @return this builder
         */
        public Builder treeType(TreeType type) {
            this.treeType = type;
            return this;
        }
        
        /**
         * Adds a custom property
         * 
         * @param key the property key
         * @param value the property value
         * @return this builder
         */
        public Builder property(String key, Object value) {
            this.properties.put(key, value);
            return this;
        }
        
        /**
         * Sets all custom properties, replacing any existing ones
         * 
         * @param properties the properties map
         * @return this builder
         */
        public Builder properties(Map<String, Object> properties) {
            this.properties.clear();
            this.properties.putAll(properties);
            return this;
        }
        
        /**
         * Builds the TreeMetadata instance
         * 
         * @return the constructed TreeMetadata
         * @throws NullPointerException if required fields are null
         */
        public TreeMetadata build() {
            return new TreeMetadata(this);
        }
    }
}