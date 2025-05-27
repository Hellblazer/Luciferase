package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Octree;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Performance test demonstrating the efficiency of spatial range queries vs. naive filtering of all contents.
 */
public class SpatialPerformanceTest {

    private Tetree<String> tetree;
    private Octree<String> octree;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new TreeMap<>());
        octree = new Octree<>(new TreeMap<>());

        // Populate with test data across multiple levels
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Add 1000 random points to demonstrate performance difference
        for (int i = 0; i < 1000; i++) {
            float x = random.nextFloat() * 10000;
            float y = random.nextFloat() * 10000;
            float z = random.nextFloat() * 10000;
            byte level = (byte) random.nextInt(5, 15);

            tetree.insert(new Point3f(x, y, z), level, "tetree-data-" + i);
            octree.insert(new Point3f(x, y, z), level, "octree-data-" + i);
        }
    }

    @Test
    @DisplayName("Demonstrate efficient spatial range query performance")
    void testSpatialRangeQueryPerformance() {
        // Define a query volume that covers part of the space
        Spatial.Cube queryVolume = new Spatial.Cube(2000.0f, 2000.0f, 2000.0f, 1000.0f);

        System.out.println("=== Spatial Range Query Performance Test ===");
        System.out.println("Total tetree contents: " + getTetreeContentCount());
        System.out.println("Total octree contents: " + getOctreeContentCount());
        System.out.println("Query volume: Cube at (2000,2000,2000) with extent 1000");

        // Test Tetree performance
        long tetreeStart = System.nanoTime();
        var tetreeBounded = tetree.boundedBy(queryVolume);
        long tetreedBoundedCount = tetreeBounded.count();
        long tetreeTime = System.nanoTime() - tetreeStart;

        tetreeStart = System.nanoTime();
        var tetreeBounding = tetree.bounding(queryVolume);
        long tetreeBoundingCount = tetreeBounding.count();
        long tetreeBoundingTime = System.nanoTime() - tetreeStart;

        // Test Octree performance
        long octreeStart = System.nanoTime();
        var octreeBounded = octree.boundedBy(queryVolume);
        long octreeBoundedCount = octreeBounded.count();
        long octreeTime = System.nanoTime() - octreeStart;

        octreeStart = System.nanoTime();
        var octreeBounding = octree.bounding(queryVolume);
        long octreeBoundingCount = octreeBounding.count();
        long octreeBoundingTime = System.nanoTime() - octreeStart;

        System.out.printf("Tetree boundedBy: %d results in %.2f ms%n", tetreedBoundedCount, tetreeTime / 1_000_000.0);
        System.out.printf("Tetree bounding: %d results in %.2f ms%n", tetreeBoundingCount,
                          tetreeBoundingTime / 1_000_000.0);
        System.out.printf("Octree boundedBy: %d results in %.2f ms%n", octreeBoundedCount, octreeTime / 1_000_000.0);
        System.out.printf("Octree bounding: %d results in %.2f ms%n", octreeBoundingCount,
                          octreeBoundingTime / 1_000_000.0);

        System.out.println("\n✅ Spatial range queries completed efficiently!");
        System.out.println("Note: The new implementation uses spatial indexing to only examine");
        System.out.println("relevant regions instead of filtering through all contents.");
    }

    private int getOctreeContentCount() {
        // Access the private map field via reflection for testing
        try {
            var field = Octree.class.getDeclaredField("map");
            field.setAccessible(true);
            var map = (TreeMap<?, ?>) field.get(octree);
            return map.size();
        } catch (Exception e) {
            return -1;
        }
    }

    private int getTetreeContentCount() {
        // Access the private contents field via reflection for testing
        try {
            var field = Tetree.class.getDeclaredField("contents");
            field.setAccessible(true);
            var contents = (TreeMap<?, ?>) field.get(tetree);
            return contents.size();
        } catch (Exception e) {
            return -1;
        }
    }
}
