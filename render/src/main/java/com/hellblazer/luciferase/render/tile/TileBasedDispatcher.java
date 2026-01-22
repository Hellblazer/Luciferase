package com.hellblazer.luciferase.render.tile;

import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.gpu.beam.BeamTree;
import com.hellblazer.luciferase.esvo.gpu.beam.BeamTreeBuilder;
import com.hellblazer.luciferase.esvo.gpu.beam.Ray;

import java.util.ArrayList;
import java.util.List;

/**
 * Dispatches tiles to optimal execution paths based on coherence.
 *
 * Execution Flow:
 * 1. Partition rays into tiles
 * 2. Measure per-tile coherence
 * 3. Build BeamTree for high-coherence tiles
 * 4. Execute batch kernel for high-coherence tiles
 * 5. Execute single-ray kernel for low-coherence tiles
 * 6. Merge results
 */
public class TileBasedDispatcher {

    /**
     * Functional interface for analyzing ray coherence.
     * Adapter for RayCoherenceAnalyzer to work with beam.Ray type.
     */
    @FunctionalInterface
    public interface CoherenceAnalyzer {
        double analyzeCoherence(Ray[] rays, DAGOctreeData dag);
    }

    /**
     * Functional interface for building BeamTree instances.
     * Allows dependency injection and testing.
     */
    @FunctionalInterface
    public interface BeamTreeFactory {
        BeamTree buildBeamTree(Ray[] rays, int[] rayIndices, DAGOctreeData dag, double coherenceScore);
    }

    private final TileConfiguration config;
    private final TileCoherenceMap coherenceMap;
    private final double coherenceThreshold;
    private final CoherenceAnalyzer coherenceAnalyzer;
    private final BeamTreeFactory beamTreeFactory;

    /**
     * Creates a dispatcher with the given configuration and threshold.
     *
     * @param config             tile configuration
     * @param coherenceThreshold threshold for high/low coherence classification (0.7 from phase 5a.2)
     * @param coherenceAnalyzer  analyzer for measuring ray coherence
     * @param beamTreeFactory    factory for building beam trees
     */
    public TileBasedDispatcher(TileConfiguration config, double coherenceThreshold,
                                CoherenceAnalyzer coherenceAnalyzer,
                                BeamTreeFactory beamTreeFactory) {
        if (config == null) {
            throw new IllegalArgumentException("Config cannot be null");
        }
        if (coherenceAnalyzer == null) {
            throw new IllegalArgumentException("Coherence analyzer cannot be null");
        }
        if (beamTreeFactory == null) {
            throw new IllegalArgumentException("Beam tree factory cannot be null");
        }
        if (coherenceThreshold < 0.0 || coherenceThreshold > 1.0) {
            throw new IllegalArgumentException("Coherence threshold must be in [0, 1]");
        }

        this.config = config;
        this.coherenceThreshold = coherenceThreshold;
        this.coherenceAnalyzer = coherenceAnalyzer;
        this.beamTreeFactory = beamTreeFactory;
        this.coherenceMap = new TileCoherenceMap(config, 0.5); // Default coherence 0.5
    }

    /**
     * Dispatch entire frame using adaptive tile-based execution.
     *
     * @param rays        global ray array (frameWidth × frameHeight)
     * @param frameWidth  frame width in pixels
     * @param frameHeight frame height in pixels
     * @param dag         DAG data for traversal
     * @param executor    kernel executor (batch and single-ray)
     * @return execution metrics
     */
    public DispatchMetrics dispatchFrame(Ray[] rays, int frameWidth, int frameHeight,
                                          DAGOctreeData dag, KernelExecutor executor) {
        long startTime = System.nanoTime();

        // Phase 1: Partition into tiles
        var tiles = partitionIntoTiles(rays, frameWidth, frameHeight);

        // Phase 2: Measure coherence per tile
        updateTileCoherence(tiles, rays, frameWidth, frameHeight, dag);

        // Phase 3: Execute tiles
        int batchCount = 0;
        int singleRayCount = 0;
        double totalCoherence = 0.0;

        for (var tile : tiles) {
            if (tile.isHighCoherence(coherenceThreshold)) {
                batchCount++;
            } else {
                singleRayCount++;
            }
            totalCoherence += tile.coherenceScore();

            executeTile(tile, rays, frameWidth, frameHeight, executor);
        }

        long endTime = System.nanoTime();

        // Calculate metrics
        int totalTiles = tiles.size();
        double avgCoherence = totalCoherence / totalTiles;
        double batchRatio = (double) batchCount / totalTiles;
        long dispatchTime = endTime - startTime;

        return new DispatchMetrics(totalTiles, batchCount, singleRayCount, batchRatio, avgCoherence, dispatchTime);
    }

