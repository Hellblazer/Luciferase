/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.forest.TreeConnectivityManager.ConnectivityType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * A specialized forest implementation that creates a uniform grid of spatial index trees.
 * This is useful for applications that need regular spatial partitioning across a large area.
 * 
 * @param <ID> The entity ID type
 * @param <Content> The entity content type
 */
public class GridForest<Key extends SpatialKey<Key>, ID extends EntityID, Content> extends Forest<Key, ID, Content> {
    private static final Logger log = LoggerFactory.getLogger(GridForest.class);
    
    private final int gridX;
    private final int gridY;
    private final int gridZ;
    private final Vector3f cellSize;
    private final Point3f origin;
    private final TreeType treeType;
    
    /**
     * Tree type for the grid
     */
    public enum TreeType {
        OCTREE,
        TETREE
    }
    
    /**
     * Private constructor - use static factory methods
     */
    private GridForest(ForestConfig config, Point3f origin, Vector3f totalSize,
                      int gridX, int gridY, int gridZ, TreeType treeType) {
        super(config);
        this.origin = new Point3f(origin);
        this.gridX = gridX;
        this.gridY = gridY;
        this.gridZ = gridZ;
        this.treeType = treeType;
        
        // Calculate cell size
        this.cellSize = new Vector3f(
            totalSize.x / gridX,
            totalSize.y / gridY,
            totalSize.z / gridZ
        );
        
        initializeGrid();
    }
    
    /**
     * Create a uniform grid forest with Octrees
     * 
     * @param origin The origin point of the entire grid
     * @param totalSize The total size of the grid
     * @param gridX Number of trees in X direction
     * @param gridY Number of trees in Y direction  
     * @param gridZ Number of trees in Z direction
     * @return A new grid forest
     */
    public static <Key extends SpatialKey<Key>, ID extends EntityID, Content> GridForest<Key, ID, Content> createOctreeGrid(
            Point3f origin, Vector3f totalSize, int gridX, int gridY, int gridZ) {
        return createGrid(origin, totalSize, gridX, gridY, gridZ, TreeType.OCTREE, 
                         ForestConfig.defaultConfig());
    }
    
    /**
     * Create a uniform grid forest with Tetrees
     * 
     * @param origin The origin point of the entire grid
     * @param totalSize The total size of the grid
     * @param gridX Number of trees in X direction
     * @param gridY Number of trees in Y direction
     * @param gridZ Number of trees in Z direction
     * @return A new grid forest
     */
    public static <Key extends SpatialKey<Key>, ID extends EntityID, Content> GridForest<Key, ID, Content> createTetreeGrid(
            Point3f origin, Vector3f totalSize, int gridX, int gridY, int gridZ) {
        return createGrid(origin, totalSize, gridX, gridY, gridZ, TreeType.TETREE,
                         ForestConfig.defaultConfig());
    }
    
    /**
     * Create a uniform grid forest with specified configuration
     * 
     * @param origin The origin point of the entire grid
     * @param totalSize The total size of the grid
     * @param gridX Number of trees in X direction
     * @param gridY Number of trees in Y direction
     * @param gridZ Number of trees in Z direction
     * @param treeType Type of trees to use
     * @param config Forest configuration
     * @return A new grid forest
     */
    public static <Key extends SpatialKey<Key>, ID extends EntityID, Content> GridForest<Key, ID, Content> createGrid(
            Point3f origin, Vector3f totalSize, int gridX, int gridY, int gridZ,
            TreeType treeType, ForestConfig config) {
        
        if (gridX <= 0 || gridY <= 0 || gridZ <= 0) {
            throw new IllegalArgumentException("Grid dimensions must be positive");
        }
        
        if (totalSize.x <= 0 || totalSize.y <= 0 || totalSize.z <= 0) {
            throw new IllegalArgumentException("Total size must be positive in all dimensions");
        }
        
        log.info("Creating {} grid forest: {}x{}x{} cells, total size: {}", 
                treeType, gridX, gridY, gridZ, totalSize);
        
        return new GridForest<>(config, origin, totalSize, gridX, gridY, gridZ, treeType);
    }
    
