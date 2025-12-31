package com.hellblazer.luciferase.portal.web.dto;

import com.hellblazer.luciferase.portal.web.service.SpatialIndexService.IndexType;

/**
 * Request to create a new spatial index.
 *
 * @param indexType          Type of spatial index (OCTREE, TETREE, SFC)
 * @param maxDepth           Maximum tree depth (default: 10)
 * @param maxEntitiesPerNode Maximum entities per node before subdivision (default: 10)
 */
public record CreateIndexRequest(
        IndexType indexType,
        Byte maxDepth,
        Integer maxEntitiesPerNode
) {
    public CreateIndexRequest {
        if (indexType == null) {
            throw new IllegalArgumentException("indexType is required");
        }
    }
}
