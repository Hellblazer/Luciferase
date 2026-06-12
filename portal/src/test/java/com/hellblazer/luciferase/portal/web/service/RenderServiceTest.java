// SPDX-License-Identifier: AGPL-3.0-only
package com.hellblazer.luciferase.portal.web.service;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.portal.esvt.bridge.ESVTBridge;
import com.hellblazer.luciferase.portal.inspector.SpatialBridge;
import com.hellblazer.luciferase.portal.web.dto.CreateRenderRequest;
import com.hellblazer.luciferase.portal.web.dto.CreateRenderRequest.RenderType;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RenderService build paths, in particular the BuildResult success-guard
 * (Luciferase-x5zcl): a build failure must surface as a clean {@link RenderBuildException},
 * not an uncaught builder exception, and must not leave partial render state behind.
 */
class RenderServiceTest {

    private static final String SESSION = "test-session";

    private static Octree<UUIDEntityID, Object> octreeWithEntities() {
        var octree = new Octree<UUIDEntityID, Object>(UUIDEntityID::new, 10, (byte) 10);
        octree.insert(new Point3f(0.25f, 0.25f, 0.25f), (byte) 10, Map.of());
        octree.insert(new Point3f(0.5f, 0.5f, 0.5f), (byte) 10, Map.of());
        octree.insert(new Point3f(0.75f, 0.75f, 0.75f), (byte) 10, Map.of());
        return octree;
    }

    private static Tetree<UUIDEntityID, Object> tetreeWithEntities() {
        var tetree = new Tetree<UUIDEntityID, Object>(UUIDEntityID::new, 10, (byte) 10);
        tetree.insert(new Point3f(0.25f, 0.25f, 0.25f), (byte) 10, Map.of());
        tetree.insert(new Point3f(0.5f, 0.5f, 0.5f), (byte) 10, Map.of());
        tetree.insert(new Point3f(0.75f, 0.75f, 0.75f), (byte) 10, Map.of());
        return tetree;
    }

    /** Bridge stub whose build always fails with a BuildResult failure (never throws). */
    private static <D extends com.hellblazer.luciferase.render.inspector.SpatialData> SpatialBridge<D> failingBridge(
            String typeName) {
        return new SpatialBridge<>() {
            @Override
            public BuildResult<D> buildFromVoxels(List<Point3i> voxels, int maxDepth, int gridResolution) {
                return BuildResult.failure(1, voxels != null ? voxels.size() : 0, "injected failure");
            }

            @Override
            public String getStructureTypeName() {
                return typeName;
            }
        };
    }

    private static RenderService withFailingBridges() {
        Supplier<SpatialBridge<ESVTData>> esvt = () -> failingBridge("ESVT");
        Supplier<SpatialBridge<ESVOOctreeData>> esvo = () -> failingBridge("Octree");
        return new RenderService(esvt, esvo);
    }

    // ===== Failure path (the .220 scope gap) =====

    @Test
    void esvtBuildFailureThrowsRenderBuildExceptionNotRawThrow() {
        var service = withFailingBridges();
        var request = new CreateRenderRequest(RenderType.ESVT, 5, 16);

        var ex = assertThrows(RenderBuildException.class,
                              () -> service.createRender(SESSION, tetreeWithEntities(), request));
        assertTrue(ex.getMessage().contains("injected failure"),
                   "Failure message from BuildResult must be propagated, got: " + ex.getMessage());
    }

    @Test
    void esvoBuildFailureThrowsRenderBuildExceptionNotRawThrow() {
        var service = withFailingBridges();
        var request = new CreateRenderRequest(RenderType.ESVO, 5, 16);

        var ex = assertThrows(RenderBuildException.class,
                              () -> service.createRender(SESSION, octreeWithEntities(), request));
        assertTrue(ex.getMessage().contains("injected failure"),
                   "Failure message from BuildResult must be propagated, got: " + ex.getMessage());
    }

    /**
     * Same-instance recovery: a transient build failure must not poison the service —
     * the SAME RenderService must accept a retry for the same session once the build
     * can complete. The flaky bridge fails the first build, then delegates to a real
     * ESVTBridge.
     */
    @Test
    void buildFailureLeavesNoRenderStateAndSameServiceCanRetry() {
        var failedOnce = new AtomicBoolean(false);
        Supplier<SpatialBridge<ESVTData>> flaky = () -> new SpatialBridge<>() {
            @Override
            public BuildResult<ESVTData> buildFromVoxels(List<Point3i> voxels, int maxDepth, int gridResolution) {
                if (failedOnce.compareAndSet(false, true)) {
                    return BuildResult.failure(1, voxels != null ? voxels.size() : 0, "injected failure");
                }
                return new ESVTBridge().buildFromVoxels(voxels, maxDepth, gridResolution);
            }

            @Override
            public String getStructureTypeName() {
                return "ESVT";
            }
        };
        var service = new RenderService(flaky, () -> failingBridge("Octree"));
        var request = new CreateRenderRequest(RenderType.ESVT, 5, 16);

        assertThrows(RenderBuildException.class,
                     () -> service.createRender(SESSION, tetreeWithEntities(), request));
        assertFalse(service.hasRender(SESSION),
                    "Failed build must not leave a render holder for the session");

        // Same service, same session: retry must succeed once the build can complete
        var info = service.createRender(SESSION, tetreeWithEntities(), request);
        assertNotNull(info);
        assertTrue(service.hasRender(SESSION));
    }

