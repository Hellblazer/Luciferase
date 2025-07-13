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
package com.hellblazer.luciferase.lucien.migration;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SpatialIndexConverter
 */
public class SpatialIndexConverterTest {

    @Test
    public void testOctreeToTetreeConversion() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = createSampleOctree(idGenerator);
        
        // Get original stats
        var originalEntityCount = octree.entityCount();
        var originalNodeCount = octree.nodeCount();
        var originalEntities = octree.getEntitiesWithPositions();
        
        // Convert to Tetree
        var tetree = SpatialIndexConverter.octreeToTetree(octree, idGenerator);
        
        // Verify conversion
        assertNotNull(tetree);
        assertEquals(originalEntityCount, tetree.entityCount(), "Entity count should match");
        
        // Verify all entities were transferred
        for (var entry : originalEntities.entrySet()) {
            var entityId = entry.getKey();
            var position = entry.getValue();
            
            assertTrue(tetree.containsEntity(entityId), 
                      "Tetree should contain entity " + entityId);
            
            var tetreePos = tetree.getEntityPosition(entityId);
            assertNotNull(tetreePos, "Position should not be null");
            assertEquals(position.x, tetreePos.x, 0.001f);
            assertEquals(position.y, tetreePos.y, 0.001f);
            assertEquals(position.z, tetreePos.z, 0.001f);
            
            // Verify content
            var originalContent = octree.getEntity(entityId);
            var tetreeContent = tetree.getEntity(entityId);
            assertEquals(originalContent, tetreeContent, "Content should match");
        }
        
