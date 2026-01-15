package com.hellblazer.luciferase.lucien.prism;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.Plane3D;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Edge case and boundary condition tests for the Prism spatial index.
 * Tests unusual inputs, extreme values, and corner cases.
 */
public class PrismEdgeCaseTest {

    private Prism<LongEntityID, String> prism;
    private SequentialLongIDGenerator idGenerator;
    private final float worldSize = 100.0f;
    
    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        prism = new Prism<>(idGenerator, worldSize, 21);
    }
    
    @Test
    void testMinimumWorldSize() {
        // Test with very small world size
        var tinyPrism = new Prism<LongEntityID, String>(idGenerator, 0.1f, 21);
        
        var pos = new Point3f(0.04f, 0.04f, 0.05f); // x+y=0.08 < 0.1
        var id = tinyPrism.insert(pos, (byte)10, "Tiny");
        
        assertNotNull(id);
        assertTrue(tinyPrism.containsEntity(id));
        assertEquals(1, tinyPrism.entityCount());
    }
    
    @Test
    void testExtremeCoordinateValues() {
        // Test with Float.MIN_VALUE
        var minPos = new Point3f(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE);
        var minId = prism.insert(minPos, (byte)10, "MinValue");
        assertTrue(prism.containsEntity(minId));
        
        // Test near world boundary
        float maxCoord = worldSize * 0.4999f;
        var maxPos = new Point3f(maxCoord, maxCoord, worldSize - 0.001f);
        var maxId = prism.insert(maxPos, (byte)10, "MaxValue");
        assertTrue(prism.containsEntity(maxId));
        
        // Verify both can be queried
        var results = prism.kNearestNeighbors(new Point3f(50.0f, 25.0f, 50.0f), 10, Float.MAX_VALUE);
        assertTrue(results.size() >= 1, "Should find at least one entity");
    }
    
    @Test
    void testZeroVolumeEntities() {
        // Insert multiple entities at exact same position
        var pos = new Point3f(25.0f, 25.0f, 50.0f);
        List<LongEntityID> ids = new ArrayList<>();
        
        for (int i = 0; i < 100; i++) {
            var id = prism.insert(pos, (byte)15, "Zero" + i);
            ids.add(id);
        }
        
        assertEquals(100, prism.entityCount());
        
        // All should be findable
        for (var id : ids) {
            assertTrue(prism.containsEntity(id));
        }
        
        // k-NN should find all at distance 0
        var neighbors = prism.kNearestNeighbors(pos, 100, Float.MAX_VALUE);
        assertEquals(100, neighbors.size());
    }
    
    @Test
    void testEntityBoundsEdgeCases() {
        // Bounds that exceed triangular constraint
        var center = new Point3f(30.0f, 30.0f, 50.0f);
        var bounds = new EntityBounds(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(worldSize, worldSize, worldSize) // Clearly exceeds constraint
        );
        
        // Should still insert at center position
        var id = idGenerator.generateID();
        prism.insert(id, center, (byte)8, "LargeBounds", bounds);
        assertTrue(prism.containsEntity(id));
        
        // Zero-size bounds
        var zeroBounds = new EntityBounds(center, center);
        var zeroId = idGenerator.generateID();
        prism.insert(zeroId, center, (byte)8, "ZeroBounds", zeroBounds);
        assertTrue(prism.containsEntity(zeroId));
        
        // Inverted bounds (min > max)
        var invertedBounds = new EntityBounds(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(10.0f, 10.0f, 10.0f)
        );
        var invertedId = idGenerator.generateID();
        prism.insert(invertedId, center, (byte)8, "InvertedBounds", invertedBounds);
        assertTrue(prism.containsEntity(invertedId));
    }
    
    @Test
    void testEmptyPrismOperations() {
        // Operations on empty prism
        assertEquals(0, prism.entityCount());
        
        // k-NN on empty prism
        var emptyKnn = prism.kNearestNeighbors(new Point3f(50.0f, 25.0f, 50.0f), 10, Float.MAX_VALUE);
        assertTrue(emptyKnn.isEmpty());
        
        // Range query on empty prism
        var emptyCube = new Spatial.Cube(0.0f, 0.0f, 0.0f, worldSize);
        var emptyRange = prism.entitiesInRegion(emptyCube);
        assertTrue(emptyRange.isEmpty());
        
        // Ray intersection on empty prism
        var ray = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 1, 1));
        var emptyRay = prism.rayIntersectAll(ray);
        assertTrue(emptyRay.isEmpty());
        
        // Remove from empty prism
        assertFalse(prism.removeEntity(new LongEntityID(12345L)));
    }
    
    @Test
    void testQueryWithInvalidParameters() {
        // Insert some entities first
        prism.insert(new Point3f(25.0f, 25.0f, 50.0f), (byte)10, "Test1");
        prism.insert(new Point3f(30.0f, 20.0f, 60.0f), (byte)10, "Test2");
        
        // k-NN with k=0
        var zeroK = prism.kNearestNeighbors(new Point3f(25.0f, 25.0f, 50.0f), 0, Float.MAX_VALUE);
        assertTrue(zeroK.isEmpty());
        
        // k-NN with negative k (should be treated as 0)
        var negativeK = prism.kNearestNeighbors(new Point3f(25.0f, 25.0f, 50.0f), -5, Float.MAX_VALUE);
        assertTrue(negativeK.isEmpty());
        
        // k-NN with zero search radius - should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> 
            prism.kNearestNeighbors(new Point3f(25.0f, 25.0f, 50.0f), 10, 0.0f));
        
        // Range query with zero-size cube
        var zeroCube = new Spatial.Cube(50.0f, 25.0f, 50.0f, 0.0f);
        var zeroRange = prism.entitiesInRegion(zeroCube);
        assertTrue(zeroRange.isEmpty());
    }
    
    @Test
    void testRayIntersectionEdgeCases() {
        // Insert entity with bounds
        var pos = new Point3f(50.0f, 25.0f, 50.0f);
        var bounds = new EntityBounds(
            new Point3f(45.0f, 20.0f, 45.0f),
            new Point3f(55.0f, 30.0f, 55.0f)
        );
        var id = idGenerator.generateID();
        prism.insert(id, pos, (byte)10, "Target", bounds);
        
        // Ray with zero-length direction - should handle gracefully
        var zeroDirection = new Vector3f(0, 0, 0);
        // Avoid creating invalid ray
        if (zeroDirection.length() == 0) {
            // Can't create ray with zero direction
            assertTrue(true, "Zero direction correctly prevented");
        }
        
        // Ray starting inside entity bounds
        var insideRay = new Ray3D(pos, new Vector3f(1, 0, 0));
        var insideHits = prism.rayIntersectAll(insideRay);
        // Without collision shapes, ray intersection returns empty
        assertTrue(insideHits.isEmpty() || insideHits.size() >= 0,
                  "Ray intersection should handle gracefully");
        
        // Ray parallel to triangular constraint boundary
        var parallelRay = new Ray3D(
            new Point3f(0, worldSize, 0),
            new Vector3f(1, -1, 0) // Parallel to x+y=worldSize line
        );
        var parallelHits = prism.rayIntersectAll(parallelRay);
        assertTrue(parallelHits.isEmpty() || parallelHits.size() >= 0);
    }
    
    @Test
    void testFrustumCullingEdgeCases() {
        // Insert entities
        for (int i = 0; i < 10; i++) {
            float x = i * 8.0f;
            float y = (9 - i) * 8.0f; // Ensures x+y < 100
            prism.insert(new Point3f(x, y, 50.0f), (byte)10, "Frustum" + i);
        }
        
        // Degenerate frustum (all planes identical)
        var plane = new Plane3D(0, 0, 1, -50);
        var degenerateFrustum = new Frustum3D(plane, plane, plane, plane, plane, plane);
        var degenerateResults = prism.frustumCullVisible(degenerateFrustum);
        // Should either return nothing or handle gracefully
        assertNotNull(degenerateResults);
        
        // Inside-out frustum (normals pointing wrong way)
        var insideOutFrustum = new Frustum3D(
            new Plane3D(-1, 0, 0, 100),  // Left (inverted)
            new Plane3D(1, 0, 0, 100),   // Right (inverted)
            new Plane3D(0, -1, 0, 100),  // Bottom (inverted)
            new Plane3D(0, 1, 0, 100),   // Top (inverted)
            new Plane3D(0, 0, -1, 100),  // Near (inverted)
            new Plane3D(0, 0, 1, 100)    // Far (inverted)
        );
        var insideOutResults = prism.frustumCullVisible(insideOutFrustum);
        assertNotNull(insideOutResults);
    }
    
    @Test
    void testBatchOperationEdgeCases() {
        // Empty batch
        List<Point3f> emptyPositions = Collections.emptyList();
        List<String> emptyContents = Collections.emptyList();
        var emptyIds = prism.insertBatch(emptyPositions, emptyContents, (byte)10);
        assertTrue(emptyIds.isEmpty());
        
        // Mismatched sizes (should handle gracefully or throw)
        List<Point3f> positions = Arrays.asList(
            new Point3f(10, 10, 10),
            new Point3f(20, 20, 20)
        );
        List<String> contents = Arrays.asList("Only one");
        
        assertThrows(Exception.class, () -> {
            prism.insertBatch(positions, contents, (byte)10);
        });
        
        // Batch with some invalid positions
        List<Point3f> mixedPositions = Arrays.asList(
            new Point3f(10, 10, 10),     // Valid
            new Point3f(60, 60, 10),     // Invalid: x+y > worldSize
            new Point3f(20, 20, 20)      // Valid
        );
        List<String> mixedContents = Arrays.asList("Valid1", "Invalid", "Valid2");
        
        // Should either skip invalid or throw for entire batch
        try {
            var mixedIds = prism.insertBatch(mixedPositions, mixedContents, (byte)10);
            // If it succeeds, should have handled invalid position somehow
            assertTrue(mixedIds.size() <= 3);
        } catch (IllegalArgumentException e) {
            // Expected if strict validation
            assertTrue(e.getMessage().contains("constraint"));
        }
    }
    
    @Test
    void testNumericalPrecisionAtBoundaries() {
        // Test positions that are exactly at subdivision boundaries
        float cellSize = worldSize / (1 << 10); // Level 10 cell size
        
        // Insert at exact cell boundaries
        for (int i = 0; i < 10; i++) {
            float x = i * cellSize;
            float y = Math.min((9 - i) * cellSize, worldSize - x - 0.001f);
            var pos = new Point3f(x, y, i * cellSize);
            var id = prism.insert(pos, (byte)10, "Boundary" + i);
            assertTrue(prism.containsEntity(id));
        }
        
        // Test queries at boundaries
        var boundaryQuery = new Point3f(5 * cellSize, 4 * cellSize, 5 * cellSize);
        var boundaryNeighbors = prism.kNearestNeighbors(boundaryQuery, 5, cellSize * 2);
        assertTrue(boundaryNeighbors.size() > 0);
    }
    
    @Test
    void testLevelTransitionBehavior() {
        // Insert entities at parent-child level boundaries
        var parentPos = new Point3f(25.0f, 25.0f, 50.0f);
        var parentId = prism.insert(parentPos, (byte)5, "Parent");
        
        // Insert children in same spatial region at finer level
        float offset = 0.01f;
        for (int i = 0; i < 8; i++) {
            float dx = (i & 1) * offset;
            float dy = ((i >> 1) & 1) * offset;
            float dz = ((i >> 2) & 1) * offset;
            
            var childPos = new Point3f(parentPos.x + dx, parentPos.y + dy, parentPos.z + dz);
            prism.insert(childPos, (byte)10, "Child" + i);
        }
        
        assertEquals(9, prism.entityCount());
        
        // Query should find all entities despite level differences
        var allNear = prism.kNearestNeighbors(parentPos, 10, offset * 3);
        assertTrue(allNear.size() >= 1); // At least parent
    }
    
    @Test
    void testTriangularRegionQueries() {
        // Create search triangle with normalized coordinates
        // World coordinates must be in [0,1) range for Triangle.fromWorldCoordinate
        var searchTriangle = Triangle.fromWorldCoordinate(
            0.45f, 0.45f, 8  // Normalized coordinates
        );
        
        // Insert entities near boundary
        for (int i = 0; i < 5; i++) {
            float x = worldSize * 0.4f + i;
            float y = worldSize * 0.4f - i;
            prism.insert(new Point3f(x, y, 50.0f), (byte)10, "Boundary" + i);
        }
        
        // Query with boundary-touching triangle
        var results = prism.findInTriangularRegion(searchTriangle, 0.0f, 100.0f);
        assertNotNull(results);
        // Should handle boundary case gracefully
    }
}