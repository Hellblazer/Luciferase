package com.hellblazer.luciferase.portal.web.service;

import com.hellblazer.luciferase.lucien.BulkOperationConfig;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.collision.CollisionShape;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.visitor.TraversalStrategy;
import com.hellblazer.luciferase.lucien.visitor.TreeVisitor;
import com.hellblazer.luciferase.portal.web.dto.CreateIndexRequest;
import com.hellblazer.luciferase.portal.web.dto.InsertEntityRequest;
import com.hellblazer.luciferase.portal.web.dto.UpdateEntityRequest;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3i;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit tests for SpatialIndexService entity counter invariants.
 *
 * H1: a RuntimeException from insertBatch / insert (after reserveEntitySlots succeeded)
 *     must release the reserved slots so the counter is unchanged.
 *
 * H2: updateEntity is counter-neutral — repeated updates must not drift the counter
 *     down, and a session at cap must still accept updates (net-zero operation).
 */
class SpatialIndexServiceCounterTest {

    private static final String SESSION = "test-session";
    private static final CreateIndexRequest OCTREE_REQ =
            new CreateIndexRequest(SpatialIndexService.IndexType.OCTREE, (byte) 10, 10);

    // ── Helpers ─────────────────────────────────────────────────────────────

    private SpatialIndexService serviceWithCap(int cap) {
        var svc = new SpatialIndexService(cap);
        svc.createIndex(SESSION, OCTREE_REQ);
        return svc;
    }

    private static InsertEntityRequest req(float x) {
        return new InsertEntityRequest(x, 0f, 0f, null);
    }

    // ── H1: insert failure releases reserved slot ────────────────────────────

    /**
     * After reserveEntitySlots succeeds but the underlying index.insert() throws,
     * the session entity counter must be unchanged (no slot leak).
     * A subsequent within-cap insert must still succeed.
     */
    @Test
    void h1_singleInsertFailureReleasesSlot() {
        var svc = serviceWithCap(3);

        // Pre-insert 1 entity so counter = 1
        svc.insertEntity(SESSION, req(0.1f));
        assertEquals(1, svc.sessionEntityCount(SESSION), "pre-condition: counter must be 1");

        // Replace the real index with one that always throws on insert
        svc.testOnlyReplaceIndex(SESSION, new ThrowingIndex());

        // Single-insert path — must throw and leave counter at 1
        assertThrows(RuntimeException.class, () -> svc.insertEntity(SESSION, req(0.5f)));
        assertEquals(1, svc.sessionEntityCount(SESSION),
                     "H1 single-insert: counter must be unchanged after index.insert() throw");

        // Restore a working index — a subsequent insert within cap must succeed
        svc.testOnlyReplaceIndex(SESSION, new Octree<>(UUIDEntityID::new, 10, (byte) 10));
        assertDoesNotThrow(() -> svc.insertEntity(SESSION, req(0.6f)));
        assertEquals(2, svc.sessionEntityCount(SESSION),
                     "H1 single-insert: post-fix insert must increment counter normally");
    }

    /**
     * After reserveEntitySlots succeeds for a bulk insert but insertBatch() throws,
     * ALL reserved slots (requests.size()) must be released.
     * A subsequent within-cap insert must still succeed.
     */
    @Test
    void h1_bulkInsertFailureReleasesAllReservedSlots() {
        var svc = serviceWithCap(5);

        // Pre-insert 1 entity so counter = 1
        svc.insertEntity(SESSION, req(0.1f));
        assertEquals(1, svc.sessionEntityCount(SESSION), "pre-condition: counter must be 1");

        // Replace the real index with one that always throws on insertBatch
        svc.testOnlyReplaceIndex(SESSION, new ThrowingIndex());

        // Bulk-insert of 3 (would bring total to 4, within cap=5) — must throw from the index
        var batch = List.of(req(0.2f), req(0.3f), req(0.4f));
        assertThrows(RuntimeException.class, () -> svc.insertEntities(SESSION, batch));

        // All 3 reserved slots must have been released — counter stays at 1
        assertEquals(1, svc.sessionEntityCount(SESSION),
                     "H1 bulk-insert: all " + batch.size() + " reserved slots must be released on throw");

        // Restore a working index — a within-cap bulk insert must succeed
        svc.testOnlyReplaceIndex(SESSION, new Octree<>(UUIDEntityID::new, 10, (byte) 10));
        assertDoesNotThrow(() -> svc.insertEntities(SESSION, batch));
        assertEquals(4, svc.sessionEntityCount(SESSION),
                     "H1 bulk-insert: post-fix insert must increment counter normally");
    }

