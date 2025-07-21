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

import org.junit.jupiter.api.Test;
import javax.vecmath.Vector3f;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for HilbertCurveComparator.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class HilbertCurveComparatorTest {
    
    private static final float EPSILON = 1e-6f;
    
    @Test
    public void testBasicOrdering() {
        HilbertCurveComparator comparator = new HilbertCurveComparator();
        
        // Points at corners of unit cube should have consistent ordering
        Vector3f origin = new Vector3f(0, 0, 0);
        Vector3f corner = new Vector3f(1, 1, 1);
        
        // Origin should come before far corner
        assertTrue(comparator.compare(origin, corner) < 0);
        assertTrue(comparator.compare(corner, origin) > 0);
        assertEquals(0, comparator.compare(origin, origin));
    }
    
    @Test
    public void testSpatialLocality() {
        HilbertCurveComparator comparator = new HilbertCurveComparator();
        
        // Create a grid of points
        List<Vector3f> points = new ArrayList<>();
        float step = 0.1f;
        for (float x = 0; x <= 1; x += step) {
            for (float y = 0; y <= 1; y += step) {
                for (float z = 0; z <= 1; z += step) {
                    points.add(new Vector3f(x, y, z));
                }
            }
        }
        
        // Sort by Hilbert curve
        Collections.sort(points, comparator);
        
        // Verify that consecutive points in the sorted order are spatially close
        float totalDistance = 0;
        float maxDistance = 0;
        for (int i = 1; i < points.size(); i++) {
            Vector3f p1 = points.get(i - 1);
            Vector3f p2 = points.get(i);
            float distance = HilbertCurveComparator.distance(p1, p2);
            totalDistance += distance;
            maxDistance = Math.max(maxDistance, distance);
        }
        
        float avgDistance = totalDistance / (points.size() - 1);
        
        // Average distance between consecutive points should be relatively small
        // For a 10x10x10 grid, diagonal of a cell is sqrt(3) * 0.1 ≈ 0.173
        assertTrue(avgDistance < 0.3f, "Average distance: " + avgDistance);
        
        // Maximum jump should be bounded (not jumping across entire space)
        // Hilbert curve can have some larger jumps between levels
        assertTrue(maxDistance < 1.0f, "Max distance: " + maxDistance);
    }
    
    @Test
    public void testCustomBoundingBox() {
        HilbertCurveComparator comparator = new HilbertCurveComparator(
            -10, -10, -10, 10, 10, 10
        );
        
        Vector3f center = new Vector3f(0, 0, 0);
        Vector3f corner = new Vector3f(10, 10, 10);
        Vector3f opposite = new Vector3f(-10, -10, -10);
        
        // Test ordering
        assertTrue(comparator.compare(opposite, center) < 0);
        assertTrue(comparator.compare(center, corner) < 0);
        assertTrue(comparator.compare(opposite, corner) < 0);
    }
    
    @Test
    public void testDifferentPrecisions() {
        // Test with different bit precisions
        for (int bits = 1; bits <= 21; bits += 5) {
            HilbertCurveComparator comparator = new HilbertCurveComparator(
                0, 0, 0, 1, 1, 1, bits
            );
            
            Vector3f p1 = new Vector3f(0.25f, 0.25f, 0.25f);
            Vector3f p2 = new Vector3f(0.75f, 0.75f, 0.75f);
            
            // Basic ordering should be preserved regardless of precision
            // Note: at very low precision (1-2 bits), points may map to same index
            int cmp = comparator.compare(p1, p2);
            assertTrue(cmp <= 0, "Bits: " + bits + ", comparison: " + cmp);
        }
    }
    
    @Test
    public void testTransitivity() {
        HilbertCurveComparator comparator = new HilbertCurveComparator();
        
        Vector3f a = new Vector3f(0.1f, 0.2f, 0.3f);
        Vector3f b = new Vector3f(0.4f, 0.5f, 0.6f);
        Vector3f c = new Vector3f(0.7f, 0.8f, 0.9f);
        
        // Sort to establish order
        List<Vector3f> points = Arrays.asList(a, b, c);
        Collections.sort(points, comparator);
        
        // Verify transitivity: if a < b and b < c, then a < c
        for (int i = 0; i < points.size() - 2; i++) {
            for (int j = i + 1; j < points.size() - 1; j++) {
                for (int k = j + 1; k < points.size(); k++) {
                    Vector3f p1 = points.get(i);
                    Vector3f p2 = points.get(j);
                    Vector3f p3 = points.get(k);
                    
                    assertTrue(comparator.compare(p1, p2) < 0);
                    assertTrue(comparator.compare(p2, p3) < 0);
                    assertTrue(comparator.compare(p1, p3) < 0);
                }
            }
        }
    }
    
    @Test
    public void testConsistencyWithEquals() {
        HilbertCurveComparator comparator = new HilbertCurveComparator();
        
        Vector3f p1 = new Vector3f(0.5f, 0.5f, 0.5f);
        Vector3f p2 = new Vector3f(0.5f, 0.5f, 0.5f);
        
        // Same point should compare as equal
        assertEquals(0, comparator.compare(p1, p2));
        assertEquals(0, comparator.compare(p2, p1));
        
        // Should have same Hilbert index
        assertEquals(comparator.computeHilbertIndex(p1), 
                    comparator.computeHilbertIndex(p2));
    }
    
    @Test
    public void testBoundaryConditions() {
        HilbertCurveComparator comparator = new HilbertCurveComparator();
        
        // Points exactly on boundaries
        Vector3f[] boundaryPoints = {
            new Vector3f(0, 0, 0),
            new Vector3f(1, 0, 0),
            new Vector3f(0, 1, 0),
            new Vector3f(0, 0, 1),
            new Vector3f(1, 1, 0),
            new Vector3f(1, 0, 1),
            new Vector3f(0, 1, 1),
            new Vector3f(1, 1, 1)
        };
        
        // All boundary points should have valid indices
        for (Vector3f p : boundaryPoints) {
            long index = comparator.computeHilbertIndex(p);
            assertTrue(index >= 0);
        }
        
        // Sort and verify no exceptions
        Arrays.sort(boundaryPoints, comparator);
    }
    
    @Test
    public void testOutOfBoundsHandling() {
        HilbertCurveComparator comparator = new HilbertCurveComparator(
            0, 0, 0, 1, 1, 1
        );
        
        // Points outside bounding box should be clamped
        Vector3f inside = new Vector3f(0.5f, 0.5f, 0.5f);
        Vector3f outsideBelow = new Vector3f(-1, -1, -1);
        Vector3f outsideAbove = new Vector3f(2, 2, 2);
        
        // Should still produce valid comparison results
        assertTrue(comparator.compare(outsideBelow, inside) <= 0);
        assertTrue(comparator.compare(inside, outsideAbove) <= 0);
    }
    
    @Test
    public void testFactoryMethods() {
        // Test cube factory
        HilbertCurveComparator cubeCmp = HilbertCurveComparator.forCube(5.0f);
        Vector3f p1 = new Vector3f(-4, -4, -4);
        Vector3f p2 = new Vector3f(4, 4, 4);
        assertTrue(cubeCmp.compare(p1, p2) < 0);
        
        // Test box factory
        HilbertCurveComparator boxCmp = HilbertCurveComparator.forBox(10, 20, 30);
        Vector3f p3 = new Vector3f(-4, -8, -12);
        Vector3f p4 = new Vector3f(4, 8, 12);
        assertTrue(boxCmp.compare(p3, p4) < 0);
    }
    
    @Test
    public void testInvalidParameters() {
        // Test invalid bits per dimension
        assertThrows(IllegalArgumentException.class, () -> {
            new HilbertCurveComparator(0, 0, 0, 1, 1, 1, 0);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            new HilbertCurveComparator(0, 0, 0, 1, 1, 1, 22);
        });
        
        // Test invalid bounding box
        assertThrows(IllegalArgumentException.class, () -> {
            new HilbertCurveComparator(1, 0, 0, 0, 1, 1);
        });
    }
    
    @Test
    public void testLargeDatasetPerformance() {
        HilbertCurveComparator comparator = new HilbertCurveComparator();
        
        // Generate random points
        Random rand = new Random(42);
        List<Vector3f> points = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            points.add(new Vector3f(rand.nextFloat(), rand.nextFloat(), rand.nextFloat()));
        }
        
        // Time the sort
        long start = System.nanoTime();
        Collections.sort(points, comparator);
        long elapsed = System.nanoTime() - start;
        
        // Should complete in reasonable time (< 100ms for 10k points)
        assertTrue(elapsed < 100_000_000L, "Sort took: " + (elapsed / 1_000_000) + "ms");
        
        // Verify sorted order
        for (int i = 1; i < points.size(); i++) {
            assertTrue(comparator.compare(points.get(i-1), points.get(i)) <= 0);
        }
    }
    
    @Test
    public void testSpatialClustering() {
        HilbertCurveComparator comparator = new HilbertCurveComparator();
        
        // Create clusters of points
        List<Vector3f> points = new ArrayList<>();
        
        // Cluster 1: around (0.2, 0.2, 0.2)
        for (int i = 0; i < 10; i++) {
            points.add(new Vector3f(
                0.2f + (float)(Math.random() * 0.05),
                0.2f + (float)(Math.random() * 0.05),
                0.2f + (float)(Math.random() * 0.05)
            ));
        }
        
        // Cluster 2: around (0.8, 0.8, 0.8)
        for (int i = 0; i < 10; i++) {
            points.add(new Vector3f(
                0.8f + (float)(Math.random() * 0.05),
                0.8f + (float)(Math.random() * 0.05),
                0.8f + (float)(Math.random() * 0.05)
            ));
        }
        
        // Sort by Hilbert curve
        Collections.sort(points, comparator);
        
        // Points from the same cluster should tend to be near each other
        // Count how many times we jump between clusters
        int clusterJumps = 0;
        for (int i = 1; i < points.size(); i++) {
            boolean prev = isInFirstCluster(points.get(i-1));
            boolean curr = isInFirstCluster(points.get(i));
            if (prev != curr) {
                clusterJumps++;
            }
        }
        
        // Should have few jumps between clusters (ideally just 1)
        assertTrue(clusterJumps <= 4, "Too many cluster jumps: " + clusterJumps);
    }
    
    private boolean isInFirstCluster(Vector3f p) {
        return p.x < 0.5f && p.y < 0.5f && p.z < 0.5f;
    }
    
    @Test
    public void testStability() {
        HilbertCurveComparator comparator = new HilbertCurveComparator();
        
        // Create points with same Hilbert index but different coordinates
        // Due to quantization, nearby points may map to same index
        List<Vector3f> points = new ArrayList<>();
        float delta = 1.0f / (1 << 22); // Smaller than quantization precision
        
        for (int i = 0; i < 5; i++) {
            points.add(new Vector3f(0.5f + i * delta, 0.5f, 0.5f));
        }
        
        // Sort multiple times - order should be consistent
        List<Vector3f> sorted1 = new ArrayList<>(points);
        List<Vector3f> sorted2 = new ArrayList<>(points);
        
        Collections.sort(sorted1, comparator);
        Collections.shuffle(sorted2); // Shuffle before second sort
        Collections.sort(sorted2, comparator);
        
        // Both sorts should produce same order for equal elements
        for (int i = 0; i < sorted1.size(); i++) {
            if (comparator.compare(sorted1.get(i), sorted2.get(i)) != 0) {
                // If they're different, they should at least have same Hilbert index
                assertEquals(
                    comparator.computeHilbertIndex(sorted1.get(i)),
                    comparator.computeHilbertIndex(sorted2.get(i))
                );
            }
        }
    }
}