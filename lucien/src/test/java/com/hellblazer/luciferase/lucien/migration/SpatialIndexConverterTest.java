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
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.AfterEach;
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

    /**
     * Restore the system clock after each test so injected clocks don't leak
     * across test methods (static field in SpatialIndexConverter).
     */
    @AfterEach
    void restoreSystemClock() {
        SpatialIndexConverter.setClock(com.hellblazer.luciferase.common.time.Clock.system());
    }

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

    // ---- Bead .16: null-content entity must not inflate processedCount ----

    @Test
    public void testNullContentEntityNotCountedAsSuccess() {
        // Build an octree with one real entity, then manually poison a second entity whose
        // content lookup will return null (simulated via a subclass that returns null for one ID).
        var idGenerator = new SequentialLongIDGenerator();

        // We cannot easily inject a null from the real Octree API, so we test via batchConvert
        // with an EntityData carrying null content — the target index receives null content.
        // The deeper null-content path in migrateEntities is exercised by verifying that
        // the converter's success counter only counts the non-null entity.

        // Insert one entity with non-null content and one with null content via EntityData batch
        var goodId = idGenerator.generateID();
        var nullId = idGenerator.generateID();
        var entities = new java.util.ArrayList<SpatialIndexConverter.EntityData<LongEntityID, String>>();
        entities.add(new SpatialIndexConverter.EntityData<>(goodId, new Point3f(10, 10, 10), (byte) 2, "valid"));
        entities.add(new SpatialIndexConverter.EntityData<>(nullId, new Point3f(20, 20, 20), (byte) 2, null));

        // batchConvert inserts unconditionally — this tests the EntityData path, not migrateEntities.
        // To test migrateEntities we use a NullContentOctree helper that returns null for one entity.
        var nullOctree = new NullContentOctree(idGenerator);
        var realId = nullOctree.insert(new Point3f(50, 50, 50), (byte) 2, "present");
        var ghostId = nullOctree.insertGhostWithNullContent(new Point3f(80, 80, 80), (byte) 2, idGenerator);

        var tetree = SpatialIndexConverter.octreeToTetree(nullOctree, idGenerator);

        // Only the entity with real content should be in the target
        assertTrue(tetree.containsEntity(realId), "Real entity must survive conversion");
        assertFalse(tetree.containsEntity(ghostId), "Null-content entity must NOT appear in target");
        // Entity count reflects only successful (non-null) migrations
        assertEquals(1, tetree.entityCount(),
                     "processedCount / entityCount must equal number of entities with non-null content");
    }

    /** Octree subclass that can produce a null-content entity for testing .16 */
    private static class NullContentOctree extends Octree<LongEntityID, String> {
        private LongEntityID nullContentId;

        NullContentOctree(SequentialLongIDGenerator gen) {
            super(gen, 5, (byte) 4);
        }

        /** Insert a position into the tree but record its ID so getEntity returns null. */
        LongEntityID insertGhostWithNullContent(Point3f pos, byte level, SequentialLongIDGenerator gen) {
            nullContentId = insert(pos, level, "__SENTINEL__");
            return nullContentId;
        }

        @Override
        public String getEntity(LongEntityID entityId) {
            if (entityId.equals(nullContentId)) {
                return null;
            }
            return super.getEntity(entityId);
        }
    }

    // ---- Bead .17: spanning entity must be preserved at all source levels ----

    @Test
    public void testSpanningEntityPreservedAcrossConversion() {
        // Insert the same entity at two different levels (simulating a spanning entity).
        // getEntityLocations() on the source will return both keys; after conversion the
        // target must also contain the entity at both levels.
        var idGenerator = new SequentialLongIDGenerator();
        var source = new Octree<LongEntityID, String>(idGenerator, 5, (byte) 6);

        // Insert at level 2 first to establish the entity
        var pos = new Point3f(100, 100, 100);
        var entityId = source.insert(pos, (byte) 2, "spanning");

        // Add the same entity at a second, deeper level to make it spanning.
        // AbstractSpatialIndex.insert(ID,pos,level,content) calls createOrUpdate + addLocation,
        // so the entity accumulates two location keys.
        source.insert(entityId, pos, (byte) 4, "spanning");

        var sourceLocations = source.getEntityLocations(entityId);
        assertTrue(sourceLocations.size() >= 2,
                   "Source must have >= 2 location keys for the spanning entity (got " + sourceLocations.size() + ")");

        var target = SpatialIndexConverter.octreeToTetree(source, idGenerator);

        // Entity must be present in target
        assertTrue(target.containsEntity(entityId), "Spanning entity must survive conversion");

        // Target must also carry all source levels
        var targetLocations = target.getEntityLocations(entityId);
        assertEquals(sourceLocations.size(), targetLocations.size(),
                     "Target must preserve all spanning levels. source=" + sourceLocations.size()
                     + " target=" + targetLocations.size());

        // The set of levels must match exactly
        var sourceLevels = sourceLocations.stream()
                                          .map(k -> (int) k.getLevel())
                                          .collect(java.util.stream.Collectors.toSet());
        var targetLevels = targetLocations.stream()
                                          .map(k -> (int) k.getLevel())
                                          .collect(java.util.stream.Collectors.toSet());
        assertEquals(sourceLevels, targetLevels, "Target spanning levels must match source spanning levels");
    }

    // ---- Bead .128: clock injection for deterministic conversion duration ----

    /**
     * Verifies that {@link SpatialIndexConverter#setClock} is honoured: when an injected
     * clock is in place, the converter reads time from it and not from
     * {@link System#currentTimeMillis()}.  We use a counting wrapper to assert that
     * {@code currentTimeMillis()} is called at least twice (start + end) during the conversion.
     */
    @Test
    public void testInjectedClockIsUsedForConversionDuration() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = createSampleOctree(idGenerator);

        // A clock that counts how many times it is read and advances by 50ms on each call.
        var callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        var time = new java.util.concurrent.atomic.AtomicLong(1000L);
        com.hellblazer.luciferase.common.time.Clock countingClock = () -> {
            callCount.incrementAndGet();
            return time.getAndAdd(50L);
        };

        SpatialIndexConverter.setClock(countingClock);

        // Conversion should succeed and use the injected clock.
        var tetree = SpatialIndexConverter.octreeToTetree(octree, idGenerator);

        assertNotNull(tetree);
        // The converter calls clock.currentTimeMillis() twice: once for startTime, once for duration.
        assertTrue(callCount.get() >= 2,
                   "Injected clock must be called at least twice (start + end); got " + callCount.get());
        // Duration reported by the injected clock is 50ms (second call - first call = 1050-1000).
        // We cannot inspect the log value, but if the clock is used the count proves it.
        assertEquals(octree.entityCount(), tetree.entityCount(), "Conversion must be correct");
    }

    /**
     * Same as above for the tetreeToOctree direction.
     */
    @Test
    public void testInjectedClockIsUsedForTetreeToOctreeConversionDuration() {
        var idGenerator = new SequentialLongIDGenerator();
        var tetree = createSampleTetree(idGenerator);

        var callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        var time = new java.util.concurrent.atomic.AtomicLong(2000L);
        com.hellblazer.luciferase.common.time.Clock countingClock = () -> {
            callCount.incrementAndGet();
            return time.getAndAdd(100L);
        };

        SpatialIndexConverter.setClock(countingClock);

        var octree = SpatialIndexConverter.tetreeToOctree(tetree, idGenerator);

        assertNotNull(octree);
        assertTrue(callCount.get() >= 2,
                   "Injected clock must be called at least twice (start + end); got " + callCount.get());
        assertEquals(tetree.entityCount(), octree.entityCount(), "Conversion must be correct");
    }

    // ---- Bead .130: per-entity failures must surface to the caller ----

    /**
     * Verifies that when a per-entity migration fails (simulated by an Octree subclass that
     * throws during {@code getEntityLocations}), the converter throws
     * {@link SpatialIndexConverter.ConversionException} rather than silently returning a
     * partial result.  The exception message must contain the failure count and the error detail.
     */
    @Test
    public void testFailingEntitySurfacesAsConversionException() {
        var idGenerator = new SequentialLongIDGenerator();

        // An Octree subclass that throws for one specific entity during getEntityLocations,
        // simulating a per-entity failure inside migrateEntities.
        var throwingOctree = new ThrowingOctree(idGenerator);
        throwingOctree.insert(new Point3f(10, 10, 10), (byte) 2, "good-1");
        throwingOctree.insert(new Point3f(20, 20, 20), (byte) 2, "good-2");
        var badId = throwingOctree.insertBadEntity(new Point3f(50, 50, 50), (byte) 2, "will-fail");

        var ex = assertThrows(SpatialIndexConverter.ConversionException.class,
                              () -> SpatialIndexConverter.octreeToTetree(throwingOctree, idGenerator),
                              "ConversionException expected when a per-entity migration fails");

        // Message must mention the failure count and include the entity error detail.
        var msg = ex.getMessage();
        assertTrue(msg.contains("lost 1"), "Exception message must mention 1 lost entity; was: " + msg);
        assertTrue(msg.contains("of 3"), "Exception message must mention total=3; was: " + msg);
    }

    /**
     * Verifies that a fully clean conversion (no per-entity failures) does NOT throw.
     * Guards against the ConversionException check being too broad.
     */
    @Test
    public void testCleanConversionDoesNotThrow() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = createSampleOctree(idGenerator);

        // Must complete without exception.
        assertDoesNotThrow(() -> SpatialIndexConverter.octreeToTetree(octree, idGenerator),
                           "Clean conversion must not throw ConversionException");
    }

    /**
     * Octree subclass that throws during {@code getEntityLocations} for one specific entity,
     * triggering the per-entity catch block inside {@code migrateEntities}.
     */
    private static class ThrowingOctree extends Octree<LongEntityID, String> {
        private LongEntityID badEntityId;

        ThrowingOctree(SequentialLongIDGenerator gen) {
            super(gen, 5, (byte) 4);
        }

        LongEntityID insertBadEntity(Point3f pos, byte level, String content) {
            badEntityId = insert(pos, level, content);
            return badEntityId;
        }

        @Override
        public java.util.Set<MortonKey> getEntityLocations(LongEntityID entityId) {
            if (entityId.equals(badEntityId)) {
                throw new RuntimeException("Simulated per-entity failure for entity " + entityId);
            }
            return super.getEntityLocations(entityId);
        }
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