        System.out.println("Octree to Tetree conversion:");
        System.out.println("Original nodes: " + originalNodeCount);
        System.out.println("Tetree nodes: " + tetree.nodeCount());
        System.out.println("Entities transferred: " + originalEntityCount);
    }

    @Test
    public void testTetreeToOctreeConversion() {
        var idGenerator = new SequentialLongIDGenerator();
        var tetree = createSampleTetree(idGenerator);
        
        // Get original stats
        var originalEntityCount = tetree.entityCount();
        var originalNodeCount = tetree.nodeCount();
        var originalEntities = tetree.getEntitiesWithPositions();
        
        // Convert to Octree
        var octree = SpatialIndexConverter.tetreeToOctree(tetree, idGenerator);
        
        // Verify conversion
        assertNotNull(octree);
        assertEquals(originalEntityCount, octree.entityCount(), "Entity count should match");
        
        // Verify all entities were transferred
        for (var entry : originalEntities.entrySet()) {
            var entityId = entry.getKey();
            var position = entry.getValue();
            
            assertTrue(octree.containsEntity(entityId), 
                      "Octree should contain entity " + entityId);
            
            var octreePos = octree.getEntityPosition(entityId);
            assertNotNull(octreePos, "Position should not be null");
            assertEquals(position.x, octreePos.x, 0.001f);
            assertEquals(position.y, octreePos.y, 0.001f);
            assertEquals(position.z, octreePos.z, 0.001f);
            
            // Verify content
            var originalContent = tetree.getEntity(entityId);
            var octreeContent = octree.getEntity(entityId);
            assertEquals(originalContent, octreeContent, "Content should match");
        }
        
        System.out.println("\nTetree to Octree conversion:");
        System.out.println("Original nodes: " + originalNodeCount);
        System.out.println("Octree nodes: " + octree.nodeCount());
        System.out.println("Entities transferred: " + originalEntityCount);
    }

    @Test
    public void testRoundTripConversion() {
        var idGenerator = new SequentialLongIDGenerator();
        var originalOctree = createSampleOctree(idGenerator);
        
        // Convert Octree -> Tetree -> Octree
        var tetree = SpatialIndexConverter.octreeToTetree(originalOctree, idGenerator);
        var finalOctree = SpatialIndexConverter.tetreeToOctree(tetree, idGenerator);
        
        // Verify round trip preserves data
        assertEquals(originalOctree.entityCount(), finalOctree.entityCount());
        
        var originalEntities = originalOctree.getEntitiesWithPositions();
        var finalEntities = finalOctree.getEntitiesWithPositions();
        
        assertEquals(originalEntities.size(), finalEntities.size());
        
        for (var entry : originalEntities.entrySet()) {
            var entityId = entry.getKey();
            var originalPos = entry.getValue();
            var finalPos = finalEntities.get(entityId);
            
            assertNotNull(finalPos);
            assertEquals(originalPos.x, finalPos.x, 0.001f);
            assertEquals(originalPos.y, finalPos.y, 0.001f);
            assertEquals(originalPos.z, finalPos.z, 0.001f);
            
            var originalContent = originalOctree.getEntity(entityId);
            var finalContent = finalOctree.getEntity(entityId);
            assertEquals(originalContent, finalContent);
        }
    }

    @Test
    public void testConversionWithProgress() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = createLargeOctree(idGenerator, 1000);
        
        var progressUpdates = new ArrayList<String>();
        
        var progressCallback = new SpatialIndexConverter.ProgressCallback() {
            @Override
            public void onProgress(int processed, int total) {
                progressUpdates.add(String.format("Progress: %d/%d", processed, total));
            }

            @Override
            public void onComplete(int total) {
                progressUpdates.add(String.format("Complete: %d entities", total));
            }
        };
        
        var result = SpatialIndexConverter.convertWithProgress(
            octree, 
            SpatialIndexConverter.SpatialIndexType.TETREE,
            idGenerator,
            progressCallback
        );
        
        assertNotNull(result);
        assertTrue(result instanceof Tetree);
        assertFalse(progressUpdates.isEmpty(), "Should have progress updates");
        assertTrue(progressUpdates.get(progressUpdates.size() - 1).startsWith("Complete:"));
        
        System.out.println("\nProgress updates received: " + progressUpdates.size());
    }

    @Test
    public void testBatchConversion() {
        var idGenerator = new SequentialLongIDGenerator();
        
        // Create batch entity data
        var entities = new ArrayList<SpatialIndexConverter.EntityData<LongEntityID, String>>();
        for (int i = 0; i < 100; i++) {
            var id = idGenerator.generateID();
            var pos = new Point3f(
                (float) (Math.random() * 1000),
                (float) (Math.random() * 1000),
                (float) (Math.random() * 1000)
            );
            entities.add(new SpatialIndexConverter.EntityData<>(
                id, pos, (byte) 3, "Entity " + i
            ));
        }
        
        // Convert to Octree
        var octree = (Octree<LongEntityID, String>) SpatialIndexConverter.batchConvert(
            entities,
            null, // source type not used for batch convert
            SpatialIndexConverter.SpatialIndexType.OCTREE,
            idGenerator,
            (byte) 5,
            10
        );
        
        assertNotNull(octree);
        assertEquals(100, octree.entityCount());
        
        // Convert to Tetree
        var tetree = (Tetree<LongEntityID, String>) SpatialIndexConverter.batchConvert(
            entities,
            null, // source type not used for batch convert
            SpatialIndexConverter.SpatialIndexType.TETREE,
            idGenerator,
            (byte) 5,
            10
        );
        
        assertNotNull(tetree);
        assertEquals(100, tetree.entityCount());
    }

    @Test
    public void testEmptyIndexConversion() {
        var idGenerator = new SequentialLongIDGenerator();
        var emptyOctree = new Octree<LongEntityID, String>(idGenerator);
        
        var tetree = SpatialIndexConverter.octreeToTetree(emptyOctree, idGenerator);
        
        assertNotNull(tetree);
        assertEquals(0, tetree.entityCount());
        assertEquals(0, tetree.nodeCount());
    }

    @Test
    public void testConversionPerformance() {
        var idGenerator = new SequentialLongIDGenerator();
        var size = 10000;
        var octree = createLargeOctree(idGenerator, size);
        
        var startTime = System.currentTimeMillis();
        var tetree = SpatialIndexConverter.octreeToTetree(octree, idGenerator);
        var conversionTime = System.currentTimeMillis() - startTime;
        
        System.out.println("\nPerformance test:");
        System.out.println("Entities: " + size);
        System.out.println("Conversion time: " + conversionTime + "ms");
        System.out.println("Rate: " + (size * 1000.0 / conversionTime) + " entities/sec");
        
        assertEquals(size, tetree.entityCount());
    }

    // Helper methods

    private Octree<LongEntityID, String> createSampleOctree(SequentialLongIDGenerator idGenerator) {
        var octree = new Octree<LongEntityID, String>(idGenerator, 5, (byte) 4);
        
        // Add diverse points
        octree.insert(new Point3f(10, 10, 10), (byte) 3, "Near origin");
        octree.insert(new Point3f(100, 100, 100), (byte) 3, "Center");
        octree.insert(new Point3f(200, 200, 200), (byte) 3, "Far corner");
        octree.insert(new Point3f(50, 150, 75), (byte) 3, "Mixed 1");
        octree.insert(new Point3f(150, 50, 125), (byte) 3, "Mixed 2");
        
        // Add cluster
        for (int i = 0; i < 5; i++) {
            octree.insert(new Point3f(110 + i, 110 + i, 110 + i), (byte) 3, "Cluster " + i);
        }
        
        return octree;
    }

    private Tetree<LongEntityID, String> createSampleTetree(SequentialLongIDGenerator idGenerator) {
        var tetree = new Tetree<LongEntityID, String>(idGenerator, 5, (byte) 4);
        
        // Add diverse points
        tetree.insert(new Point3f(10, 10, 10), (byte) 3, "Near origin");
        tetree.insert(new Point3f(100, 100, 100), (byte) 3, "Center");
        tetree.insert(new Point3f(200, 200, 200), (byte) 3, "Far corner");
        tetree.insert(new Point3f(50, 150, 75), (byte) 3, "Mixed 1");
        tetree.insert(new Point3f(150, 50, 125), (byte) 3, "Mixed 2");
        
        // Add cluster
        for (int i = 0; i < 5; i++) {
            tetree.insert(new Point3f(110 + i, 110 + i, 110 + i), (byte) 3, "Cluster " + i);
        }
        
        return tetree;
    }

    private Octree<LongEntityID, String> createLargeOctree(SequentialLongIDGenerator idGenerator, int size) {
        var octree = new Octree<LongEntityID, String>(idGenerator, 10, (byte) 6);
        
        for (int i = 0; i < size; i++) {
            var x = (float) (Math.random() * 1000);
            var y = (float) (Math.random() * 1000);
            var z = (float) (Math.random() * 1000);
            octree.insert(new Point3f(x, y, z), (byte) 4, "Entity " + i);
        }
        
        return octree;
    }

}