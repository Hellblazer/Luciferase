/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvt.io;

import javax.vecmath.Vector3f;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Metadata for ESVT files.
 *
 * <p>Stores auxiliary information about the ESVT data structure that is
 * not required for rendering but useful for management and debugging.
 *
 * @author hal.hildebrand
 */
public class ESVTMetadata implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private long creationTime;
    private int tetreeDepth;
    private int rootType;
    private Vector3f boundingBoxMin;
    private Vector3f boundingBoxMax;
    private int nodeCount;
    private int leafCount;
    private int internalCount;
    private int contourCount;
    private int farPointerCount;
    private String compressionType;
    private String sourceFile;
    private int gridResolution;
    private long buildTimeMs;
    private Map<String, String> customProperties;

    public ESVTMetadata() {
        this.creationTime = Instant.now().toEpochMilli();
        this.customProperties = new HashMap<>();
    }

    // === Getters and Setters ===

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public int getTetreeDepth() {
        return tetreeDepth;
    }

    public void setTetreeDepth(int tetreeDepth) {
        this.tetreeDepth = tetreeDepth;
    }

    public int getRootType() {
        return rootType;
    }

    public void setRootType(int rootType) {
        this.rootType = rootType;
    }

    public Vector3f getBoundingBoxMin() {
        return boundingBoxMin;
    }

    public Vector3f getBoundingBoxMax() {
        return boundingBoxMax;
    }

    public void setBoundingBox(Vector3f min, Vector3f max) {
        this.boundingBoxMin = min != null ? new Vector3f(min) : null;
        this.boundingBoxMax = max != null ? new Vector3f(max) : null;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public void setNodeCount(int nodeCount) {
        this.nodeCount = nodeCount;
    }

    public int getLeafCount() {
        return leafCount;
    }

    public void setLeafCount(int leafCount) {
        this.leafCount = leafCount;
    }

    public int getInternalCount() {
        return internalCount;
    }

    public void setInternalCount(int internalCount) {
        this.internalCount = internalCount;
    }

    public int getContourCount() {
        return contourCount;
    }

    public void setContourCount(int contourCount) {
        this.contourCount = contourCount;
    }

    public int getFarPointerCount() {
        return farPointerCount;
    }

    public void setFarPointerCount(int farPointerCount) {
        this.farPointerCount = farPointerCount;
    }

    public String getCompressionType() {
        return compressionType;
    }

    public void setCompressionType(String compressionType) {
        this.compressionType = compressionType;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public int getGridResolution() {
        return gridResolution;
    }

    public void setGridResolution(int gridResolution) {
        this.gridResolution = gridResolution;
    }

    public long getBuildTimeMs() {
        return buildTimeMs;
    }

    public void setBuildTimeMs(long buildTimeMs) {
        this.buildTimeMs = buildTimeMs;
    }

    public void addCustomProperty(String key, String value) {
        customProperties.put(key, value);
    }

    public String getCustomProperty(String key) {
        return customProperties.get(key);
    }

    public Map<String, String> getCustomProperties() {
        return new HashMap<>(customProperties);
    }

    /**
     * Calculate the memory footprint of the ESVT data.
     *
     * @return Size in bytes
     */
    public long calculateDataSize() {
        long size = (long) nodeCount * 8; // 8 bytes per node
        size += (long) contourCount * 4;
        size += (long) farPointerCount * 4;
        return size;
    }

    /**
     * Get a human-readable size string.
     */
    public String getDataSizeString() {
        long bytes = calculateDataSize();
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    @Override
    public String toString() {
        return String.format("ESVTMetadata[nodes=%d, leaves=%d, depth=%d, rootType=%d, size=%s, grid=%d]",
            nodeCount, leafCount, tetreeDepth, rootType, getDataSizeString(), gridResolution);
    }
}
