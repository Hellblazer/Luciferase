package com.hellblazer.luciferase.portal.web.service;

import com.hellblazer.luciferase.portal.web.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static com.hellblazer.luciferase.portal.web.service.SpatialIndexService.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for coordinate / k / direction validation in SpatialIndexService.
 * Exercises validation guards at all 5 entry points: insertEntity, insertEntities,
 * updateEntity, knnQuery, rayQuery.
 */
class SpatialIndexServiceTest {

    // ===== validateCoords =====

    @Test
    void validateCoords_valid() {
        // Must not throw for finite non-negative values
        assertDoesNotThrow(() -> validateCoords(0f, 0f, 0f));
        assertDoesNotThrow(() -> validateCoords(0.5f, 1.0f, 100.0f));
    }

    @Test
    void validateCoords_nanX() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> validateCoords(Float.NaN, 0f, 0f));
        assertTrue(ex.getMessage().contains("finite"), ex.getMessage());
    }

    @Test
    void validateCoords_nanY() {
        assertThrows(IllegalArgumentException.class, () -> validateCoords(0f, Float.NaN, 0f));
    }

    @Test
    void validateCoords_nanZ() {
        assertThrows(IllegalArgumentException.class, () -> validateCoords(0f, 0f, Float.NaN));
    }

    @Test
    void validateCoords_positiveInfinityX() {
        assertThrows(IllegalArgumentException.class,
                () -> validateCoords(Float.POSITIVE_INFINITY, 0f, 0f));
    }

    @Test
    void validateCoords_negativeInfinityY() {
        assertThrows(IllegalArgumentException.class,
                () -> validateCoords(0f, Float.NEGATIVE_INFINITY, 0f));
    }

    @ParameterizedTest
    @ValueSource(floats = { -0.001f, -1f, -Float.MAX_VALUE })
    void validateCoords_negativeX(float x) {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> validateCoords(x, 0f, 0f));
        assertTrue(ex.getMessage().contains("non-negative"), ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(floats = { -0.001f, -1f })
    void validateCoords_negativeY(float y) {
        assertThrows(IllegalArgumentException.class, () -> validateCoords(0f, y, 0f));
    }

    @ParameterizedTest
    @ValueSource(floats = { -0.001f, -1f })
    void validateCoords_negativeZ(float z) {
        assertThrows(IllegalArgumentException.class, () -> validateCoords(0f, 0f, z));
    }

    // ===== validateK =====

    @Test
    void validateK_valid() {
        assertDoesNotThrow(() -> validateK(1));
        assertDoesNotThrow(() -> validateK(MAX_K));
    }

    @Test
    void validateK_zero() {
        assertThrows(IllegalArgumentException.class, () -> validateK(0));
    }

    @Test
    void validateK_negative() {
        assertThrows(IllegalArgumentException.class, () -> validateK(-1));
    }

    @Test
    void validateK_tooLarge() {
        var ex = assertThrows(IllegalArgumentException.class, () -> validateK(MAX_K + 1));
        assertTrue(ex.getMessage().contains(String.valueOf(MAX_K)), ex.getMessage());
    }

    // ===== validateDirection =====

    @Test
    void validateDirection_valid() {
        assertDoesNotThrow(() -> validateDirection(1f, 0f, 0f));
        assertDoesNotThrow(() -> validateDirection(0f, 0f, -1f));
        assertDoesNotThrow(() -> validateDirection(0.577f, 0.577f, 0.577f));
    }

    @Test
    void validateDirection_allZero() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> validateDirection(0f, 0f, 0f));
        assertTrue(ex.getMessage().contains("non-zero"), ex.getMessage());
    }

    @Test
    void validateDirection_nanComponent() {
        assertThrows(IllegalArgumentException.class,
                () -> validateDirection(Float.NaN, 0f, 1f));
    }

    @Test
    void validateDirection_infiniteComponent() {
        assertThrows(IllegalArgumentException.class,
                () -> validateDirection(Float.POSITIVE_INFINITY, 0f, 1f));
    }

    // ===== Service-level integration: invalid coords rejected at each entry point =====

    private SpatialIndexService serviceWithOctree(String sessionId) {
        var service = new SpatialIndexService();
        var req = new CreateIndexRequest(SpatialIndexService.IndexType.OCTREE, (byte) 10, 10);
        service.createIndex(sessionId, req);
        return service;
    }

    @Test
    void insertEntity_rejectsNaN() {
        var service = serviceWithOctree("s1");
        var req = new InsertEntityRequest(Float.NaN, 0f, 0f, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.insertEntity("s1", req));
        // Counter must not be incremented on rejection
        assertEquals(0, service.sessionEntityCount("s1"),
                "Entity counter must not advance on rejected insert");
    }

    @Test
    void insertEntity_rejectsNegative() {
        var service = serviceWithOctree("s2");
        var req = new InsertEntityRequest(-1f, 0f, 0f, Map.of());
        assertThrows(IllegalArgumentException.class, () -> service.insertEntity("s2", req));
        assertEquals(0, service.sessionEntityCount("s2"));
    }

    @Test
    void insertEntities_rejectsNaNInBatch() {
        var service = serviceWithOctree("s3");
        var requests = List.of(
                new InsertEntityRequest(0.1f, 0.1f, 0.1f, null),
                new InsertEntityRequest(Float.NaN, 0.5f, 0.5f, null)
        );
        assertThrows(IllegalArgumentException.class,
                () -> service.insertEntities("s3", requests));
        assertEquals(0, service.sessionEntityCount("s3"),
                "Counter must not advance when bulk insert fails validation");
    }

    @Test
    void updateEntity_rejectsNaN() {
        var service = serviceWithOctree("s4");
        // Insert valid entity first
        var insert = new InsertEntityRequest(0.5f, 0.5f, 0.5f, null);
        var info = service.insertEntity("s4", insert);
        // Now update with NaN
        var update = new UpdateEntityRequest(info.entityId(), Float.NaN, 0.5f, 0.5f);
        assertThrows(IllegalArgumentException.class, () -> service.updateEntity("s4", update));
    }

    @Test
    void knnQuery_rejectsNaN() {
        var service = serviceWithOctree("s5");
        var req = new KnnQueryRequest(Float.NaN, 0f, 0f, 5, null);
        assertThrows(IllegalArgumentException.class, () -> service.knnQuery("s5", req));
    }

    @Test
    void knnQuery_rejectsZeroK() {
        var service = serviceWithOctree("s6");
        var req = new KnnQueryRequest(0.5f, 0.5f, 0.5f, 0, null);
        assertThrows(IllegalArgumentException.class, () -> service.knnQuery("s6", req));
    }

    @Test
    void knnQuery_rejectsNegativeK() {
        var service = serviceWithOctree("s7");
        var req = new KnnQueryRequest(0.5f, 0.5f, 0.5f, -1, null);
        assertThrows(IllegalArgumentException.class, () -> service.knnQuery("s7", req));
    }

    @Test
    void knnQuery_rejectsExcessiveK() {
        var service = serviceWithOctree("s8");
        var req = new KnnQueryRequest(0.5f, 0.5f, 0.5f, MAX_K + 1, null);
        assertThrows(IllegalArgumentException.class, () -> service.knnQuery("s8", req));
    }

    @Test
    void rayQuery_rejectsNaNOrigin() {
        var service = serviceWithOctree("s9");
        var req = new RayQueryRequest(Float.NaN, 0f, 0f, 0f, 0f, 1f, null);
        assertThrows(IllegalArgumentException.class, () -> service.rayQuery("s9", req));
    }

    @Test
    void rayQuery_rejectsZeroDirection() {
        var service = serviceWithOctree("s10");
        var req = new RayQueryRequest(0.5f, 0.5f, 0.5f, 0f, 0f, 0f, null);
        assertThrows(IllegalArgumentException.class, () -> service.rayQuery("s10", req));
    }

    @Test
    void rayQuery_rejectsNaNDirection() {
        var service = serviceWithOctree("s11");
        var req = new RayQueryRequest(0.5f, 0.5f, 0.5f, Float.NaN, 0f, 1f, null);
        assertThrows(IllegalArgumentException.class, () -> service.rayQuery("s11", req));
    }

    @Test
    void validCoords_insertSucceeds() {
        var service = serviceWithOctree("s12");
        var req = new InsertEntityRequest(0.5f, 0.5f, 0.5f, null);
        assertDoesNotThrow(() -> service.insertEntity("s12", req));
        assertEquals(1, service.sessionEntityCount("s12"));
    }

    // ===== rayQuery origin validation =====

    /**
     * Ray origins may be anywhere in 3D space — negative coordinates must be accepted.
     * Regression test for the bug where validateCoords (non-negative guard) was
     * incorrectly applied to ray origins instead of the finite-only check.
     */
    @Test
    void rayQuery_acceptsNegativeOrigin() {
        var service = serviceWithOctree("s13");
        // origin (-5,-5,-5), direction (1,0,0) — valid ray from a negative-coordinate camera
        var req = new RayQueryRequest(-5f, -5f, -5f, 1f, 0f, 0f, null);
        assertDoesNotThrow(() -> service.rayQuery("s13", req),
                "Ray with negative origin must be accepted (only entities require non-negative coords)");
    }

    @Test
    void rayQuery_rejectsNaNOriginCoord() {
        var service = serviceWithOctree("s14");
        var req = new RayQueryRequest(Float.NaN, -5f, -5f, 1f, 0f, 0f, null);
        assertThrows(IllegalArgumentException.class, () -> service.rayQuery("s14", req),
                "Ray origin with NaN coordinate must be rejected");
    }
}
