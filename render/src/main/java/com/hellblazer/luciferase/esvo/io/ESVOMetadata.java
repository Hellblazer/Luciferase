package com.hellblazer.luciferase.esvo.io;

import javax.vecmath.Vector3f;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Metadata for ESVO files
 */
public class ESVOMetadata implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private long creationTime;
    private int octreeDepth;
    private Vector3f boundingBoxMin;
    private Vector3f boundingBoxMax;
    private int nodeCount;
    private String compressionType;
    private Map<String, String> customProperties;
    
    public ESVOMetadata() {
        this.customProperties = new HashMap<>();
    }
    
    public long getCreationTime() {
        return creationTime;
    }
    
    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }
    
    public int getOctreeDepth() {
        return octreeDepth;
    }
    
    public void setOctreeDepth(int octreeDepth) {
        this.octreeDepth = octreeDepth;
    }
    
    public Vector3f getBoundingBoxMin() {
        return boundingBoxMin;
    }
    
    public Vector3f getBoundingBoxMax() {
        return boundingBoxMax;
    }
    
    public void setBoundingBox(Vector3f min, Vector3f max) {
        this.boundingBoxMin = new Vector3f(min);
        this.boundingBoxMax = new Vector3f(max);
    }
    
    public int getNodeCount() {
        return nodeCount;
    }
    
    public void setNodeCount(int nodeCount) {
        this.nodeCount = nodeCount;
    }
    
    public String getCompressionType() {
        return compressionType;
    }
    
    public void setCompressionType(String compressionType) {
        this.compressionType = compressionType;
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
}