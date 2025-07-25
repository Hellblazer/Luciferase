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
package com.hellblazer.luciferase.lucien.occlusion;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import javax.vecmath.Point3f;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Hierarchical Z-Buffer for efficient occlusion culling.
 * Uses a pyramid of depth buffers at different resolutions to quickly test
 * occlusion of bounding volumes.
 *
 * @author hal.hildebrand
 */
public class HierarchicalZBuffer {
    
    private final int width;
    private final int height;
    private final int levels;
    private final float[][] zBuffers;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    // Camera parameters
    private float[] viewMatrix = new float[16];
    private float[] projectionMatrix = new float[16];
    private float[] viewProjectionMatrix = new float[16];
    private float nearPlane = 0.1f;
    private float farPlane = 1000.0f;
    
    /**
     * Creates a hierarchical Z-buffer with specified dimensions
     * 
     * @param width Base resolution width
     * @param height Base resolution height
     * @param levels Number of hierarchy levels
     */
    public HierarchicalZBuffer(int width, int height, int levels) {
        this.width = width;
        this.height = height;
        this.levels = levels;
        
        // Allocate buffers for each level
        this.zBuffers = new float[levels][];
        
        int w = width;
        int h = height;
        for (int level = 0; level < levels; level++) {
            zBuffers[level] = new float[w * h];
            // Initialize to far plane
            for (int i = 0; i < w * h; i++) {
                zBuffers[level][i] = 1.0f;
            }
            
            // Halve resolution for next level
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
    }
    
    /**
     * Updates camera matrices
     */
    public void updateCamera(float[] viewMatrix, float[] projectionMatrix, 
                           float nearPlane, float farPlane) {
        lock.writeLock().lock();
        try {
            System.arraycopy(viewMatrix, 0, this.viewMatrix, 0, 16);
            System.arraycopy(projectionMatrix, 0, this.projectionMatrix, 0, 16);
            this.nearPlane = nearPlane;
            this.farPlane = farPlane;
            
            // Compute view-projection matrix
            multiplyMatrices(projectionMatrix, viewMatrix, viewProjectionMatrix);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Clears all Z-buffer levels
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            for (int level = 0; level < levels; level++) {
                for (int i = 0; i < zBuffers[level].length; i++) {
                    zBuffers[level][i] = 1.0f;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Tests if a bounding volume is occluded
     * 
     * @param bounds The bounds to test
     * @return true if the bounds are occluded
     */
    public boolean isOccluded(EntityBounds bounds) {
        lock.readLock().lock();
        try {
            // Transform bounds to screen space
            ScreenSpaceBounds screenBounds = projectBounds(bounds);
            if (screenBounds == null) {
                // Behind camera or outside frustum
                return false;
            }
            
            // Choose appropriate level based on screen size
            int level = chooseLevelForBounds(screenBounds);
            
            // Test occlusion at chosen level
            return testOcclusionAtLevel(screenBounds, level);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Updates the Z-buffer with a rendered occluder
     * 
     * @param bounds The bounds of the occluder
     */
    public void renderOccluder(EntityBounds bounds) {
        lock.writeLock().lock();
        try {
            // Transform bounds to screen space
            ScreenSpaceBounds screenBounds = projectBounds(bounds);
            if (screenBounds == null) {
                return;
            }
            
            // Rasterize at base level
            rasterizeBounds(screenBounds, 0);
            
            // Update hierarchy
            updateHierarchy();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Builds the Z-pyramid from the base level
     */
    public void updateHierarchy() {
        for (int level = 1; level < levels; level++) {
            int srcWidth = getWidthAtLevel(level - 1);
            int srcHeight = getHeightAtLevel(level - 1);
            int dstWidth = getWidthAtLevel(level);
            int dstHeight = getHeightAtLevel(level);
            
            float[] srcBuffer = zBuffers[level - 1];
            float[] dstBuffer = zBuffers[level];
            
            // Downsample using max depth (furthest)
            for (int y = 0; y < dstHeight; y++) {
                for (int x = 0; x < dstWidth; x++) {
                    int dstIdx = y * dstWidth + x;
                    
                    // Sample 2x2 region from source
                    int srcX = x * 2;
                    int srcY = y * 2;
                    
                    float maxDepth = 0.0f;
                    for (int dy = 0; dy < 2 && srcY + dy < srcHeight; dy++) {
                        for (int dx = 0; dx < 2 && srcX + dx < srcWidth; dx++) {
                            int srcIdx = (srcY + dy) * srcWidth + (srcX + dx);
                            maxDepth = Math.max(maxDepth, srcBuffer[srcIdx]);
                        }
                    }
                    
                    dstBuffer[dstIdx] = maxDepth;
                }
            }
        }
    }
    
    /**
     * Gets buffer width at specified level
     */
    public int getWidthAtLevel(int level) {
        int w = width;
        for (int i = 0; i < level; i++) {
            w = Math.max(1, w / 2);
        }
        return w;
    }
    
    /**
     * Gets buffer height at specified level
     */
    public int getHeightAtLevel(int level) {
        int h = height;
        for (int i = 0; i < level; i++) {
            h = Math.max(1, h / 2);
        }
        return h;
    }
    
    /**
     * Projects bounds to screen space
     */
    private ScreenSpaceBounds projectBounds(EntityBounds bounds) {
        // Get corners of bounding box
        Point3f min = bounds.getMin();
        Point3f max = bounds.getMax();
        
        Point3f[] corners = new Point3f[8];
        corners[0] = new Point3f(min.x, min.y, min.z);
        corners[1] = new Point3f(max.x, min.y, min.z);
        corners[2] = new Point3f(min.x, max.y, min.z);
        corners[3] = new Point3f(max.x, max.y, min.z);
        corners[4] = new Point3f(min.x, min.y, max.z);
        corners[5] = new Point3f(max.x, min.y, max.z);
        corners[6] = new Point3f(min.x, max.y, max.z);
        corners[7] = new Point3f(max.x, max.y, max.z);
        
        // Project each corner
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        
        boolean anyInFront = false;
        for (Point3f corner : corners) {
            float[] projected = projectPoint(corner);
            if (projected[2] > 0) {
                anyInFront = true;
                minX = Math.min(minX, projected[0]);
                minY = Math.min(minY, projected[1]);
                minZ = Math.min(minZ, projected[2]);
                maxX = Math.max(maxX, projected[0]);
                maxY = Math.max(maxY, projected[1]);
                maxZ = Math.max(maxZ, projected[2]);
            }
        }
        
        if (!anyInFront) {
            return null; // All corners behind camera
        }
        
        // Convert to pixel coordinates
        int pixelMinX = Math.max(0, (int)(minX * width));
        int pixelMinY = Math.max(0, (int)(minY * height));
        int pixelMaxX = Math.min(width - 1, (int)(maxX * width));
        int pixelMaxY = Math.min(height - 1, (int)(maxY * height));
        
        return new ScreenSpaceBounds(pixelMinX, pixelMinY, pixelMaxX, pixelMaxY, minZ);
    }
    
    /**
     * Projects a 3D point to normalized device coordinates
     */
    private float[] projectPoint(Point3f point) {
        float[] homogeneous = new float[4];
        homogeneous[0] = point.x;
        homogeneous[1] = point.y;
        homogeneous[2] = point.z;
        homogeneous[3] = 1.0f;
        
        float[] transformed = new float[4];
        multiplyMatrixVector(viewProjectionMatrix, homogeneous, transformed);
        
        // Perspective divide
        if (transformed[3] != 0) {
            transformed[0] /= transformed[3];
            transformed[1] /= transformed[3];
            transformed[2] /= transformed[3];
        }
        
        // Convert to [0,1] range
        float[] result = new float[3];
        result[0] = (transformed[0] + 1.0f) * 0.5f;
        result[1] = (transformed[1] + 1.0f) * 0.5f;
        result[2] = transformed[2]; // Keep Z in NDC space
        
        return result;
    }
    
    /**
     * Chooses appropriate hierarchy level for bounds
     */
    private int chooseLevelForBounds(ScreenSpaceBounds bounds) {
        int width = bounds.maxX - bounds.minX + 1;
        int height = bounds.maxY - bounds.minY + 1;
        int size = Math.max(width, height);
        
        // Use higher levels for smaller objects
        int level = 0;
        int threshold = Math.min(this.width, this.height) / 4;
        
        while (level < levels - 1 && size < threshold) {
            level++;
            threshold /= 2;
        }
        
        return level;
    }
    
    /**
     * Tests occlusion at specified level
     */
    private boolean testOcclusionAtLevel(ScreenSpaceBounds bounds, int level) {
        int levelWidth = getWidthAtLevel(level);
        int levelHeight = getHeightAtLevel(level);
        float[] buffer = zBuffers[level];
        
        // Scale bounds to level resolution
        int minX = bounds.minX >> level;
        int minY = bounds.minY >> level;
        int maxX = Math.min(levelWidth - 1, bounds.maxX >> level);
        int maxY = Math.min(levelHeight - 1, bounds.maxY >> level);
        
        // Test if any pixel passes depth test
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                int idx = y * levelWidth + x;
                if (bounds.nearZ < buffer[idx]) {
                    return false; // Not occluded
                }
            }
        }
        
        return true; // Fully occluded
    }
    
    /**
     * Rasterizes bounds into Z-buffer
     */
    private void rasterizeBounds(ScreenSpaceBounds bounds, int level) {
        int levelWidth = getWidthAtLevel(level);
        int levelHeight = getHeightAtLevel(level);
        float[] buffer = zBuffers[level];
        
        // Scale bounds to level resolution
        int minX = Math.max(0, bounds.minX >> level);
        int minY = Math.max(0, bounds.minY >> level);
        int maxX = Math.min(levelWidth - 1, bounds.maxX >> level);
        int maxY = Math.min(levelHeight - 1, bounds.maxY >> level);
        
        // Update depth values
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                int idx = y * levelWidth + x;
                buffer[idx] = Math.min(buffer[idx], bounds.nearZ);
            }
        }
    }
    
    /**
     * Matrix multiplication: result = a * b
     */
    private void multiplyMatrices(float[] a, float[] b, float[] result) {
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[i * 4 + k] * b[k * 4 + j];
                }
                result[i * 4 + j] = sum;
            }
        }
    }
    
    /**
     * Matrix-vector multiplication
     */
    private void multiplyMatrixVector(float[] matrix, float[] vector, float[] result) {
        for (int i = 0; i < 4; i++) {
            float sum = 0;
            for (int j = 0; j < 4; j++) {
                sum += matrix[i * 4 + j] * vector[j];
            }
            result[i] = sum;
        }
    }
    
    /**
     * Screen space bounds representation
     */
    private static class ScreenSpaceBounds {
        final int minX, minY, maxX, maxY;
        final float nearZ;
        
        ScreenSpaceBounds(int minX, int minY, int maxX, int maxY, float nearZ) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.nearZ = nearZ;
        }
    }
}