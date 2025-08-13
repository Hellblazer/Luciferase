package com.hellblazer.luciferase.render.webgpu;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.render.voxel.core.EnhancedVoxelOctreeNode;
import com.hellblazer.luciferase.render.voxel.core.VoxelOctreeNode;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Adapter to convert Lucien Octree data to WebGPU voxel instances.
 * Bridges the spatial indexing system with the WebGPU renderer.
 */
public class VoxelOctreeAdapter {
    private static final Logger log = LoggerFactory.getLogger(VoxelOctreeAdapter.class);
    
    /**
     * Convert an Octree to a stream of voxel instances for rendering.
     * Uses the streaming API for memory efficiency and scalability.
     * 
     * @param octree the octree to convert
     * @param voxelSize the size of each voxel (not currently used, sizes come from octree levels)
     * @return a stream of voxel instances
     */
    public static <ID extends EntityID, Content> Stream<InstancedVoxelRenderer.VoxelInstance> 
            octreeToVoxelStream(Octree<ID, Content> octree, float voxelSize) {
        
        // Use the nodeStream() method to get all nodes efficiently
        return octree.nodeStream()
            .filter(node -> !node.entityIds().isEmpty()) // Only process occupied nodes
            .map(node -> {
                MortonKey key = node.sfcIndex();
                
                // Get spatial bounds for this node
                Spatial bounds = octree.getNodeBounds(key);
                
                // Create voxel instance
                InstancedVoxelRenderer.VoxelInstance voxel = new InstancedVoxelRenderer.VoxelInstance();
                
                // Extract center and size from bounds
                if (bounds instanceof Spatial.Cube cube) {
                    // Cube has originX, originY, originZ and extent
                    float cellSize = cube.extent();
                    
                    // Calculate center of the cube
                    float centerX = cube.originX() + cellSize / 2.0f;
                    float centerY = cube.originY() + cellSize / 2.0f;
                    float centerZ = cube.originZ() + cellSize / 2.0f;
                    
                    voxel.position = new Vector3f(centerX, centerY, centerZ);
                    voxel.scale = new Vector3f(cellSize, cellSize, cellSize);
                } else {
                    // Fallback for non-cube bounds (shouldn't happen with Octree)
                    log.warn("Non-cube bounds encountered: {}", bounds);
                    voxel.position = new Vector3f(0, 0, 0);
                    voxel.scale = new Vector3f(voxelSize, voxelSize, voxelSize);
                }
                
                // Color based on level (depth in octree)
                int level = key.getLevel();
                float hue = (level * 60) % 360; // Different hue per level
                voxel.color = hsvToRgb(hue, 0.7f, 0.9f);
                
                // Material ID based on entity count
                voxel.materialId = Math.min(node.entityIds().size(), 10);
                
                return voxel;
            });
    }
    
    /**
     * Convert an Octree to voxel instances for rendering.
     * Convenience method that collects the stream into a list.
     * 
     * @param octree the octree to convert
     * @param voxelSize the size of each voxel
     * @return a list of voxel instances
     */
    public static <ID extends EntityID, Content> List<InstancedVoxelRenderer.VoxelInstance> 
            octreeToVoxels(Octree<ID, Content> octree, float voxelSize) {
        var voxels = octreeToVoxelStream(octree, voxelSize)
            .toList();
        log.info("Created {} voxel instances from octree", voxels.size());
        return voxels;
    }
    
    /**
     * Convert a VoxelOctreeNode hierarchy to voxel instances.
     */
    public static List<InstancedVoxelRenderer.VoxelInstance> voxelNodeToInstances(
            VoxelOctreeNode root, float baseSize) {
        List<InstancedVoxelRenderer.VoxelInstance> voxels = new ArrayList<>();
        traverseVoxelNode(root, voxels, baseSize);
        return voxels;
    }
    
    private static void traverseVoxelNode(VoxelOctreeNode node, 
                                         List<InstancedVoxelRenderer.VoxelInstance> voxels,
                                         float size) {
        if (node == null) {
            return;
        }
        
        // TODO: Implement proper traversal once VoxelOctreeNode API is clarified
        // VoxelOctreeNode is a low-level bit-packed structure without high-level methods
        // The following methods don't exist: isOccupied(), getCenterX/Y/Z(), getChildren()
        // Need to either:
        // 1. Add wrapper methods to VoxelOctreeNode
        // 2. Use a different node type with proper API
        // 3. Work directly with the bit-packed data
        log.warn("VoxelOctreeNode traversal not yet implemented - needs API clarification");
    }
    