    /**
     * Initialize the grid by creating all trees
     */
    private void initializeGrid() {
        var totalTrees = gridX * gridY * gridZ;
        log.debug("Initializing grid with {} trees", totalTrees);
        
        // Create trees in a regular grid pattern
        for (int z = 0; z < gridZ; z++) {
            for (int y = 0; y < gridY; y++) {
                for (int x = 0; x < gridX; x++) {
                    createTreeAt(x, y, z);
                }
            }
        }
        
        // Establish connectivity between adjacent trees if ghost zones are enabled
        if (getConfig().isGhostZonesEnabled()) {
            establishGridConnectivity();
        }
        
        log.info("Grid forest initialized with {} trees", totalTrees);
    }
    
    /**
     * Create a tree at the specified grid position
     */
    private void createTreeAt(int x, int y, int z) {
        // Calculate tree origin
        var treeOrigin = new Point3f(
            origin.x + x * cellSize.x,
            origin.y + y * cellSize.y,
            origin.z + z * cellSize.z
        );
        
        // Create bounds for this tree
        var minPoint = new Point3f(treeOrigin);
        var maxPoint = new Point3f(
            treeOrigin.x + cellSize.x,
            treeOrigin.y + cellSize.y,
            treeOrigin.z + cellSize.z
        );
        var bounds = new EntityBounds(minPoint, maxPoint);
        
        // Create the spatial index based on tree type
        // Note: Both Octree and Tetree require an EntityIDGenerator, not origin/size
        // This needs to be handled by the Forest base class which should provide
        // the ID generator. For now, we'll throw an exception.
        throw new UnsupportedOperationException(
            "GridForest needs to be updated to work with current Octree/Tetree constructors. " +
            "They require EntityIDGenerator, not origin/size parameters."
        );
        
        // TODO: Complete implementation when Octree/Tetree constructor issue is resolved
        // The following code is unreachable due to the throw statement above
        /*
        // Create tree metadata
        var metadata = TreeMetadata.builder()
            .name(String.format("Grid_%d_%d_%d", x, y, z))
            .treeType(treeType == TreeType.OCTREE ? 
                     TreeMetadata.TreeType.OCTREE : TreeMetadata.TreeType.TETREE)
            .property("gridX", x)
            .property("gridY", y)
            .property("gridZ", z)
            .build();
        */
        
        // TODO: Cannot create tree without proper constructor support
        // The current Octree/Tetree constructors require EntityIDGenerator
        // log.trace("Would create tree at grid position ({},{},{})", x, y, z);
    }
    
    /**
     * Establish connectivity between adjacent trees in the grid
     */
    private void establishGridConnectivity() {
        log.debug("Establishing grid connectivity");
        
        var trees = getAllTrees();
        // Connectivity manager would need to be added to Forest base class
        // For now, establish connections directly
        
        // For each tree, connect to its face neighbors
        for (var tree : trees) {
            var metadataObj = tree.getMetadata("metadata");
            if (!(metadataObj instanceof TreeMetadata metadata)) continue;
            
            var x = metadata.getProperty("gridX", Integer.class);
            var y = metadata.getProperty("gridY", Integer.class);
            var z = metadata.getProperty("gridZ", Integer.class);
            
            if (x == null || y == null || z == null) continue;
            
            // Connect to neighbors in each direction
            // Connect to neighbors in each direction
            // TODO: Implement connectivity when TreeConnectivityManager is available in Forest
        }
    }
    
