/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.geometry;

import javax.vecmath.Vector3f;
import java.util.*;

/**
 * Example usage of HilbertCurveComparator for various spatial data processing tasks.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class HilbertCurveExample {
    
    public static void main(String[] args) {
        // Example 1: Basic sorting
        basicSortingExample();
        
        // Example 2: Spatial indexing
        spatialIndexingExample();
        
        // Example 3: Cache-efficient traversal
        cacheEfficientTraversalExample();
        
        // Example 4: Spatial clustering
        spatialClusteringExample();
        
        // Example 5: Custom bounding box
        customBoundingBoxExample();
    }
    
    /**
     * Example 1: Basic sorting of 3D points using Hilbert curve order
     */
    private static void basicSortingExample() {
        System.out.println("=== Basic Sorting Example ===");
        
        // Create random points
        List<Vector3f> points = new ArrayList<>();
        Random rand = new Random(42);
        for (int i = 0; i < 10; i++) {
            points.add(new Vector3f(rand.nextFloat(), rand.nextFloat(), rand.nextFloat()));
        }
        
        // Sort using Hilbert curve
        HilbertCurveComparator comparator = new HilbertCurveComparator();
        Collections.sort(points, comparator);
        
        // Print sorted points and their Hilbert indices
        System.out.println("Sorted points:");
        for (Vector3f p : points) {
            long index = comparator.computeHilbertIndex(p);
            System.out.printf("Point (%.3f, %.3f, %.3f) -> Hilbert index: %d%n",
                p.x, p.y, p.z, index);
        }
        System.out.println();
    }
    
    /**
     * Example 2: Using Hilbert curve for spatial indexing
     */
    private static void spatialIndexingExample() {
        System.out.println("=== Spatial Indexing Example ===");
        
        // Create a spatial index using TreeMap with Hilbert curve ordering
        HilbertCurveComparator comparator = new HilbertCurveComparator();
        TreeMap<Long, List<String>> spatialIndex = new TreeMap<>();
        
        // Index some objects by their positions
        class SpatialObject {
            String name;
            Vector3f position;
            
            SpatialObject(String name, float x, float y, float z) {
                this.name = name;
                this.position = new Vector3f(x, y, z);
            }
        }
        
        SpatialObject[] objects = {
            new SpatialObject("Object A", 0.1f, 0.1f, 0.1f),
            new SpatialObject("Object B", 0.2f, 0.2f, 0.2f),
            new SpatialObject("Object C", 0.8f, 0.8f, 0.8f),
            new SpatialObject("Object D", 0.15f, 0.15f, 0.15f),
            new SpatialObject("Object E", 0.9f, 0.9f, 0.9f)
        };
        
        // Build index
        for (SpatialObject obj : objects) {
            long hilbertIndex = comparator.computeHilbertIndex(obj.position);
            spatialIndex.computeIfAbsent(hilbertIndex, k -> new ArrayList<>()).add(obj.name);
        }
        
        // Query range
        System.out.println("Spatial index contents:");
        for (Map.Entry<Long, List<String>> entry : spatialIndex.entrySet()) {
            System.out.printf("Hilbert index %d: %s%n", entry.getKey(), entry.getValue());
        }
        
        // Find objects in a range
        Vector3f queryMin = new Vector3f(0.0f, 0.0f, 0.0f);
        Vector3f queryMax = new Vector3f(0.3f, 0.3f, 0.3f);
        long minIndex = comparator.computeHilbertIndex(queryMin);
        long maxIndex = comparator.computeHilbertIndex(queryMax);
        
        System.out.printf("%nObjects in range [%d, %d]:%n", minIndex, maxIndex);
        NavigableMap<Long, List<String>> range = spatialIndex.subMap(minIndex, true, maxIndex, true);
        for (Map.Entry<Long, List<String>> entry : range.entrySet()) {
            System.out.printf("  %s%n", entry.getValue());
        }
        System.out.println();
    }
    
    /**
     * Example 3: Cache-efficient traversal of spatial data
     */
    private static void cacheEfficientTraversalExample() {
        System.out.println("=== Cache-Efficient Traversal Example ===");
        
        // Create a grid of points
        List<Vector3f> grid = new ArrayList<>();
        int size = 8;
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    grid.add(new Vector3f(
                        x / (float)(size - 1),
                        y / (float)(size - 1),
                        z / (float)(size - 1)
                    ));
                }
            }
        }
        
        // Sort by Hilbert curve for cache-efficient access
        HilbertCurveComparator comparator = new HilbertCurveComparator();
        Collections.sort(grid, comparator);
        
        // Simulate processing with locality measurement
        float totalDistance = 0;
        for (int i = 1; i < grid.size(); i++) {
            Vector3f prev = grid.get(i - 1);
            Vector3f curr = grid.get(i);
            totalDistance += HilbertCurveComparator.distance(prev, curr);
        }
        
        float avgJump = totalDistance / (grid.size() - 1);
        System.out.printf("Average distance between consecutive points: %.4f%n", avgJump);
        System.out.printf("This indicates good spatial locality for cache efficiency%n");
        System.out.println();
    }
    
    /**
     * Example 4: Spatial clustering using Hilbert curve
     */
    private static void spatialClusteringExample() {
        System.out.println("=== Spatial Clustering Example ===");
        
        // Generate clustered data
        List<Vector3f> points = new ArrayList<>();
        Random rand = new Random(42);
        
        // Generate 3 clusters
        float[][] clusterCenters = {
            {0.2f, 0.2f, 0.2f},
            {0.8f, 0.8f, 0.2f},
            {0.5f, 0.5f, 0.8f}
        };
        
        for (int c = 0; c < clusterCenters.length; c++) {
            for (int i = 0; i < 20; i++) {
                points.add(new Vector3f(
                    clusterCenters[c][0] + (rand.nextFloat() - 0.5f) * 0.1f,
                    clusterCenters[c][1] + (rand.nextFloat() - 0.5f) * 0.1f,
                    clusterCenters[c][2] + (rand.nextFloat() - 0.5f) * 0.1f
                ));
            }
        }
        
        // Sort by Hilbert curve
        HilbertCurveComparator comparator = new HilbertCurveComparator();
        Collections.sort(points, comparator);
        
        // Simple clustering: group consecutive points
        List<List<Vector3f>> clusters = new ArrayList<>();
        List<Vector3f> currentCluster = new ArrayList<>();
        float threshold = 0.2f; // Distance threshold for same cluster
        
        currentCluster.add(points.get(0));
        for (int i = 1; i < points.size(); i++) {
            if (HilbertCurveComparator.distance(points.get(i-1), points.get(i)) > threshold) {
                // Start new cluster
                clusters.add(new ArrayList<>(currentCluster));
                currentCluster.clear();
            }
            currentCluster.add(points.get(i));
        }
        clusters.add(currentCluster);
        
        System.out.printf("Found %d clusters using Hilbert curve ordering:%n", clusters.size());
        for (int i = 0; i < clusters.size(); i++) {
            System.out.printf("  Cluster %d: %d points%n", i + 1, clusters.get(i).size());
        }
        System.out.println();
    }
    
    /**
     * Example 5: Using custom bounding box
     */
    private static void customBoundingBoxExample() {
        System.out.println("=== Custom Bounding Box Example ===");
        
        // Create comparator for a game world
        float worldSize = 1000.0f;
        HilbertCurveComparator worldComparator = HilbertCurveComparator.forCube(worldSize);
        
        // Some game objects
        Vector3f[] gameObjects = {
            new Vector3f(0, 0, 0),           // Center
            new Vector3f(500, 0, 0),         // East
            new Vector3f(-500, 0, 0),        // West
            new Vector3f(0, 500, 0),         // Up
            new Vector3f(0, -500, 0),        // Down
            new Vector3f(250, 250, 250),     // Northeast-up
            new Vector3f(-250, -250, -250),  // Southwest-down
        };
        
        // Sort game objects
        Arrays.sort(gameObjects, worldComparator);
        
        System.out.println("Game objects sorted by Hilbert curve:");
        for (int i = 0; i < gameObjects.length; i++) {
            Vector3f obj = gameObjects[i];
            long index = worldComparator.computeHilbertIndex(obj);
            System.out.printf("  %d. Position (%.0f, %.0f, %.0f) -> Hilbert index: %d%n",
                i + 1, obj.x, obj.y, obj.z, index);
        }
        
        // Demonstrate spatial queries
        System.out.println("\nFinding objects near origin (within 300 units):");
        for (Vector3f obj : gameObjects) {
            if (obj.length() <= 300) {
                System.out.printf("  Found: (%.0f, %.0f, %.0f)%n", obj.x, obj.y, obj.z);
            }
        }
    }
}