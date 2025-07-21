/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the 3D Incremental Voronoi system
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
package com.hellblazer.sentry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import javax.vecmath.Point3f;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.ArrayList;
import java.util.List;

/**
 * Test that the aggressive release strategy with deferred release
 * is working correctly and improving pool reuse.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class AggressiveReleaseTest {
    
    private Random random;
    
    @BeforeEach
    public void setUp() {
        random = new Random(12345);
    }
    
    @Test
    @DisplayName("Test deferred release during flip operations")
    public void testDeferredReleaseInFlips() {
        MutableGrid grid = new MutableGrid();
        TetrahedronPool pool = getPool(grid);
        
        // Add points that will trigger many flips
        List<Point3f> points = generateClusteredPoints(100);
        
        // Get baseline stats
        String beforeStats = pool.getStatistics();
        System.out.println("Before insertions: " + beforeStats);
        
        // Insert points (this will trigger many flip operations)
        for (Point3f p : points) {
            grid.track(p, random);
        }
        
        String afterStats = pool.getStatistics();
        System.out.println("After insertions: " + afterStats);
        
        // Extract stats
        int created = extractStat(afterStats, "created=");
        int released = extractStat(afterStats, "released=");
        int acquired = extractStat(afterStats, "acquired=");
        double reuseRate = extractReuseRate(afterStats);
        
        System.out.println("Created: " + created);
        System.out.println("Released: " + released);
        System.out.println("Acquired: " + acquired);
        System.out.println("Reuse rate: " + reuseRate + "%");
        
        // With deferred release, we should see good reuse
        assertTrue(reuseRate > 50.0, "Reuse rate should be > 50% with aggressive release");
        
        // Released should be close to acquired (indicating good release behavior)
        double releaseRatio = (double) released / acquired;
        System.out.println("Release ratio: " + releaseRatio);
        assertTrue(releaseRatio > 0.7, "Release ratio should be > 0.7 with aggressive release");
    }
    
    @Test
    @DisplayName("Test pool efficiency during cascading flips")
    public void testPoolEfficiencyDuringCascadingFlips() {
        MutableGrid grid = new MutableGrid();
        TetrahedronPool pool = getPool(grid);
        
        // Pre-warm the pool to baseline size
        pool.warmUp(128);
        
        // Create a challenging point distribution that causes many cascading flips
        List<Point3f> points = new ArrayList<>();
        
        // Create points in a sphere (causes many flips)
        for (int i = 0; i < 50; i++) {
            double theta = random.nextDouble() * 2 * Math.PI;
            double phi = random.nextDouble() * Math.PI;
            float r = 50.0f;
            float x = (float)(r * Math.sin(phi) * Math.cos(theta)) + 60.0f;
            float y = (float)(r * Math.sin(phi) * Math.sin(theta)) + 60.0f;
            float z = (float)(r * Math.cos(phi)) + 60.0f;
            points.add(new Point3f(x, y, z));
        }
        
        // Measure pool performance during insertions
        String beforeStats = pool.getStatistics();
        for (Point3f p : points) {
            grid.track(p, random);
        }
        String afterStats = pool.getStatistics();
        
        System.out.println("Before cascading flips: " + beforeStats);
        System.out.println("After cascading flips: " + afterStats);
        
        // Check that pool size didn't explode
        int poolSize = pool.getPoolSize();
        int created = extractStat(afterStats, "created=");
        
        System.out.println("Pool size: " + poolSize);
        System.out.println("Created new: " + created);
        
        // With aggressive release, pool shouldn't grow excessively
        assertTrue(poolSize < 500, "Pool size should be contained with aggressive release");
    }
    
    @Test
    @DisplayName("Test no crashes with aggressive release")
    public void testNoCrashesWithAggressiveRelease() {
        // This test ensures that the deferred release doesn't cause crashes
        // that the commented-out releases were avoiding
        
        MutableGrid grid = new MutableGrid();
        
        // Add many random points
        for (int i = 0; i < 200; i++) {
            Point3f p = new Point3f(
                random.nextFloat() * 90.0f + 15.0f,
                random.nextFloat() * 90.0f + 15.0f,
                random.nextFloat() * 90.0f + 15.0f
            );
            
            // This should not crash
            assertDoesNotThrow(() -> grid.track(p, random));
        }
        
        // Verify grid integrity
        assertTrue(grid.size() > 190, "Most points should be successfully inserted");
        
        // Do a rebuild to stress test the release mechanism
        assertDoesNotThrow(() -> grid.rebuild(random));
        
        System.out.println("Successfully inserted " + grid.size() + " points without crashes");
    }
    
    private List<Point3f> generateClusteredPoints(int count) {
        List<Point3f> points = new ArrayList<>();
        
        // Create clusters to increase flip activity
        int clusters = 5;
        for (int c = 0; c < clusters; c++) {
            float cx = random.nextFloat() * 60.0f + 30.0f;
            float cy = random.nextFloat() * 60.0f + 30.0f;
            float cz = random.nextFloat() * 60.0f + 30.0f;
            
            int clusterSize = count / clusters;
            for (int i = 0; i < clusterSize; i++) {
                float x = cx + (float)(random.nextGaussian() * 10.0);
                float y = cy + (float)(random.nextGaussian() * 10.0);
                float z = cz + (float)(random.nextGaussian() * 10.0);
                points.add(new Point3f(x, y, z));
            }
        }
        
        return points;
    }
    
    private int extractStat(String stats, String key) {
        int start = stats.indexOf(key) + key.length();
        int end = stats.indexOf(",", start);
        if (end == -1) end = stats.indexOf("]", start);
        return Integer.parseInt(stats.substring(start, end));
    }
    
    private double extractReuseRate(String stats) {
        int start = stats.indexOf("reuse-rate=") + 11;
        int end = stats.indexOf("%", start);
        return Double.parseDouble(stats.substring(start, end));
    }
    
    private TetrahedronPool getPool(MutableGrid grid) {
        try {
            Method getPoolMethod = MutableGrid.class.getDeclaredMethod("getPool");
            getPoolMethod.setAccessible(true);
            return (TetrahedronPool) getPoolMethod.invoke(grid);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access pool via reflection", e);
        }
    }
}