    /**
     * Real-path regression: ESVOBridge validates maxDepth 1..15 inside the guard, so an
     * out-of-range depth must surface as RenderBuildException — previously the raw
     * OctreeBuilder path threw an unguarded exception through to the HTTP handler.
     */
    @Test
    void realEsvoBridgeRejectsOutOfRangeDepthAsBuildFailure() {
        var service = new RenderService();
        var request = new CreateRenderRequest(RenderType.ESVO, 99, 16);

        assertThrows(RenderBuildException.class,
                     () -> service.createRender(SESSION, octreeWithEntities(), request));
        assertFalse(service.hasRender(SESSION));
    }

    /**
     * Real-path regression for ESVT: ESVTBridge validates maxDepth 1..21 inside the
     * guard (Tetree supports 21 levels; ESVTTraversal.MAX_DEPTH=22 is the scale domain),
     * so an out-of-range depth must surface as RenderBuildException.
     */
    @Test
    void realEsvtBridgeRejectsOutOfRangeDepthAsBuildFailure() {
        var service = new RenderService();
        var request = new CreateRenderRequest(RenderType.ESVT, 99, 16);

        assertThrows(RenderBuildException.class,
                     () -> service.createRender(SESSION, tetreeWithEntities(), request));
        assertFalse(service.hasRender(SESSION));
    }

    /**
     * TOCTOU regression (Luciferase-lc06v): two concurrent createRender calls for the
     * same session must admit exactly one — the loser gets IllegalStateException and
     * must not overwrite the winner's holder. The barrier inside the bridge guarantees
     * both threads pass the exists-check before either stores its result.
     */
    @Test
    void concurrentCreateForSameSessionAdmitsExactlyOne() throws Exception {
        // Both threads' bridge instances share this one barrier via closure — that's the
        // intent: neither may store its result until both are past the exists-check.
        var barrier = new CyclicBarrier(2);
        Supplier<SpatialBridge<ESVTData>> blocking = () -> new SpatialBridge<>() {
            @Override
            public BuildResult<ESVTData> buildFromVoxels(List<Point3i> voxels, int maxDepth, int gridResolution) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return new ESVTBridge().buildFromVoxels(voxels, maxDepth, gridResolution);
            }

            @Override
            public String getStructureTypeName() {
                return "ESVT";
            }
        };
        var service = new RenderService(blocking, () -> failingBridge("Octree"));
        var request = new CreateRenderRequest(RenderType.ESVT, 5, 16);
        var tetree = tetreeWithEntities();

        var successes = new AtomicInteger();
        var conflicts = new AtomicInteger();
        Runnable create = () -> {
            try {
                service.createRender(SESSION, tetree, request);
                successes.incrementAndGet();
            } catch (IllegalStateException e) {
                conflicts.incrementAndGet();
            }
        };
        var t1 = new Thread(create);
        var t2 = new Thread(create);
        t1.start();
        t2.start();
        t1.join(10_000);
        t2.join(10_000);
        assertFalse(t1.isAlive(), "t1 must complete within join timeout — barrier or build may be hung");
        assertFalse(t2.isAlive(), "t2 must complete within join timeout — barrier or build may be hung");

        assertEquals(1, successes.get(), "Exactly one concurrent create must win");
        assertEquals(1, conflicts.get(), "The losing create must get IllegalStateException");
        assertTrue(service.hasRender(SESSION));
    }

    // ===== Success path must be unchanged through the bridges =====

    @Test
    void esvoSuccessPathBuildsThroughBridge() {
        var service = new RenderService();
        var request = new CreateRenderRequest(RenderType.ESVO, 5, 16);

        var info = service.createRender(SESSION, octreeWithEntities(), request);
        assertEquals("esvo", info.type());
        assertTrue(info.nodeCount() > 0, "ESVO build must produce nodes");
        assertTrue(service.hasRender(SESSION));
    }

    @Test
    void esvtSuccessPathBuildsThroughBridge() {
        var service = new RenderService();
        var request = new CreateRenderRequest(RenderType.ESVT, 5, 16);

        var info = service.createRender(SESSION, tetreeWithEntities(), request);
        assertEquals("esvt", info.type());
        assertTrue(info.nodeCount() > 0, "ESVT build must produce nodes");
        assertTrue(service.hasRender(SESSION));
        assertNotNull(service.getESVTData(SESSION), "ESVT data must be available for GPU rendering");
    }
}
