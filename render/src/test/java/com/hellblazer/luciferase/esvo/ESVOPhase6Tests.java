package com.hellblazer.luciferase.esvo;

import com.hellblazer.luciferase.esvo.app.*;
import com.hellblazer.luciferase.esvo.core.*;
import com.hellblazer.luciferase.esvo.io.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import javax.vecmath.Vector3f;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 tests for ESVO Application Integration
 */
public class ESVOPhase6Tests {

    @TempDir
    Path tempDir;
    
    private ESVOApplication app;
    private ESVOScene scene;
    private ESVOCamera camera;
    
    @BeforeEach
    void setUp() {
        // Initialize application components
        app = new ESVOApplication();
        scene = new ESVOScene();
        camera = new ESVOCamera();
    }
    
    @Test
    void testApplicationLifecycle() {
        // Test application can be initialized and shut down cleanly
        assertNotNull(app);
        
        // Initialize
        app.initialize();
        assertTrue(app.isInitialized());
        
        // Shutdown
        app.shutdown();
        assertFalse(app.isInitialized());
    }
    
    @Test
    void testSceneManagement() throws IOException {
        // Create a simple octree for testing
        ESVOOctreeData octree = new ESVOOctreeData(1024);
        ESVOOctreeNode rootNode = new ESVOOctreeNode((byte)0xFF, 0x12345678, 0);
        octree.setNode(0, rootNode);
        
        // Test scene creation and octree loading
        scene.loadOctree("test_octree", octree);
        assertEquals(1, scene.getOctreeCount());
        assertTrue(scene.hasOctree("test_octree"));
        
        // Test scene clearing
        scene.clear();
        assertEquals(0, scene.getOctreeCount());
        assertFalse(scene.hasOctree("test_octree"));
    }
    
    @Test
    void testSceneFileOperations() throws IOException {
        // Create test octree file
        ESVOOctreeData octree = new ESVOOctreeData(512);
        for (int i = 0; i < 10; i++) {
            ESVOOctreeNode node = new ESVOOctreeNode((byte)i, i * 100, 0);
            octree.setNode(i, node);
        }
        
        // Serialize to file
        Path octreeFile = tempDir.resolve("scene_octree.esvo");
        ESVOSerializer serializer = new ESVOSerializer();
        serializer.serialize(octree, octreeFile);
        
        // Test loading from file
        scene.loadOctreeFromFile("file_octree", octreeFile);
        assertTrue(scene.hasOctree("file_octree"));
        
        ESVOOctreeData loaded = scene.getOctree("file_octree");
        assertNotNull(loaded);
        
        // Verify loaded data
        ESVOOctreeNode node0 = loaded.getNode(0);
        assertEquals((byte)0, node0.childMask);
        assertEquals(0, node0.contour);
    }
    
    @Test
    void testCameraControls() {
        // Test initial camera state
        Vector3f initialPos = camera.getPosition();
        assertNotNull(initialPos);
        
        Vector3f initialDir = camera.getForward();
        assertNotNull(initialDir);
        
        // Test camera movement
        Vector3f newPos = new Vector3f(5.0f, 3.0f, 2.0f);
        camera.setPosition(newPos);
        
        Vector3f currentPos = camera.getPosition();
        assertEquals(5.0f, currentPos.x, 0.001f);
        assertEquals(3.0f, currentPos.y, 0.001f);
        assertEquals(2.0f, currentPos.z, 0.001f);
        
        // Test camera rotation
        camera.rotate(0.5f, 0.3f); // yaw, pitch
        Vector3f newDir = camera.getForward();
        assertNotEquals(initialDir, newDir);
    }
    
    @Test
    void testCameraViewMatrix() {
        camera.setPosition(new Vector3f(1.0f, 2.0f, 3.0f));
        camera.setOrientation(0.0f, 0.0f); // Set yaw and pitch instead
        
        var viewMatrix = camera.getViewMatrix();
        assertNotNull(viewMatrix);
        
        // Test projection matrix
        camera.setFieldOfView(60.0f);
        camera.setClippingPlanes(0.1f, 100.0f);
        camera.updateProjectionMatrix(16.0f/9.0f);
        
        var projMatrix = camera.getProjectionMatrix();
        assertNotNull(projMatrix);
    }
    
