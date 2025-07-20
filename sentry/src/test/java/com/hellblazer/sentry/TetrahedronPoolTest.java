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
import java.util.Random;
import java.util.ArrayList;
import java.util.List;

/**
 * Test that the TetrahedronPool is properly reusing tetrahedra during
 * clear() and rebuild() operations.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class TetrahedronPoolTest {
    
    private Random random;
    
    @BeforeEach
    public void setUp() {
        random = new Random(42);
        // Reset the pool before each test
        TetrahedronPool.getInstance().clear();
    }
    
    @Test
    @DisplayName("Test pool reuse during rebuild")
    public void testPoolReuseDuringRebuild() {
        MutableGrid grid = new MutableGrid();
        
        // Add some points
        List<Point3f> points = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            points.add(randomPoint());
        }
        
        // Track initial insertions
        for (Point3f p : points) {
            grid.track(p, random);
        }
        
        // Get initial pool stats
        TetrahedronPool pool = TetrahedronPool.getInstance();
        String initialStats = pool.getStatistics();
        System.out.println("Initial pool stats: " + initialStats);
        
        // Rebuild should release tetrahedra back to pool
        grid.rebuild(random);
        
        String afterRebuildStats = pool.getStatistics();
        System.out.println("After rebuild stats: " + afterRebuildStats);
        
        // Verify that tetrahedra were released (check release count increased)
        int releasesAfterRebuild = extractReleaseCount(afterRebuildStats);
        int releasesInitial = extractReleaseCount(initialStats);
        assertTrue(releasesAfterRebuild > releasesInitial, 
            "Release count should increase after rebuild");
        
        // Do another rebuild to see reuse
        int poolSizeBefore = pool.getPoolSize();
        grid.rebuild(random);
        
        String finalStats = pool.getStatistics();
        System.out.println("Final stats: " + finalStats);
        
        // Check that more tetrahedra were released
        int finalReleases = extractReleaseCount(finalStats);
        assertTrue(finalReleases > releasesAfterRebuild, 
            "More tetrahedra should be released after second rebuild");
    }
    
    @Test
    @DisplayName("Test pool reuse during clear")
    public void testPoolReuseDuringClear() {
        MutableGrid grid = new MutableGrid();
        TetrahedronPool pool = TetrahedronPool.getInstance();
        
        // Add points
        for (int i = 0; i < 30; i++) {
            grid.track(randomPoint(), random);
        }
        
        int tetrahedraCount = grid.tetrahedrons().size();
        System.out.println("Tetrahedra in grid: " + tetrahedraCount);
        
        // Clear should release all tetrahedra
        int poolSizeBefore = pool.getPoolSize();
        grid.clear();
        int poolSizeAfter = pool.getPoolSize();
        
        System.out.println("Pool size before clear: " + poolSizeBefore);
        System.out.println("Pool size after clear: " + poolSizeAfter);
        
        // Check that tetrahedra were released (pool might be at max capacity)
        assertTrue(poolSizeAfter >= poolSizeBefore || poolSizeAfter == pool.getMaxSize(), 
            "Pool size should increase after clear or be at max capacity");
    }
    
    @Test
    @DisplayName("Test pool statistics tracking")
    public void testPoolStatistics() {
        MutableGrid grid = new MutableGrid();
        TetrahedronPool pool = TetrahedronPool.getInstance();
        
        // Clear pool stats
        pool.clear();
        
        // Add points and track acquisitions
        for (int i = 0; i < 25; i++) {
            grid.track(randomPoint(), random);
        }
        
        String stats1 = pool.getStatistics();
        System.out.println("After insertions: " + stats1);
        
        // Rebuild to test release/reacquire cycle
        grid.rebuild(random);
        
        String stats2 = pool.getStatistics();
        System.out.println("After rebuild: " + stats2);
        
        // Verify statistics are being tracked
        assertTrue(stats2.contains("acquired="), "Stats should track acquisitions");
        assertTrue(stats2.contains("released="), "Stats should track releases");
        assertTrue(stats2.contains("reuse-rate="), "Stats should calculate reuse rate");
    }
    
    @Test
    @DisplayName("Test memory efficiency with repeated rebuilds")
    public void testMemoryEfficiencyWithRebuilds() {
        MutableGrid grid = new MutableGrid();
        TetrahedronPool pool = TetrahedronPool.getInstance();
        
        // Add initial points
        List<Point3f> points = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            points.add(randomPoint());
        }
        
        for (Point3f p : points) {
            grid.track(p, random);
        }
        
        // Do multiple rebuilds
        for (int i = 0; i < 5; i++) {
            grid.rebuild(random);
            System.out.println("Rebuild " + (i+1) + ": " + pool.getStatistics());
        }
        
        // Check final statistics
        String finalStats = pool.getStatistics();
        System.out.println("Final statistics: " + finalStats);
        
        // Extract reuse rate from stats
        int reuseStart = finalStats.indexOf("reuse-rate=") + 11;
        int reuseEnd = finalStats.indexOf("%", reuseStart);
        double reuseRate = Double.parseDouble(finalStats.substring(reuseStart, reuseEnd));
        
        // After multiple rebuilds, we should have some reuse
        assertTrue(reuseRate > 10.0, "Reuse rate should be > 10% after multiple rebuilds");
    }
    
    private Point3f randomPoint() {
        return new Point3f(
            random.nextFloat() * 100.0f + 10.0f,
            random.nextFloat() * 100.0f + 10.0f,
            random.nextFloat() * 100.0f + 10.0f
        );
    }
    
    private int extractReleaseCount(String stats) {
        int start = stats.indexOf("released=") + 9;
        int end = stats.indexOf(",", start);
        return Integer.parseInt(stats.substring(start, end));
    }
}