    // ── H2: updateEntity is counter-neutral ─────────────────────────────────

    /**
     * Repeated updateEntity calls must keep the counter stable.
     * Pre-bug: each call decremented without re-incrementing (remove -1, re-insert +0 = net -1).
     */
    @Test
    void h2_repeatedUpdateKeepsCounterStable() {
        var svc = serviceWithCap(3);

        // Insert 2 entities
        var info1 = svc.insertEntity(SESSION, req(0.1f));
        svc.insertEntity(SESSION, req(0.2f));
        assertEquals(2, svc.sessionEntityCount(SESSION), "pre-condition: counter must be 2");

        var id1 = info1.entityId();

        // Perform several updates on entity 1 — counter must stay at 2
        for (int i = 0; i < 5; i++) {
            svc.updateEntity(SESSION, new UpdateEntityRequest(id1, i * 0.01f, 0f, 0f));
            assertEquals(2, svc.sessionEntityCount(SESSION),
                         "H2: counter must remain 2 after update #" + (i + 1));
        }
    }

    /**
     * A session at cap must still allow an updateEntity call (net-zero operation).
     * The remove frees one slot before reserveEntitySlots checks the cap, so an
     * in-place move can never be spuriously rejected with 413.
     */
    @Test
    void h2_updateAllowedWhenSessionAtCap() {
        // cap = 2 so we fill it quickly
        var svc = serviceWithCap(2);

        var info1 = svc.insertEntity(SESSION, req(0.1f));
        svc.insertEntity(SESSION, req(0.2f));
        assertEquals(2, svc.sessionEntityCount(SESSION), "pre-condition: session must be at cap");

        // Update (net-zero) must not throw EntityCapExceededException
        assertDoesNotThrow(() -> svc.updateEntity(SESSION,
                                                  new UpdateEntityRequest(info1.entityId(), 0.9f, 0f, 0f)),
                           "H2: updateEntity on a full session must not throw EntityCapExceededException");
        assertEquals(2, svc.sessionEntityCount(SESSION),
                     "H2: counter must remain at cap after update");

        // A NEW insert must still be rejected
        assertThrows(EntityCapExceededException.class,
                     () -> svc.insertEntity(SESSION, req(0.99f)),
                     "H2: new insert must still be rejected when session is at cap");
    }

    // ── ThrowingIndex stub ──────────────────────────────────────────────────

