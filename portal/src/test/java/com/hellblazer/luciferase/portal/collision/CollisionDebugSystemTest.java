/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.portal.collision;

import com.hellblazer.luciferase.lucien.collision.BoxShape;
import com.hellblazer.luciferase.lucien.collision.SphereShape;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the complete collision debug visualization system.
 * Tests Phase 6: Debug and Visualization Tools implementation.
 *
 * @author hal.hildebrand
 */
public class CollisionDebugSystemTest {
    
    private CollisionVisualizer visualizer;
    private CollisionProfiler profiler;
    private SpatialIndexDebugVisualizer spatialVisualizer;
    private CollisionEventRecorder recorder;
    
    @BeforeEach
    void setUp() {
        visualizer = new CollisionVisualizer();
        profiler = CollisionProfiler.getInstance();
        spatialVisualizer = new SpatialIndexDebugVisualizer();
        recorder = new CollisionEventRecorder();
        
        // Reset profiler state
        profiler.reset();
    }
    
    @Test
    void testCollisionVisualizationSystem() {
        // Test shape visualization
        var sphere = new SphereShape(new Point3f(0, 0, 0), 1.0f);
        var box = new BoxShape(new Point3f(2, 0, 0), new Vector3f(1, 1, 1));
        
        visualizer.addShape(sphere);
        visualizer.addShape(box);
        
        assertEquals(2, visualizer.getShapes().size());
        assertNotNull(visualizer.getRootGroup());
        
        // Test contact point visualization
        var contactPoint = new Point3f(1, 0, 0);
        var normal = new Vector3f(1, 0, 0);
        visualizer.addContact(contactPoint, normal, 0.1f);
        
        assertEquals(1, visualizer.getContacts().size());
        
        // Test property binding
        assertTrue(visualizer.showWireframesProperty().get());
        visualizer.showWireframesProperty().set(false);
        assertFalse(visualizer.showWireframesProperty().get());
    }
    
    @Test
    void testPerformanceProfiling() {
        // Test timing operations
        var context = profiler.startTiming("test_operation");
        
        // Simulate some work
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        context.stop();
        
        var stats = profiler.getTimingStats("test_operation");
        assertNotNull(stats);
        assertEquals(1, stats.getSampleCount());
        assertTrue(stats.getAverageNanos() > 0);
        
        // Test collision pair tracking
        profiler.recordCollisionPair("Sphere", "Box", true);
        profiler.recordCollisionPair("Sphere", "Box", false);
        
        var pairStats = profiler.getCollisionPairStats();
        assertEquals(1, pairStats.pairs().size());
        
        var pair = pairStats.pairs().get(0);
        assertEquals(2, pair.totalTests());
        assertEquals(1, pair.hits());
        assertEquals(0.5, pair.hitRate(), 0.001);
        
        // Test performance summary
        var summary = profiler.getPerformanceSummary();
        assertNotNull(summary);
        assertTrue(summary.totalFrames() >= 0);
        
        // Test report generation
        var report = profiler.generateReport();
        assertNotNull(report);
        assertTrue(report.contains("Performance Report"));
        assertTrue(report.contains("test_operation"));
    }
    
    @Test
    void testSpatialIndexVisualization() {
        // Test entity tracking
        var sphere = new SphereShape(new Point3f(0, 0, 0), 1.0f);
        spatialVisualizer.addCollisionShape(sphere, "entity_1");
        
        assertEquals(1, spatialVisualizer.getEntityEntries().size());
        
        var entity = spatialVisualizer.getEntityEntries().get(0);
        assertEquals("entity_1", entity.entityId);
        assertEquals("SphereShape", entity.type);
        
        // Test collision hotspot recording
        var collisionPoint = new Point3f(1, 1, 1);
        spatialVisualizer.recordCollision(collisionPoint);
        spatialVisualizer.recordCollision(collisionPoint); // Same location twice
        
        assertEquals(1, spatialVisualizer.getHotspotEntries().size());
        var hotspot = spatialVisualizer.getHotspotEntries().get(0);
        assertEquals(2, hotspot.collisionCount);
        
        // Test statistics
        var stats = spatialVisualizer.getStats();
        assertEquals(1, stats.entityCount);
        assertEquals(1, stats.hotspotCount);
        assertEquals(2, stats.totalCollisions);
        
        // Test property bindings
        assertTrue(spatialVisualizer.showEntitiesProperty().get());
        spatialVisualizer.showEntitiesProperty().set(false);
        assertFalse(spatialVisualizer.showEntitiesProperty().get());
    }
    
