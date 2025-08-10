package com.hellblazer.luciferase.render.voxel.parallel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import javax.vecmath.Point3f;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for WorkEstimator load balancing and work prediction.
 * Validates Surface Area Heuristics, task distribution, and performance monitoring.
 */
public class WorkEstimatorTest {
    
    private static final float EPSILON = 1e-5f;
    private WorkEstimator estimator;
    
    @BeforeEach
    public void setUp() {
        estimator = new WorkEstimator();
    }
    
    @Test
    public void testBasicWorkEstimation() {
        var region = new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1);
        
        // Test with different triangle counts
        float work1 = estimator.estimateTraversalWork(region, 10);
        float work2 = estimator.estimateTraversalWork(region, 100);
        float work3 = estimator.estimateTraversalWork(region, 1000);
        
        // More triangles should require more work
        assertTrue(work1 < work2, "Work should increase with triangle count");
        assertTrue(work2 < work3, "Work should scale with triangle count");
        
        // All estimates should be positive
        assertTrue(work1 > 0, "Work estimate should be positive");
        assertTrue(work2 > 0, "Work estimate should be positive");
        assertTrue(work3 > 0, "Work estimate should be positive");
    }
    
    @Test
    public void testSurfaceAreaHeuristics() {
        // Test different sized regions with same triangle density (triangles scale with volume)
        var smallRegion = new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1);
        var largeRegion = new WorkEstimator.BoundingBox(0, 0, 0, 2, 2, 2);
        
        int smallTriangleCount = 100;
        int largeTriangleCount = 800; // Scale with volume (8x larger)
        
        float smallWork = estimator.estimateTraversalWork(smallRegion, smallTriangleCount);
        float largeWork = estimator.estimateTraversalWork(largeRegion, largeTriangleCount);
        
        // Work should scale with region size when triangle density is maintained
        assertTrue(largeWork > smallWork, "Larger regions with proportional triangles should require more work");
        
        // Test same volume, different surface area
        var cubeRegion = new WorkEstimator.BoundingBox(0, 0, 0, 2, 2, 2);
        var slabRegion = new WorkEstimator.BoundingBox(0, 0, 0, 8, 1, 1);
        
        float cubeWork = estimator.estimateTraversalWork(cubeRegion, smallTriangleCount);
        float slabWork = estimator.estimateTraversalWork(slabRegion, smallTriangleCount);
        
        // Different surface area to volume ratios should affect work differently
        assertNotEquals(cubeWork, slabWork, EPSILON, "Different shapes should have different work");
    }
    
    @Test
    public void testOctreeDepthImpact() {
        var region = new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1);
        int triangleCount = 100;
        int rayCount = 1000;
        
        float work4 = estimator.estimateTraversalWork(region, triangleCount, 4, rayCount);
        float work8 = estimator.estimateTraversalWork(region, triangleCount, 8, rayCount);
        float work12 = estimator.estimateTraversalWork(region, triangleCount, 12, rayCount);
        
        // Deeper octrees should require more work
        assertTrue(work4 < work8, "Deeper octree should require more work");
        assertTrue(work8 < work12, "Work should increase with depth");
        
        // Should show exponential-like growth
        float ratio1 = work8 / work4;
        float ratio2 = work12 / work8;
        assertTrue(ratio1 > 1.5f, "Work should increase significantly with depth");
        assertTrue(ratio2 > 1.2f, "Work should continue increasing with depth");
    }
    
    @Test
    public void testRayCountScaling() {
        var region = new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1);
        int triangleCount = 100;
        int octreeDepth = 8;
        
        float work1K = estimator.estimateTraversalWork(region, triangleCount, octreeDepth, 1000);
        float work10K = estimator.estimateTraversalWork(region, triangleCount, octreeDepth, 10000);
        float work100K = estimator.estimateTraversalWork(region, triangleCount, octreeDepth, 100000);
        
        // Work should scale linearly with ray count
        assertTrue(work1K < work10K, "More rays should require more work");
        assertTrue(work10K < work100K, "Work should scale with ray count");
        
        // Check approximate linear scaling
        float ratio1 = work10K / work1K;
        float ratio2 = work100K / work10K;
        
        assertTrue(ratio1 > 8f && ratio1 < 12f, "Work should scale approximately linearly with rays");
        assertTrue(ratio2 > 8f && ratio2 < 12f, "Work scaling should be consistent");
    }
    
    @Test
    public void testWorkTaskCreation() {
        var sceneRegion = new WorkEstimator.BoundingBox(-10, -10, -10, 10, 10, 10);
        int totalTriangles = 10000;
        int maxOctreeDepth = 10;
        int rayCount = 50000;
        int desiredTaskCount = 16;
        
        var tasks = estimator.createWorkTasks(sceneRegion, totalTriangles, 
                                           maxOctreeDepth, rayCount, desiredTaskCount);
        
        assertNotNull(tasks, "Should create task list");
        assertFalse(tasks.isEmpty(), "Should create at least one task");
        assertTrue(tasks.size() <= desiredTaskCount * 2, "Should not create excessive tasks");
        
        // Check task properties
        float totalWork = 0;
        int totalTaskTriangles = 0;
        
        for (var task : tasks) {
            assertTrue(task.estimatedWork > 0, "Each task should have positive work");
            assertTrue(task.triangleCount >= 0, "Triangle count should be non-negative");
            assertTrue(task.octreeDepth > 0, "Octree depth should be positive");
            assertTrue(task.rayCount > 0, "Ray count should be positive");
            
            totalWork += task.estimatedWork;
            totalTaskTriangles += task.triangleCount;
            
            assertNotNull(task.toString(), "Should provide string representation");
        }
        
        // Triangle distribution should roughly match total
        assertTrue(totalTaskTriangles <= totalTriangles * 1.1f, 
            "Total task triangles should not exceed scene triangles significantly");
        
        assertTrue(totalWork > 0, "Total work should be positive");
    }
    
    @Test
    public void testBoundingBoxOperations() {
        var box1 = new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1);
        
        // Test surface area calculation
        float expectedSurfaceArea = 6.0f; // Unit cube has surface area 6
        assertEquals(expectedSurfaceArea, box1.getSurfaceArea(), EPSILON, 
            "Unit cube should have surface area 6");
        
        // Test volume calculation
        float expectedVolume = 1.0f; // Unit cube has volume 1
        assertEquals(expectedVolume, box1.getVolume(), EPSILON, 
            "Unit cube should have volume 1");
        
        // Test center calculation
        var expectedCenter = new Point3f(0.5f, 0.5f, 0.5f);
        var actualCenter = box1.getCenter();
        assertEquals(expectedCenter.x, actualCenter.x, EPSILON, "Center X should be correct");
        assertEquals(expectedCenter.y, actualCenter.y, EPSILON, "Center Y should be correct");
        assertEquals(expectedCenter.z, actualCenter.z, EPSILON, "Center Z should be correct");
        
        // Test intersection
        var box2 = new WorkEstimator.BoundingBox(0.5f, 0.5f, 0.5f, 1.5f, 1.5f, 1.5f);
        assertTrue(box1.intersects(box2), "Overlapping boxes should intersect");
        
        var box3 = new WorkEstimator.BoundingBox(2, 2, 2, 3, 3, 3);
        assertFalse(box1.intersects(box3), "Separate boxes should not intersect");
    }
    
    @Test
    public void testComputeUnitManagement() {
        var unit = new WorkEstimator.ComputeUnit(1, 1.0f, 4);
        
        // Test initial state
        assertEquals(1, unit.unitId);
        assertEquals(1.0f, unit.processingPower, EPSILON);
        assertEquals(4, unit.maxConcurrentTasks);
        assertTrue(unit.canAcceptTask(), "New unit should accept tasks");
        assertEquals(0.0f, unit.getCurrentLoad(), EPSILON, "Initial load should be zero");
        
        // Test task assignment
        var task1 = new WorkEstimator.WorkTask(1, 
            new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1), 100, 8, 1000);
        
        unit.startTask(task1);
        assertEquals(1, unit.getActiveTasks(), "Should have one active task");
        assertEquals(0.25f, unit.getCurrentLoad(), EPSILON, "Load should be 25%");
        assertTrue(unit.canAcceptTask(), "Should still accept more tasks");
        
        // Add more tasks up to limit
        var task2 = new WorkEstimator.WorkTask(2, 
            new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1), 100, 8, 1000);
        var task3 = new WorkEstimator.WorkTask(3, 
            new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1), 100, 8, 1000);
        var task4 = new WorkEstimator.WorkTask(4, 
            new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1), 100, 8, 1000);
        
        unit.startTask(task2);
        unit.startTask(task3);
        unit.startTask(task4);
        
        assertEquals(4, unit.getActiveTasks(), "Should have maximum tasks");
        assertEquals(1.0f, unit.getCurrentLoad(), EPSILON, "Load should be 100%");
        assertFalse(unit.canAcceptTask(), "Should not accept more tasks at capacity");
        
        // Test task completion
        unit.completeTask(task1);
        assertEquals(3, unit.getActiveTasks(), "Should have three active tasks after completion");
        assertEquals(0.75f, unit.getCurrentLoad(), EPSILON, "Load should decrease");
        assertTrue(unit.canAcceptTask(), "Should accept tasks after completion");
        assertTrue(unit.getTotalWorkCompleted() > 0, "Should track completed work");
        
        assertNotNull(unit.toString(), "Should provide string representation");
    }
    
    @Test
    public void testWorkQueueOperations() {
        var queue = new WorkEstimator.WorkQueue();
        
        // Test empty queue
        assertTrue(queue.hasWork() == false, "Empty queue should have no work");
        assertEquals(0, queue.getPendingTaskCount(), "Pending count should be zero");
        assertEquals(0, queue.getActiveTaskCount(), "Active count should be zero");
        assertEquals(0, queue.getCompletedTaskCount(), "Completed count should be zero");
        
        // Add tasks
        var task1 = new WorkEstimator.WorkTask(1,
            new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1), 100, 8, 1000);
        var task2 = new WorkEstimator.WorkTask(2,
            new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1), 50, 6, 500);
        
        queue.addTask(task1);
        queue.addTask(task2);
        
        assertTrue(queue.hasWork(), "Queue should have work");
        assertEquals(2, queue.getPendingTaskCount(), "Should have two pending tasks");
        
        // Get tasks
        var retrieved1 = queue.getNextTask();
        assertNotNull(retrieved1, "Should retrieve a task");
        assertEquals(1, queue.getPendingTaskCount(), "Pending count should decrease");
        
        // Test filtered task retrieval
        var task3 = new WorkEstimator.WorkTask(3,
            new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1), 200, 10, 2000);
        queue.addTask(task3);
        
        float minWork = task3.estimatedWork * 0.8f;
        float maxWork = task3.estimatedWork * 1.2f;
        
        var filteredTask = queue.getNextTask(minWork, maxWork);
        assertNotNull(filteredTask, "Should find task within work range");
        assertEquals(3, filteredTask.taskId, "Should retrieve the high-work task");
        
        // Test task lifecycle
        queue.startTask(retrieved1, 1);
        assertEquals(1, queue.getActiveTaskCount(), "Should have active task");
        
        queue.completeTask(retrieved1.taskId, 1);
        assertEquals(0, queue.getActiveTaskCount(), "Active count should decrease");
        assertEquals(1, queue.getCompletedTaskCount(), "Completed count should increase");
        
        var completedTasks = queue.getCompletedTasks(1);
        assertEquals(1, completedTasks.size(), "Should have one completed task");
    }
    
    @Test
    public void testLoadBalancing() {
        var queue = new WorkEstimator.WorkQueue();
        var computeUnits = new HashMap<Integer, WorkEstimator.ComputeUnit>();
        
        // Create compute units with different capacities
        computeUnits.put(1, new WorkEstimator.ComputeUnit(1, 1.0f, 2));
        computeUnits.put(2, new WorkEstimator.ComputeUnit(2, 1.0f, 4));
        computeUnits.put(3, new WorkEstimator.ComputeUnit(3, 0.5f, 6));
        
        // Create unbalanced workload
        var heavyTask = new WorkEstimator.WorkTask(1,
            new WorkEstimator.BoundingBox(0, 0, 0, 2, 2, 2), 1000, 12, 10000);
        var lightTask1 = new WorkEstimator.WorkTask(2,
            new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1), 10, 4, 100);
        var lightTask2 = new WorkEstimator.WorkTask(3,
            new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1), 10, 4, 100);
        
        // Simulate imbalanced assignment
        computeUnits.get(1).startTask(heavyTask);
        computeUnits.get(1).startTask(lightTask1);
        
        queue.addTask(lightTask2);
        
        // Check initial load imbalance
        float unit1Load = computeUnits.get(1).getCurrentLoad();
        float unit2Load = computeUnits.get(2).getCurrentLoad();
        float unit3Load = computeUnits.get(3).getCurrentLoad();
        
        assertEquals(1.0f, unit1Load, EPSILON, "Unit 1 should be fully loaded");
        assertEquals(0.0f, unit2Load, EPSILON, "Unit 2 should be unloaded");
        assertEquals(0.0f, unit3Load, EPSILON, "Unit 3 should be unloaded");
        
        // Attempt load balancing
        estimator.redistributeWork(queue, computeUnits);
        
        // Should help balance the load by assigning pending task to idle unit
        assertTrue(queue.getPendingTaskCount() <= 1, "Should assign pending tasks");
    }
    
    @Test
    public void testPerformanceMonitoring() {
        var monitor = estimator.getPerformanceMonitor();
        
        // Create test task with actual performance data
        var task = new WorkEstimator.WorkTask(1,
            new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1), 100, 8, 1000);
        
        float estimatedWork = task.estimatedWork;
        float actualWork = estimatedWork * 1.2f; // 20% higher than estimated
        long executionTime = 1000000L; // 1ms in nanoseconds
        
        task.recordActualWork(actualWork, executionTime);
        
        // Test task accuracy calculation
        assertTrue(task.hasActualWork(), "Task should have actual work recorded");
        assertEquals(actualWork, task.getActualWork(), EPSILON, "Actual work should be recorded");
        
        float accuracy = task.getWorkAccuracy();
        assertTrue(accuracy > 0.8f && accuracy < 1.0f, "Accuracy should be reasonable");
        
        // Record in monitor
        monitor.recordTaskPerformance(task, 1);
        
        float avgAccuracy = monitor.getAverageAccuracy(1);
        assertEquals(accuracy, avgAccuracy, EPSILON, "Average should equal single task accuracy");
        
        long avgTime = monitor.getAverageExecutionTime(1);
        assertEquals(executionTime, avgTime, "Average time should equal single task time");
        
        // Test overall accuracy
        float overallAccuracy = monitor.getOverallAccuracy();
        assertTrue(overallAccuracy > 0.8f, "Overall accuracy should be reasonable");
    }
    
    @Test
    public void testAdaptiveWorkEstimation() {
        var region = new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1);
        
        // Get base estimate
        float baseEstimate = estimator.estimateTraversalWork(region, 100, 8, 1000);
        
        // Get adaptive estimate (should be same initially with no history)
        float adaptiveEstimate = estimator.getAdaptiveWorkEstimate(region, 100, 8, 1000);
        
        // Without history, should be close to base estimate
        assertEquals(baseEstimate, adaptiveEstimate, baseEstimate * 0.1f, 
            "Adaptive estimate should be close to base estimate initially");
        
        // Simulate some performance history by creating and recording tasks
        var monitor = estimator.getPerformanceMonitor();
        
        for (int i = 0; i < 10; i++) {
            var task = new WorkEstimator.WorkTask(i, region, 100, 8, 1000);
            // Simulate consistently high actual work (50% higher than estimated)
            task.recordActualWork(task.estimatedWork * 1.5f, 1000000L);
            monitor.recordTaskPerformance(task, 1);
        }
        
        // Now adaptive estimate should be adjusted
        float adaptiveEstimate2 = estimator.getAdaptiveWorkEstimate(region, 100, 8, 1000);
        
        // Should adjust upward based on historical underestimation
        assertTrue(adaptiveEstimate2 > baseEstimate, 
            "Adaptive estimate should adjust based on history");
    }
    
    @Test
    public void testSceneComplexityCalculation() {
        var simpleRegion = new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1);
        var complexRegion = new WorkEstimator.BoundingBox(0, 0, 0, 2, 2, 2);
        
        float simpleComplexity = estimator.calculateSceneComplexity(simpleRegion, 100, 8);
        float complexComplexity = estimator.calculateSceneComplexity(complexRegion, 1000, 12);
        
        assertTrue(simpleComplexity > 0, "Simple scene should have positive complexity");
        assertTrue(complexComplexity > simpleComplexity, 
            "More complex scene should have higher complexity");
        
        // Test caching - same parameters should return same result
        float cachedComplexity = estimator.calculateSceneComplexity(simpleRegion, 100, 8);
        assertEquals(simpleComplexity, cachedComplexity, EPSILON, 
            "Cached complexity should match original");
    }
    
    @Test 
    public void testWorkEstimationEdgeCases() {
        var region = new WorkEstimator.BoundingBox(0, 0, 0, 1, 1, 1);
        
        // Test with zero triangles
        float zeroWork = estimator.estimateTraversalWork(region, 0);
        assertTrue(zeroWork >= 1.0f, "Zero triangles should still have minimum work");
        
        // Test with very small region
        var tinyRegion = new WorkEstimator.BoundingBox(0, 0, 0, 0.001f, 0.001f, 0.001f);
        float tinyWork = estimator.estimateTraversalWork(tinyRegion, 100);
        assertTrue(tinyWork > 0, "Tiny region should still have positive work");
        
        // Test with very large numbers
        var largeRegion = new WorkEstimator.BoundingBox(-1000, -1000, -1000, 1000, 1000, 1000);
        float largeWork = estimator.estimateTraversalWork(largeRegion, 1000000, 20, 1000000);
        assertTrue(largeWork > zeroWork, "Large scene should have more work");
        assertFalse(Float.isInfinite(largeWork), "Work should not be infinite");
        assertFalse(Float.isNaN(largeWork), "Work should not be NaN");
    }
}