    /**
     * Convert dense voxel grid to instances.
     */
    public static List<InstancedVoxelRenderer.VoxelInstance> denseGridToInstances(
            int[][][] grid, float voxelSize, float spacing) {
        List<InstancedVoxelRenderer.VoxelInstance> voxels = new ArrayList<>();
        
        int sizeX = grid.length;
        int sizeY = grid[0].length;
        int sizeZ = grid[0][0].length;
        
        // Center offset
        float offsetX = -sizeX * spacing / 2.0f;
        float offsetY = -sizeY * spacing / 2.0f;
        float offsetZ = -sizeZ * spacing / 2.0f;
        
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    if (grid[x][y][z] != 0) {
                        InstancedVoxelRenderer.VoxelInstance voxel = 
                            new InstancedVoxelRenderer.VoxelInstance();
                        
                        voxel.position = new Vector3f(
                            offsetX + x * spacing,
                            offsetY + y * spacing,
                            offsetZ + z * spacing
                        );
                        voxel.scale = new Vector3f(voxelSize, voxelSize, voxelSize);
                        
                        // Color based on value
                        int value = grid[x][y][z];
                        voxel.color = getColorForValue(value);
                        voxel.materialId = value % 10;
                        
                        voxels.add(voxel);
                    }
                }
            }
        }
        
        log.info("Created {} voxel instances from {}x{}x{} grid", 
            voxels.size(), sizeX, sizeY, sizeZ);
        return voxels;
    }
    
    /**
     * Create voxels from a list of positions and colors.
     */
    public static List<InstancedVoxelRenderer.VoxelInstance> positionsToInstances(
            List<Vector3f> positions, List<Vector4f> colors, float voxelSize) {
        List<InstancedVoxelRenderer.VoxelInstance> voxels = new ArrayList<>();
        
        for (int i = 0; i < positions.size(); i++) {
            InstancedVoxelRenderer.VoxelInstance voxel = new InstancedVoxelRenderer.VoxelInstance();
            voxel.position = new Vector3f(positions.get(i));
            voxel.scale = new Vector3f(voxelSize, voxelSize, voxelSize);
            
            if (colors != null && i < colors.size()) {
                voxel.color = new Vector4f(colors.get(i));
            } else {
                // Default color
                voxel.color = new Vector4f(0.5f, 0.5f, 0.5f, 1.0f);
            }
            
            voxels.add(voxel);
        }
        
        return voxels;
    }
    
    /**
     * Create a test voxel pattern.
     */
    public static List<InstancedVoxelRenderer.VoxelInstance> createTestPattern(
            int size, float voxelSize, float spacing) {
        List<InstancedVoxelRenderer.VoxelInstance> voxels = new ArrayList<>();
        
        float offset = -size * spacing / 2.0f;
        
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    // Create a sphere pattern
                    float dx = x - size / 2.0f;
                    float dy = y - size / 2.0f;
                    float dz = z - size / 2.0f;
                    float distance = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                    
                    if (distance < size / 2.0f) {
                        InstancedVoxelRenderer.VoxelInstance voxel = 
                            new InstancedVoxelRenderer.VoxelInstance();
                        
                        voxel.position = new Vector3f(
                            offset + x * spacing,
                            offset + y * spacing,
                            offset + z * spacing
                        );
                        voxel.scale = new Vector3f(voxelSize, voxelSize, voxelSize);
                        
                        // Color gradient based on position
                        voxel.color = new Vector4f(
                            x / (float) size,
                            y / (float) size,
                            z / (float) size,
                            1.0f
                        );
                        
                        // Material varies with distance
                        voxel.materialId = (int) (distance / (size / 10.0f));
                        
                        voxels.add(voxel);
                    }
                }
            }
        }
        
        return voxels;
    }
    
    /**
     * Apply LOD (Level of Detail) to voxel instances based on distance.
     */
    public static List<InstancedVoxelRenderer.VoxelInstance> applyLOD(
            List<InstancedVoxelRenderer.VoxelInstance> voxels,
            Vector3f viewerPosition,
            float[] lodDistances) {
        
        List<InstancedVoxelRenderer.VoxelInstance> lodVoxels = new ArrayList<>();
        
        for (InstancedVoxelRenderer.VoxelInstance voxel : voxels) {
            float distance = voxel.position.distance(viewerPosition);
            
            // Determine LOD level
            int lodLevel = 0;
            for (int i = 0; i < lodDistances.length; i++) {
                if (distance > lodDistances[i]) {
                    lodLevel = i + 1;
                } else {
                    break;
                }
            }
            
            // Skip voxels beyond max LOD distance
            if (lodLevel >= lodDistances.length) {
                continue;
            }
            
            // Adjust voxel based on LOD
            if (lodLevel > 0) {
                // Combine nearby voxels at lower LOD
                // This is simplified - real implementation would merge voxels
                voxel.scale.mul(1 << lodLevel); // Double size for each LOD level
            }
            
            lodVoxels.add(voxel);
        }
        
        log.debug("LOD reduced voxel count from {} to {}", voxels.size(), lodVoxels.size());
        return lodVoxels;
    }
    
    /**
     * Helper to convert HSV to RGB.
     */
    private static Vector4f hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1 - Math.abs((h / 60) % 2 - 1));
        float m = v - c;
        
        float r, g, b;
        if (h < 60) {
            r = c; g = x; b = 0;
        } else if (h < 120) {
            r = x; g = c; b = 0;
        } else if (h < 180) {
            r = 0; g = c; b = x;
        } else if (h < 240) {
            r = 0; g = x; b = c;
        } else if (h < 300) {
            r = x; g = 0; b = c;
        } else {
            r = c; g = 0; b = x;
        }
        
        return new Vector4f(r + m, g + m, b + m, 1.0f);
    }
    
    /**
     * Get color for a voxel value.
     */
    private static Vector4f getColorForValue(int value) {
        // Simple color palette
        switch (value % 8) {
            case 0: return new Vector4f(1.0f, 0.0f, 0.0f, 1.0f); // Red
            case 1: return new Vector4f(0.0f, 1.0f, 0.0f, 1.0f); // Green
            case 2: return new Vector4f(0.0f, 0.0f, 1.0f, 1.0f); // Blue
            case 3: return new Vector4f(1.0f, 1.0f, 0.0f, 1.0f); // Yellow
            case 4: return new Vector4f(1.0f, 0.0f, 1.0f, 1.0f); // Magenta
            case 5: return new Vector4f(0.0f, 1.0f, 1.0f, 1.0f); // Cyan
            case 6: return new Vector4f(1.0f, 0.5f, 0.0f, 1.0f); // Orange
            case 7: return new Vector4f(0.5f, 0.0f, 1.0f, 1.0f); // Purple
            default: return new Vector4f(0.5f, 0.5f, 0.5f, 1.0f); // Gray
        }
    }
}