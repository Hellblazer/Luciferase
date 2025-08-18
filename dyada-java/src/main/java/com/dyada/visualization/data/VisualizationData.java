package com.dyada.visualization.data;

import java.time.Instant;
import java.util.Map;

/**
 * Base record for all visualization data types.
 * Contains common metadata and rendering information.
 */
public record VisualizationData(
    String id,
    Instant timestamp,
    int dimensions,
    Bounds bounds,
    Map<String, Object> metadata
) {
    
    public VisualizationData {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Visualization ID cannot be null or blank");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (dimensions < 2 || dimensions > 3) {
            throw new IllegalArgumentException("Dimensions must be 2 or 3, got: " + dimensions);
        }
        if (bounds == null) {
            throw new IllegalArgumentException("Bounds cannot be null");
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }
    
    /**
     * Creates a new VisualizationData with updated metadata.
     * 
     * @param newMetadata additional metadata to merge
     * @return new instance with merged metadata
     */
    public VisualizationData withMetadata(Map<String, Object> newMetadata) {
        var mergedMetadata = new java.util.HashMap<>(this.metadata);
        mergedMetadata.putAll(newMetadata);
        return new VisualizationData(id, timestamp, dimensions, bounds, mergedMetadata);
    }
    
    /**
     * Gets metadata value with type casting.
     * 
     * @param key metadata key
     * @param type expected value type
     * @return typed metadata value or null
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, Class<T> type) {
        var value = metadata.get(key);
        return type.isInstance(value) ? (T) value : null;
    }
}