    /**
     * Connect to a neighbor at the specified grid position if it exists
     */
    private void connectToNeighbor(TreeNode<Key, ID, Content> tree,
                                  int neighborX, int neighborY, int neighborZ) {
        // Check if neighbor is within grid bounds
        if (neighborX < 0 || neighborX >= gridX ||
            neighborY < 0 || neighborY >= gridY ||
            neighborZ < 0 || neighborZ >= gridZ) {
            return;
        }
        
        // Find neighbor tree
        var neighborName = String.format("Grid_%d_%d_%d", neighborX, neighborY, neighborZ);
        for (var neighbor : getAllTrees()) {
            var metadataObj = neighbor.getMetadata("metadata");
            if (metadataObj instanceof TreeMetadata metadata && neighborName.equals(metadata.getName())) {
                // TODO: Add connection when TreeConnectivityManager is available
                log.trace("Would connect trees {} and {}", tree.getTreeId(), neighbor.getTreeId());
                break;
            }
        }
    }
    
    /**
     * Calculate the shared boundary between two adjacent trees
     */
    private EntityBounds calculateSharedBoundary(TreeNode<Key, ID, Content> tree1,
                                               TreeNode<Key, ID, Content> tree2) {
        var bounds1 = tree1.getGlobalBounds();
        var bounds2 = tree2.getGlobalBounds();
        
        if (bounds1 == null || bounds2 == null) {
            return null;
        }
        
        // Calculate intersection
        var minX = Math.max(bounds1.getMinX(), bounds2.getMinX());
        var minY = Math.max(bounds1.getMinY(), bounds2.getMinY());
        var minZ = Math.max(bounds1.getMinZ(), bounds2.getMinZ());
        var maxX = Math.min(bounds1.getMaxX(), bounds2.getMaxX());
        var maxY = Math.min(bounds1.getMaxY(), bounds2.getMaxY());
        var maxZ = Math.min(bounds1.getMaxZ(), bounds2.getMaxZ());
        
        // Check if there's a valid intersection
        if (minX <= maxX && minY <= maxY && minZ <= maxZ) {
            return new EntityBounds(
                new Point3f(minX, minY, minZ),
                new Point3f(maxX, maxY, maxZ)
            );
        }
        
        return null;
    }
    
    /**
     * Get the tree at a specific grid position
     * 
     * @param x Grid X coordinate
     * @param y Grid Y coordinate
     * @param z Grid Z coordinate
     * @return The tree at that position, or null if not found
     */
    public TreeNode<Key, ID, Content> getTreeAt(int x, int y, int z) {
        if (x < 0 || x >= gridX || y < 0 || y >= gridY || z < 0 || z >= gridZ) {
            return null;
        }
        
        var targetName = String.format("Grid_%d_%d_%d", x, y, z);
        for (var tree : getAllTrees()) {
            var metadataObj = tree.getMetadata("metadata");
            if (metadataObj instanceof TreeMetadata metadata && targetName.equals(metadata.getName())) {
                return tree;
            }
        }
        
        return null;
    }
    
    /**
     * Get the grid coordinates for a spatial position
     * 
     * @param position The position to query
     * @return Grid coordinates as [x, y, z], or null if outside grid
     */
    public int[] getGridCoordinates(Point3f position) {
        // Calculate relative position
        var relX = position.x - origin.x;
        var relY = position.y - origin.y;
        var relZ = position.z - origin.z;
        
        // Check if position is within grid bounds
        if (relX < 0 || relY < 0 || relZ < 0 ||
            relX >= gridX * cellSize.x ||
            relY >= gridY * cellSize.y ||
            relZ >= gridZ * cellSize.z) {
            return null;
        }
        
        // Calculate grid coordinates
        var x = (int)(relX / cellSize.x);
        var y = (int)(relY / cellSize.y);
        var z = (int)(relZ / cellSize.z);
        
        // Clamp to valid range (in case of floating point edge cases)
        x = Math.min(x, gridX - 1);
        y = Math.min(y, gridY - 1);
        z = Math.min(z, gridZ - 1);
        
        return new int[]{x, y, z};
    }
    
    // Getters
    public int getGridX() { return gridX; }
    public int getGridY() { return gridY; }
    public int getGridZ() { return gridZ; }
    public Vector3f getCellSize() { return new Vector3f(cellSize); }
    public Point3f getOrigin() { return new Point3f(origin); }
    public TreeType getTreeType() { return treeType; }
}