    /**
     * A minimal SpatialIndex stub that throws RuntimeException on insert and insertBatch.
     * All other methods return safe empty values (they are not exercised in the counter tests).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class ThrowingIndex
            implements SpatialIndex<MortonKey, UUIDEntityID, Object> {

        private static final RuntimeException FAIL =
                new RuntimeException("injected index failure for counter test");

        // ---- Throwing paths (the ones the H1 fix guards) ----

        @Override
        public UUIDEntityID insert(Point3f position, byte level, Object content) {
            throw FAIL;
        }

        @Override
        public void insert(UUIDEntityID entityId, Point3f position, byte level, Object content) {
            throw FAIL;
        }

        @Override
        public void insert(UUIDEntityID entityId, Point3f position, byte level, Object content,
                           EntityBounds bounds) {
            throw FAIL;
        }

        @Override
        public List<UUIDEntityID> insertBatch(List<Point3f> positions, List<Object> contents,
                                              byte level) {
            throw FAIL;
        }

        @Override
        public List<UUIDEntityID> insertBatchWithSpanning(List<EntityBounds> bounds,
                                                          List<Object> contents, byte level) {
            throw FAIL;
        }

        // ---- Safe no-op stubs for all remaining abstract interface methods ----

        @Override public boolean removeEntity(UUIDEntityID entityId)          { return false; }
        @Override public boolean containsEntity(UUIDEntityID entityId)        { return false; }
        @Override public Object getEntity(UUIDEntityID entityId)              { return null; }
        @Override public Point3f getEntityPosition(UUIDEntityID entityId)     { return null; }
        @Override public int entityCount()                                     { return 0; }
        @Override public int nodeCount()                                       { return 0; }
        @Override public void clear()                                          {}
        @Override public void enableBulkLoading()                             {}
        @Override public void finalizeBulkLoading()                           {}
        @Override public void configureBulkOperations(BulkOperationConfig c)  {}
        @Override public boolean hasNode(MortonKey key)                       { return false; }
        @Override public void updateEntity(UUIDEntityID id, Point3f pos, byte l) {}
        @Override public void setCollisionShape(UUIDEntityID id, CollisionShape s) {}
        @Override public CollisionShape getCollisionShape(UUIDEntityID id)    { return null; }
        @Override public EntityBounds getEntityBounds(UUIDEntityID id)        { return null; }
        @Override public int getEntitySpanCount(UUIDEntityID id)              { return 0; }
        @Override public SpatialIndex.EntityStats getStats()                  { return null; }
        @Override public Map<UUIDEntityID, Point3f> getEntitiesWithPositions(){ return Map.of(); }
        @Override public List<UUIDEntityID> entitiesInRegion(Spatial.Cube r)  { return List.of(); }
        @Override public List<UUIDEntityID> kNearestNeighbors(Point3f p, int k, float d) { return List.of(); }
        @Override public List<SpatialIndex.RayIntersection<UUIDEntityID, Object>> rayIntersectAll(com.hellblazer.luciferase.lucien.Ray3D ray) { return List.of(); }
        @Override public Optional<SpatialIndex.RayIntersection<UUIDEntityID, Object>> rayIntersectFirst(com.hellblazer.luciferase.lucien.Ray3D ray) { return Optional.empty(); }
        @Override public List<SpatialIndex.RayIntersection<UUIDEntityID, Object>> rayIntersectWithin(com.hellblazer.luciferase.lucien.Ray3D ray, float d) { return List.of(); }
        @Override public List<UUIDEntityID> frustumCullVisible(Frustum3D f)   { return List.of(); }
        @Override public List<Object> getEntities(List<UUIDEntityID> ids)     { return List.of(); }
        @Override public List<SpatialIndex.CollisionPair<UUIDEntityID, Object>> findAllCollisions() { return List.of(); }
        @Override public List<SpatialIndex.CollisionPair<UUIDEntityID, Object>> findCollisions(UUIDEntityID id) { return List.of(); }
        @Override public List<SpatialIndex.CollisionPair<UUIDEntityID, Object>> findCollisionsInRegion(com.hellblazer.luciferase.lucien.Spatial r) { return List.of(); }
        @Override public Optional<SpatialIndex.CollisionPair<UUIDEntityID, Object>> checkCollision(UUIDEntityID a, UUIDEntityID b) { return Optional.empty(); }
        @Override public Stream<SpatialIndex.SpatialNode<MortonKey, UUIDEntityID>> boundedBy(com.hellblazer.luciferase.lucien.Spatial v) { return Stream.empty(); }
        @Override public Stream<SpatialIndex.SpatialNode<MortonKey, UUIDEntityID>> bounding(com.hellblazer.luciferase.lucien.Spatial v) { return Stream.empty(); }
        @Override public Stream<SpatialIndex.SpatialNode<MortonKey, UUIDEntityID>> nodes() { return Stream.empty(); }
        @Override public SpatialIndex.SpatialNode<MortonKey, UUIDEntityID> enclosing(com.hellblazer.luciferase.lucien.Spatial v) { return null; }
        @Override public SpatialIndex.SpatialNode<MortonKey, UUIDEntityID> enclosing(Tuple3i p, byte l) { return null; }
        @Override public List<UUIDEntityID> lookup(Point3f p, byte l)        { return List.of(); }
        @Override public void traverse(TreeVisitor<MortonKey, UUIDEntityID, Object> v, TraversalStrategy s) {}
        @Override public void traverseFrom(TreeVisitor<MortonKey, UUIDEntityID, Object> v, TraversalStrategy s, MortonKey k) {}
        @Override public void traverseRegion(TreeVisitor<MortonKey, UUIDEntityID, Object> v, com.hellblazer.luciferase.lucien.Spatial r, TraversalStrategy s) {}
    }
}
