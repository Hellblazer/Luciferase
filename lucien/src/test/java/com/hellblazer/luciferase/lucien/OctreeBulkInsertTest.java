/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityData;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the bulk insert operation
 *
 * @author hal.hildebrand
 */
public class OctreeBulkInsertTest {

    @Test
    void testBulkInsertBasic() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        byte level = 15;

        // Create test data
        List<EntityData<LongEntityID, String>> testData = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            LongEntityID id = new LongEntityID(i);
            Point3f pos = new Point3f(i * 100, i * 100, i * 100);
            testData.add(new EntityData<>(id, pos, level, "Entity" + i));
        }

        // Bulk insert
        octree.insertAll(testData);

        // Verify all entities were inserted
        assertEquals(10, octree.entityCount());

        // Verify each entity can be found
        for (int i = 0; i < 10; i++) {
            LongEntityID id = new LongEntityID(i);
            assertTrue(octree.containsEntity(id));
            assertEquals("Entity" + i, octree.getEntity(id));

            Point3f expectedPos = new Point3f(i * 100, i * 100, i * 100);
            assertEquals(expectedPos, octree.getEntityPosition(id));
        }
    }

    @Test
    void testBulkInsertEmpty() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());

        // Insert empty collection
        octree.insertAll(new ArrayList<>());

        // Should have no entities
        assertEquals(0, octree.entityCount());
    }

    @Test
    void testBulkInsertMixedLevels() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());

        // Create test data with different levels
        List<EntityData<LongEntityID, String>> testData = new ArrayList<>();
        for (int level = 10; level <= 15; level++) {
            for (int i = 0; i < 5; i++) {
                LongEntityID id = new LongEntityID(level * 100 + i);
                Point3f pos = new Point3f(i * 100, level * 100, 0);
                testData.add(new EntityData<>(id, pos, (byte) level, "Entity_L" + level + "_" + i));
            }
        }

        // Bulk insert
        octree.insertAll(testData);

        // Verify all 30 entities (6 levels * 5 entities each)
        assertEquals(30, octree.entityCount());

        // Spot check some entities
        assertTrue(octree.containsEntity(new LongEntityID(1000))); // level 10, i=0
        assertTrue(octree.containsEntity(new LongEntityID(1504))); // level 15, i=4
    }

    @Test
    void testBulkInsertPerformance() {
        Octree<LongEntityID, String> octreeSequential = new Octree<>(new SequentialLongIDGenerator());
        Octree<LongEntityID, String> octreeBulk = new Octree<>(new SequentialLongIDGenerator());
        byte level = 15;
        int entityCount = 10000;

        // Create test data
        Random random = new Random(42);
        List<EntityData<LongEntityID, String>> testData = new ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            LongEntityID id = new LongEntityID(i);
            Point3f pos = new Point3f(random.nextFloat() * 10000, random.nextFloat() * 10000,
                                      random.nextFloat() * 10000);
            testData.add(new EntityData<>(id, pos, level, "Entity" + i));
        }

        // Measure sequential insert time
        long sequentialStart = System.nanoTime();
        for (EntityData<LongEntityID, String> data : testData) {
            octreeSequential.insert(data.id(), data.position(), data.level(), data.content());
        }
        long sequentialTime = System.nanoTime() - sequentialStart;

        // Measure bulk insert time
        long bulkStart = System.nanoTime();
        octreeBulk.insertAll(testData);
        long bulkTime = System.nanoTime() - bulkStart;

        // Verify both have same number of entities
        assertEquals(entityCount, octreeSequential.entityCount());
        assertEquals(entityCount, octreeBulk.entityCount());

        // Calculate speedup
        double speedup = (double) sequentialTime / bulkTime;

        System.out.printf("Bulk Insert Performance Test:%n");
        System.out.printf("  Sequential: %.2f ms (%.2f μs/op)%n", sequentialTime / 1_000_000.0,
                          sequentialTime / 1000.0 / entityCount);
        System.out.printf("  Bulk:       %.2f ms (%.2f μs/op)%n", bulkTime / 1_000_000.0,
                          bulkTime / 1000.0 / entityCount);
        System.out.printf("  Speedup:    %.2fx%n", speedup);

        // Bulk insert may have overhead from sorting, especially on slower machines
        // On CI or less capable hardware, the overhead can be more significant
        // Check if we're running on CI (common CI environment variables)
        boolean isCI = System.getenv("CI") != null || System.getenv("CONTINUOUS_INTEGRATION") != null || System.getenv(
        "GITHUB_ACTIONS") != null;

        if (isCI) {
            // On CI, we're mainly testing functionality, not performance
            System.out.println("Running on CI - skipping performance assertion");
        } else {
            // On development machines, expect reasonable performance
            // Bulk insert may have overhead from sorting and EntityManager operations
            assertTrue(speedup >= 0.4, "Bulk insert should not be more than 2.5x slower - speedup was " + speedup);
        }
    }

    @Test
    void testBulkInsertWithBounds() {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        byte level = 15;

        // Create test data with bounds
        List<EntityData<LongEntityID, String>> testData = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LongEntityID id = new LongEntityID(i);
            Point3f pos = new Point3f(i * 1000, i * 1000, i * 1000);
            EntityBounds bounds = new EntityBounds(pos, 50); // radius of 50
            testData.add(new EntityData<>(id, pos, level, "BoundedEntity" + i, bounds));
        }

        // Bulk insert with bounds
        octree.insertAll(testData);

        // Verify entities and bounds
        for (int i = 0; i < 5; i++) {
            LongEntityID id = new LongEntityID(i);
            assertTrue(octree.containsEntity(id));

            EntityBounds bounds = octree.getEntityBounds(id);
            assertNotNull(bounds);
            assertEquals(100, bounds.getMaxX() - bounds.getMinX(), 0.01); // diameter = 2 * radius
        }
    }
}