    @Test
    void testRealTimeUpdates() throws InterruptedException {
        // Test real-time octree updates
        ESVOOctreeData octree = new ESVOOctreeData(256);
        scene.loadOctree("dynamic", octree);
        
        // Set up update callback
        CountDownLatch updateLatch = new CountDownLatch(1);
        scene.setUpdateCallback(() -> updateLatch.countDown());
        
        // Modify octree
        ESVOOctreeNode newNode = new ESVOOctreeNode((byte)0x42, 0xABCDEF, 123);
        octree.setNode(5, newNode);
        scene.markDirty("dynamic");
        
        // Wait for update notification
        assertTrue(updateLatch.await(1, TimeUnit.SECONDS));
        
        // Verify change was applied
        ESVOOctreeNode retrieved = scene.getOctree("dynamic").getNode(5);
        assertEquals((byte)0x42, retrieved.childMask);
        assertEquals(0xABCDEF, retrieved.contour);
        assertEquals(123, retrieved.farPointer);
    }
    
    @Test
    void testPerformanceMonitoring() {
        ESVOPerformanceMonitor monitor = new ESVOPerformanceMonitor();
        
        // Test frame rate tracking
        monitor.startFrame();
        // Simulate some work
        try { Thread.sleep(16); } catch (InterruptedException e) {}
        monitor.startFrame(); // Start next frame to trigger calculation
        
        double fps = monitor.getCurrentFPS();
        assertTrue(fps >= 0);
        assertTrue(fps < 1000); // Reasonable upper bound
        
        // Test traversal statistics
        monitor.recordTraversal(1000000, 5000000, 800000);
        assertEquals(1000000, monitor.getTotalRaysTraced());
        assertEquals(5000000, monitor.getTotalNodesVisited());
        assertEquals(800000, monitor.getTotalVoxelsHit());
        assertEquals(5.0, monitor.getAverageNodesPerRay(), 0.001);
        assertEquals(80.0, monitor.getVoxelHitRate(), 0.001);
    }
    
    @Test
    void testSceneBounds() {
        // Test automatic bounding box calculation
        ESVOOctreeData octree = new ESVOOctreeData(512);
        
        // Add nodes at known positions
        ESVOOctreeNode node1 = new ESVOOctreeNode((byte)1, 100, 0);
        ESVOOctreeNode node2 = new ESVOOctreeNode((byte)2, 200, 0);
        octree.setNode(0, node1);
        octree.setNode(10, node2);
        
        scene.loadOctree("bounded", octree);
        
        Vector3f minBounds = scene.getMinBounds();
        Vector3f maxBounds = scene.getMaxBounds();
        
        assertNotNull(minBounds);
        assertNotNull(maxBounds);
        
        // Bounds should encompass [1,2] coordinate space
        assertTrue(minBounds.x <= 1.0f);
        assertTrue(minBounds.y <= 1.0f);
        assertTrue(minBounds.z <= 1.0f);
        assertTrue(maxBounds.x >= 2.0f);
        assertTrue(maxBounds.y >= 2.0f);
        assertTrue(maxBounds.z >= 2.0f);
    }
    
    @Test
    void testMultiOctreeScene() throws IOException {
        // Test scenes with multiple octrees
        ESVOOctreeData octree1 = new ESVOOctreeData(256);
        ESVOOctreeData octree2 = new ESVOOctreeData(512);
        
        octree1.setNode(0, new ESVOOctreeNode((byte)1, 111, 0));
        octree2.setNode(0, new ESVOOctreeNode((byte)2, 222, 0));
        
        scene.loadOctree("first", octree1);
        scene.loadOctree("second", octree2);
        
        assertEquals(2, scene.getOctreeCount());
        assertTrue(scene.hasOctree("first"));
        assertTrue(scene.hasOctree("second"));
        
        // Test individual retrieval
        ESVOOctreeNode node1 = scene.getOctree("first").getNode(0);
        ESVOOctreeNode node2 = scene.getOctree("second").getNode(0);
        
        assertEquals((byte)1, node1.childMask);
        assertEquals(111, node1.contour);
        assertEquals((byte)2, node2.childMask);
        assertEquals(222, node2.contour);
        
        // Test removal
        scene.removeOctree("first");
        assertEquals(1, scene.getOctreeCount());
        assertFalse(scene.hasOctree("first"));
        assertTrue(scene.hasOctree("second"));
    }
}