    @Test
    void testCollisionEventRecording() {
        // Test recording state
        assertFalse(recorder.isRecordingProperty().get());
        
        recorder.isRecordingProperty().set(true);
        assertTrue(recorder.isRecordingProperty().get());
        assertNotNull(recorder.sessionNameProperty().get());
        
        // Test event recording
        var sphere = new SphereShape(new Point3f(0, 0, 0), 1.0f);
        var box = new BoxShape(new Point3f(1, 0, 0), new Vector3f(1, 1, 1));
        var contactPoint = new Point3f(0.5f, 0, 0);
        var normal = new Vector3f(1, 0, 0);
        
        recorder.recordCollision(sphere, box, contactPoint, normal, 0.1f);
        recorder.nextFrame();
        
        assertEquals(1, recorder.getRecordedEvents().size());
        assertEquals(1, recorder.getFrameSnapshots().size());
        
        var event = recorder.getRecordedEvents().get(0);
        assertEquals(0, event.frameNumber); // First frame is 0
        assertEquals(contactPoint, event.contactPoint);
        assertEquals(normal, event.contactNormal);
        assertEquals(0.1f, event.penetrationDepth);
        
        // Test stopping recording
        recorder.isRecordingProperty().set(false);
        assertFalse(recorder.isRecordingProperty().get());
        assertEquals(1, recorder.totalFramesProperty().get());
        
        // Test export
        var exportText = recorder.exportAsText();
        assertNotNull(exportText);
        assertTrue(exportText.contains("Collision Recording Session"));
        assertTrue(exportText.contains("Total Events: 1"));
    }
    
    @Test
    void testIntegratedWorkflow() {
        // Test complete workflow: setup -> record -> profile -> visualize
        
        // 1. Setup collision shapes (clearly overlapping)
        var sphere = new SphereShape(new Point3f(0, 0, 0), 1.0f);
        var box = new BoxShape(new Point3f(0.8f, 0, 0), new Vector3f(1, 1, 1)); // Clearly overlapping
        
        visualizer.addShape(sphere);
        visualizer.addShape(box);
        spatialVisualizer.addCollisionShape(sphere, "sphere_1");
        spatialVisualizer.addCollisionShape(box, "box_1");
        
        // 2. Start recording
        recorder.isRecordingProperty().set(true);
        
        // 3. Simulate collision detection with profiling
        var frameContext = profiler.startFrame();
        
        var detectionContext = profiler.startTiming("collision_detection");
        
        // Simulate collision test
        var collision = sphere.collidesWith(box);
        profiler.recordCollisionPair("SphereShape", "BoxShape", collision.collides);
        
        if (collision.collides) {
            // Record the collision
            recorder.recordCollision(sphere, box, collision.contactPoint, collision.contactNormal, collision.penetrationDepth);
            visualizer.addContact(collision.contactPoint, collision.contactNormal, collision.penetrationDepth);
            spatialVisualizer.recordCollision(collision.contactPoint);
        }
        
        detectionContext.stop();
        
        recorder.nextFrame();
        frameContext.stop();
        
        // 4. Stop recording
        recorder.isRecordingProperty().set(false);
        
        // 5. Verify integrated results
        assertTrue(collision.collides); // Overlapping shapes should collide
        assertEquals(1, visualizer.getContacts().size());
        assertEquals(1, recorder.getRecordedEvents().size());
        assertEquals(1, spatialVisualizer.getHotspotEntries().size());
        
        var pairStats = profiler.getCollisionPairStats();
        assertEquals(1, pairStats.pairs().size());
        assertTrue(pairStats.pairs().get(0).hits() > 0);
        
        // 6. Test replay capability
        recorder.startReplay();
        assertTrue(recorder.isReplayingProperty().get());
        
        recorder.stepReplay();
        recorder.stopReplay();
        assertFalse(recorder.isReplayingProperty().get());
        
        // 7. Generate final reports
        var performanceReport = profiler.generateReport();
        var recordingReport = recorder.exportAsText();
        var spatialStats = spatialVisualizer.getStats();
        
        assertNotNull(performanceReport);
        assertNotNull(recordingReport);
        assertEquals(2, spatialStats.entityCount);
        assertEquals(1, spatialStats.totalCollisions);
    }
    
    @Test
    void testVisualizationProperties() {
        // Test that all major properties are properly exposed and functional
        
        // Collision visualizer properties
        assertNotNull(visualizer.showWireframesProperty());
        assertNotNull(visualizer.showContactPointsProperty());
        assertNotNull(visualizer.wireframeColorProperty());
        assertNotNull(visualizer.vectorScaleProperty());
        
        // Spatial visualizer properties
        assertNotNull(spatialVisualizer.showNodesProperty());
        assertNotNull(spatialVisualizer.showEntitiesProperty());
        assertNotNull(spatialVisualizer.minLevelProperty());
        assertNotNull(spatialVisualizer.nodeOpacityProperty());
        
        // Recorder properties
        assertNotNull(recorder.isRecordingProperty());
        assertNotNull(recorder.currentFrameProperty());
        assertNotNull(recorder.sessionNameProperty());
        
        // Test property changes propagate correctly
        var originalOpacity = spatialVisualizer.nodeOpacityProperty().get();
        spatialVisualizer.nodeOpacityProperty().set(0.8);
        assertEquals(0.8, spatialVisualizer.nodeOpacityProperty().get());
        assertNotEquals(originalOpacity, spatialVisualizer.nodeOpacityProperty().get());
    }
}