    /**
     * Partition rays into tiles based on configuration.
     * Rays are in scanline order: rayIndex = y * frameWidth + x
     *
     * Package-private for testing.
     *
     * @param rays        global ray array
     * @param frameWidth  frame width in pixels
     * @param frameHeight frame height in pixels
     * @return list of tiles with ray indices assigned
     */
    List<Tile> partitionIntoTiles(Ray[] rays, int frameWidth, int frameHeight) {
        var tiles = new ArrayList<Tile>();

        for (int tileY = 0; tileY < config.tilesY(); tileY++) {
            for (int tileX = 0; tileX < config.tilesX(); tileX++) {
                // Calculate pixel bounds of this tile
                int pixelX = tileX * config.tileWidth();
                int pixelY = tileY * config.tileHeight();

                // Calculate actual width/height (may be clipped at edges)
                int actualWidth = Math.min(config.tileWidth(), frameWidth - pixelX);
                int actualHeight = Math.min(config.tileHeight(), frameHeight - pixelY);

                // Ray start index in scanline order
                int rayStartIndex = pixelY * frameWidth + pixelX;

                // Ray count for this tile
                int rayCount = actualWidth * actualHeight;

                // Create tile with default coherence (will be updated later)
                var tile = new Tile(tileX, tileY, rayStartIndex, rayCount, 0.5);
                tiles.add(tile);
            }
        }

        return tiles;
    }

    /**
     * Update coherence scores for all tiles by analyzing ray patterns.
     *
     * @param tiles       list of tiles to update
     * @param rays        global ray array
     * @param frameWidth  frame width in pixels
     * @param frameHeight frame height in pixels
     * @param dag         DAG structure for traversal
     */
    private void updateTileCoherence(List<Tile> tiles, Ray[] rays, int frameWidth, int frameHeight, DAGOctreeData dag) {
        for (int i = 0; i < tiles.size(); i++) {
            var tile = tiles.get(i);

            // Extract rays for this tile
            var tileRays = extractTileRays(tile, rays, frameWidth, frameHeight);

            // Analyze coherence
            double coherenceScore = coherenceAnalyzer.analyzeCoherence(tileRays, dag);

            // Update tile with measured coherence
            var updatedTile = new Tile(tile.tileX(), tile.tileY(), tile.rayStartIndex(),
                                        tile.rayCount(), coherenceScore);
            tiles.set(i, updatedTile);

            // Update coherence map
            coherenceMap.updateTileCoherence(tile.tileX(), tile.tileY(), coherenceScore);
        }
    }

    /**
     * Extract ray array for a specific tile.
     * Handles tiles that span multiple scanlines in the frame.
     *
     * @param tile        tile to extract rays for
     * @param rays        global ray array
     * @param frameWidth  frame width in pixels
     * @param frameHeight frame height in pixels
     * @return array of rays belonging to this tile
     */
    private Ray[] extractTileRays(Tile tile, Ray[] rays, int frameWidth, int frameHeight) {
        var tileRays = new Ray[tile.rayCount()];
        int destIndex = 0;

        // Calculate pixel bounds of this tile
        int pixelX = tile.tileX() * config.tileWidth();
        int pixelY = tile.tileY() * config.tileHeight();

        // Calculate actual dimensions (may be clipped at edges)
        int actualWidth = Math.min(config.tileWidth(), frameWidth - pixelX);
        int actualHeight = tile.rayCount() / actualWidth;

        // Extract rays scanline by scanline
        for (int dy = 0; dy < actualHeight; dy++) {
            int scanlineStart = (pixelY + dy) * frameWidth + pixelX;
            System.arraycopy(rays, scanlineStart, tileRays, destIndex, actualWidth);
            destIndex += actualWidth;
        }

        return tileRays;
    }

    /**
     * Execute a single tile using appropriate kernel based on coherence.
     *
     * @param tile        tile to execute
     * @param rays        global ray array
     * @param frameWidth  frame width in pixels
     * @param frameHeight frame height in pixels
     * @param executor    kernel executor
     */
    private void executeTile(Tile tile, Ray[] rays, int frameWidth, int frameHeight, KernelExecutor executor) {
        // Extract ray indices for this tile
        var rayIndices = extractTileRayIndices(tile, frameWidth, frameHeight);

        if (tile.isHighCoherence(coherenceThreshold)) {
            // High coherence: use batch kernel
            // Build BeamTree for organizing rays (note: beamTree is built but not used in this phase)
            var beamTree = beamTreeFactory.buildBeamTree(rays, rayIndices, null, tile.coherenceScore());

            // Execute batch kernel with 4 rays per work item (SIMD factor from spec)
            executor.executeBatch(rays, rayIndices, 4);
        } else {
            // Low coherence: use single-ray kernel
            executor.executeSingleRay(rays, rayIndices);
        }
    }

    /**
     * Extract ray indices for a specific tile.
     * Returns indices in the global ray array.
     *
     * @param tile        tile to extract indices for
     * @param frameWidth  frame width in pixels
     * @param frameHeight frame height in pixels
     * @return array of ray indices in global ray array
     */
    private int[] extractTileRayIndices(Tile tile, int frameWidth, int frameHeight) {
        var indices = new int[tile.rayCount()];
        int destIndex = 0;

        // Calculate pixel bounds of this tile
        int pixelX = tile.tileX() * config.tileWidth();
        int pixelY = tile.tileY() * config.tileHeight();

        // Calculate actual dimensions (may be clipped at edges)
        int actualWidth = Math.min(config.tileWidth(), frameWidth - pixelX);
        int actualHeight = tile.rayCount() / actualWidth;

        // Extract indices scanline by scanline
        for (int dy = 0; dy < actualHeight; dy++) {
            int scanlineStart = (pixelY + dy) * frameWidth + pixelX;
            for (int dx = 0; dx < actualWidth; dx++) {
                indices[destIndex++] = scanlineStart + dx;
            }
        }

        return indices;
    }
}
