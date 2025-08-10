package com.hellblazer.luciferase.render.voxel.parallel;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static java.util.stream.Collectors.toList;

/**
 * Work estimation and load balancing for GPU ray traversal.
 * Predicts computational workload based on scene complexity and dynamically
 * redistributes work across compute units for optimal GPU utilization.
 * 
 * Based on NVIDIA ESVO work estimation heuristics and surface area heuristics (SAH).
 */
public class WorkEstimator {
    
    // Surface Area Heuristics constants
    private static final float SAH_TRAVERSAL_COST = 1.0f;
    private static final float SAH_INTERSECTION_COST = 80.0f;
    private static final float SAH_EMPTY_SPACE_FACTOR = 0.1f;
    
    // Work estimation parameters
    private static final float TRIANGLE_DENSITY_WEIGHT = 1.5f;
    private static final float OCTREE_DEPTH_WEIGHT = 1.2f;
    private static final float RAY_COHERENCE_WEIGHT = 0.8f;
    private static final float HISTORICAL_WEIGHT = 0.3f;
    
    // Load balancing thresholds
    private static final float LOAD_IMBALANCE_THRESHOLD = 0.2f;
    private static final float MIN_TASK_SIZE_RATIO = 0.01f;
    private static final int MAX_REDISTRIBUTION_ATTEMPTS = 3;
    
    /**
     * Represents a bounding box for work estimation.
     */
    public static class BoundingBox {
        public final Point3f min;
        public final Point3f max;
        
        public BoundingBox(Point3f min, Point3f max) {
            this.min = new Point3f(min);
            this.max = new Point3f(max);
        }
        
        public BoundingBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            this.min = new Point3f(minX, minY, minZ);
            this.max = new Point3f(maxX, maxY, maxZ);
        }
        
        public float getSurfaceArea() {
            float dx = max.x - min.x;
            float dy = max.y - min.y;
            float dz = max.z - min.z;
            return 2.0f * (dx * dy + dy * dz + dz * dx);
        }
        
        public float getVolume() {
            float dx = max.x - min.x;
            float dy = max.y - min.y;
            float dz = max.z - min.z;
            return dx * dy * dz;
        }
        
        public Point3f getCenter() {
            return new Point3f(
                (min.x + max.x) * 0.5f,
                (min.y + max.y) * 0.5f,
                (min.z + max.z) * 0.5f
            );
        }
        
        public boolean intersects(BoundingBox other) {
            return !(min.x > other.max.x || max.x < other.min.x ||
                    min.y > other.max.y || max.y < other.min.y ||
                    min.z > other.max.z || max.z < other.min.z);
        }
    }
    
    /**
     * Work task representing a unit of GPU computation.
     */
    public static class WorkTask {
        public final int taskId;
        public final BoundingBox region;
        public final int triangleCount;
        public final int octreeDepth;
        public final float estimatedWork;
        public final int rayCount;
        public final float complexity;
        
        private volatile float actualWork = -1.0f;
        private volatile long executionTimeNs = -1;
        
        public WorkTask(int taskId, BoundingBox region, int triangleCount, 
                       int octreeDepth, int rayCount) {
            this.taskId = taskId;
            this.region = region;
            this.triangleCount = triangleCount;
            this.octreeDepth = octreeDepth;
            this.rayCount = rayCount;
            this.complexity = calculateComplexity();
            this.estimatedWork = estimateWorkUnits();
        }
        
        private float calculateComplexity() {
            float density = triangleCount / Math.max(1.0f, region.getVolume());
            float depthFactor = (float) Math.pow(2, octreeDepth);
            return density * depthFactor * rayCount;
        }
        
        private float estimateWorkUnits() {
            // Surface Area Heuristics-based estimation
            float surfaceArea = region.getSurfaceArea();
            float traversalCost = SAH_TRAVERSAL_COST * octreeDepth * rayCount;
            float intersectionCost = SAH_INTERSECTION_COST * triangleCount * rayCount;
            
            // Adjust for empty space
            float emptySpaceFactor = 1.0f - (triangleCount > 0 ? SAH_EMPTY_SPACE_FACTOR : 0.9f);
            
            return (traversalCost + intersectionCost) * surfaceArea * emptySpaceFactor;
        }
        
        public void recordActualWork(float work, long executionTimeNs) {
            this.actualWork = work;
            this.executionTimeNs = executionTimeNs;
        }
        
        public boolean hasActualWork() {
            return actualWork >= 0;
        }
        
        public float getActualWork() {
            return actualWork;
        }
        
        public long getExecutionTimeNs() {
            return executionTimeNs;
        }
        
        public float getWorkAccuracy() {
            if (!hasActualWork()) {
                return 0.0f;
            }
            
            if (actualWork == 0.0f && estimatedWork == 0.0f) {
                return 1.0f;
            }
            
            float ratio = Math.min(estimatedWork, actualWork) / Math.max(estimatedWork, actualWork);
            return Math.max(0.0f, ratio);
        }
        
        @Override
        public String toString() {
            return String.format("WorkTask{id=%d, triangles=%d, depth=%d, rays=%d, estimated=%.2f, actual=%.2f}",
                taskId, triangleCount, octreeDepth, rayCount, estimatedWork, actualWork);
        }
    }
    
    /**
     * Compute unit representing a processing resource (GPU SM, CPU core, etc.).
     */
    public static class ComputeUnit {
        public final int unitId;
        public final float processingPower;     // Relative processing capability
        public final int maxConcurrentTasks;    // Maximum tasks this unit can handle
        
        private final AtomicInteger activeTasks = new AtomicInteger(0);
        private final AtomicLong totalWorkCompleted = new AtomicLong(0);
        private volatile float currentLoad = 0.0f;
        
        public ComputeUnit(int unitId, float processingPower, int maxConcurrentTasks) {
            this.unitId = unitId;
            this.processingPower = processingPower;
            this.maxConcurrentTasks = maxConcurrentTasks;
        }
        
        public boolean canAcceptTask() {
            return activeTasks.get() < maxConcurrentTasks;
        }
        
        public void startTask(WorkTask task) {
            activeTasks.incrementAndGet();
            updateLoad();
        }
        
        public void completeTask(WorkTask task) {
            activeTasks.decrementAndGet();
            totalWorkCompleted.addAndGet((long) task.estimatedWork);
            updateLoad();
        }
        
        private void updateLoad() {
            currentLoad = (float) activeTasks.get() / maxConcurrentTasks;
        }
        
        public float getCurrentLoad() {
            return currentLoad;
        }
        
        public int getActiveTasks() {
            return activeTasks.get();
        }
        
        public long getTotalWorkCompleted() {
            return totalWorkCompleted.get();
        }
        
        @Override
        public String toString() {
            return String.format("ComputeUnit{id=%d, power=%.2f, load=%.2f%%, active=%d/%d}",
                unitId, processingPower, currentLoad * 100, activeTasks.get(), maxConcurrentTasks);
        }
    }
    
    /**
     * Work queue for load balancing across compute units.
     */
    public static class WorkQueue {
        private final Queue<WorkTask> pendingTasks = new LinkedList<>();
        private final Map<Integer, WorkTask> activeTasks = new ConcurrentHashMap<>();
        private final Map<Integer, List<WorkTask>> completedTasks = new ConcurrentHashMap<>();
        private final AtomicInteger taskIdGenerator = new AtomicInteger(0);
        
        public synchronized void addTask(WorkTask task) {
            pendingTasks.offer(task);
        }
        
        public synchronized void addTasks(List<WorkTask> tasks) {
            pendingTasks.addAll(tasks);
        }
        
        public synchronized WorkTask getNextTask() {
            return pendingTasks.poll();
        }
        
        public synchronized WorkTask getNextTask(float minWork, float maxWork) {
            var iterator = pendingTasks.iterator();
            while (iterator.hasNext()) {
                var task = iterator.next();
                if (task.estimatedWork >= minWork && task.estimatedWork <= maxWork) {
                    iterator.remove();
                    return task;
                }
            }
            return null;
        }
        
        public void startTask(WorkTask task, int computeUnitId) {
            activeTasks.put(task.taskId, task);
        }
        
        public void completeTask(int taskId, int computeUnitId) {
            WorkTask task = activeTasks.remove(taskId);
            if (task != null) {
                completedTasks.computeIfAbsent(computeUnitId, k -> new ArrayList<>()).add(task);
            }
        }
        
        public synchronized boolean hasWork() {
            return !pendingTasks.isEmpty();
        }
        
        public synchronized int getPendingTaskCount() {
            return pendingTasks.size();
        }
        
        public int getActiveTaskCount() {
            return activeTasks.size();
        }
        
        public int getCompletedTaskCount() {
            return completedTasks.values().stream().mapToInt(List::size).sum();
        }
        
        public List<WorkTask> getCompletedTasks(int computeUnitId) {
            return completedTasks.getOrDefault(computeUnitId, new ArrayList<>());
        }
        
        public int generateTaskId() {
            return taskIdGenerator.incrementAndGet();
        }
    }
    
    /**
     * Performance monitoring and adaptation.
     */
    public static class PerformanceMonitor {
        private final Map<Integer, List<Float>> workAccuracyHistory = new ConcurrentHashMap<>();
        private final Map<Integer, List<Long>> executionTimeHistory = new ConcurrentHashMap<>();
        private final AtomicLong totalPredictedWork = new AtomicLong(0);
        private final AtomicLong totalActualWork = new AtomicLong(0);
        
        public void recordTaskPerformance(WorkTask task, int computeUnitId) {
            if (task.hasActualWork()) {
                workAccuracyHistory.computeIfAbsent(computeUnitId, k -> new ArrayList<>())
                    .add(task.getWorkAccuracy());
                
                executionTimeHistory.computeIfAbsent(computeUnitId, k -> new ArrayList<>())
                    .add(task.getExecutionTimeNs());
                
                totalPredictedWork.addAndGet((long) task.estimatedWork);
                totalActualWork.addAndGet((long) task.getActualWork());
            }
        }
        
        public float getAverageAccuracy(int computeUnitId) {
            var accuracies = workAccuracyHistory.get(computeUnitId);
            if (accuracies == null || accuracies.isEmpty()) {
                return 0.0f;
            }
            
            return (float) accuracies.stream().mapToDouble(Float::doubleValue).average().orElse(0.0);
        }
        
        public float getOverallAccuracy() {
            long predicted = totalPredictedWork.get();
            long actual = totalActualWork.get();
            
            if (predicted == 0 && actual == 0) {
                return 1.0f;
            }
            
            if (predicted == 0 || actual == 0) {
                return 0.0f;
            }
            
            return Math.min(predicted, actual) / (float) Math.max(predicted, actual);
        }
        
        public long getAverageExecutionTime(int computeUnitId) {
            var times = executionTimeHistory.get(computeUnitId);
            if (times == null || times.isEmpty()) {
                return 0;
            }
            
            return (long) times.stream().mapToLong(Long::longValue).average().orElse(0);
        }
    }
    
    private final PerformanceMonitor monitor = new PerformanceMonitor();
    private final Map<String, Float> sceneComplexityCache = new ConcurrentHashMap<>();
    
    /**
     * Estimate traversal work for a region based on triangle count and octree depth.
     */
    public float estimateTraversalWork(BoundingBox region, int triangleCount) {
        return estimateTraversalWork(region, triangleCount, 8, 1000); // Default values
    }
    
    public float estimateTraversalWork(BoundingBox region, int triangleCount, 
                                     int octreeDepth, int rayCount) {
        // Surface Area Heuristics calculation
        float surfaceArea = region.getSurfaceArea();
        
        // Triangle density factor
        float density = triangleCount / Math.max(0.001f, region.getVolume());
        float densityFactor = 1.0f + density * TRIANGLE_DENSITY_WEIGHT;
        
        // Octree depth factor (exponential impact)
        float depthFactor = (float) Math.pow(OCTREE_DEPTH_WEIGHT, octreeDepth);
        
        // Base traversal cost
        float traversalCost = SAH_TRAVERSAL_COST * octreeDepth * rayCount;
        float intersectionCost = SAH_INTERSECTION_COST * triangleCount * rayCount;
        
        // Total work estimate
        float workUnits = (traversalCost + intersectionCost) * densityFactor * depthFactor;
        
        // Apply surface area weighting
        workUnits *= surfaceArea / (surfaceArea + 1.0f); // Normalize to prevent extreme values
        
        return Math.max(1.0f, workUnits);
    }
    
    /**
     * Create work tasks for parallel processing.
     */
    public List<WorkTask> createWorkTasks(BoundingBox sceneRegion, int totalTriangles,
                                        int maxOctreeDepth, int rayCount, int desiredTaskCount) {
        var tasks = new ArrayList<WorkTask>();
        
        // Spatial subdivision for task creation
        var subRegions = subdivideRegion(sceneRegion, desiredTaskCount);
        
        // Estimate triangle distribution across subregions
        var triangleDistribution = estimateTriangleDistribution(totalTriangles, subRegions);
        
        for (int i = 0; i < subRegions.size(); i++) {
            var region = subRegions.get(i);
            int triangleCount = triangleDistribution.get(i);
            int localDepth = estimateLocalOctreeDepth(region, triangleCount, maxOctreeDepth);
            int localRayCount = estimateLocalRayCount(region, rayCount);
            
            var task = new WorkTask(i, region, triangleCount, localDepth, localRayCount);
            tasks.add(task);
        }
        
        return tasks;
    }
    
    /**
     * Redistribute work across compute units for load balancing.
     */
    public void redistributeWork(WorkQueue queue, Map<Integer, ComputeUnit> computeUnits) {
        if (computeUnits.isEmpty()) {
            return;
        }
        
        // Calculate current load imbalance
        var loads = computeUnits.values().stream()
            .map(ComputeUnit::getCurrentLoad)
            .collect(toList());
        
        float minLoad = loads.stream().min(Float::compare).orElse(0.0f);
        float maxLoad = loads.stream().max(Float::compare).orElse(0.0f);
        float loadImbalance = maxLoad - minLoad;
        
        if (loadImbalance < LOAD_IMBALANCE_THRESHOLD) {
            return; // Load is already balanced
        }
        
        // Perform work redistribution
        for (int attempt = 0; attempt < MAX_REDISTRIBUTION_ATTEMPTS; attempt++) {
            if (!redistributeWorkIteration(queue, computeUnits)) {
                break; // No beneficial redistribution possible
            }
        }
    }
    
    private boolean redistributeWorkIteration(WorkQueue queue, Map<Integer, ComputeUnit> computeUnits) {
        // Find most loaded and least loaded compute units
        ComputeUnit mostLoaded = null;
        ComputeUnit leastLoaded = null;
        float maxLoad = 0.0f;
        float minLoad = 1.0f;
        
        for (var unit : computeUnits.values()) {
            float load = unit.getCurrentLoad();
            if (load > maxLoad) {
                maxLoad = load;
                mostLoaded = unit;
            }
            if (load < minLoad) {
                minLoad = load;
                leastLoaded = unit;
            }
        }
        
        if (mostLoaded == null || leastLoaded == null || maxLoad - minLoad < LOAD_IMBALANCE_THRESHOLD) {
            return false;
        }
        
        // Try to move work from most loaded to least loaded
        return attemptWorkTransfer(queue, mostLoaded, leastLoaded);
    }
    
    private boolean attemptWorkTransfer(WorkQueue queue, ComputeUnit from, ComputeUnit to) {
        if (!to.canAcceptTask()) {
            return false;
        }
        
        // Look for appropriately sized task
        float targetWork = (from.getCurrentLoad() - to.getCurrentLoad()) * 0.5f * from.processingPower;
        
        var task = queue.getNextTask(targetWork * 0.5f, targetWork * 1.5f);
        if (task != null) {
            // Transfer task
            to.startTask(task);
            return true;
        }
        
        return false;
    }
    
    /**
     * Adaptive work estimation based on historical performance.
     */
    public float getAdaptiveWorkEstimate(BoundingBox region, int triangleCount, 
                                       int octreeDepth, int rayCount) {
        // Base estimate
        float baseEstimate = estimateTraversalWork(region, triangleCount, octreeDepth, rayCount);
        
        // Apply historical correction
        float overallAccuracy = monitor.getOverallAccuracy();
        if (overallAccuracy > 0.5f) {
            // Adjust based on historical performance
            float correctionFactor = 1.0f + (1.0f - overallAccuracy) * HISTORICAL_WEIGHT;
            baseEstimate *= correctionFactor;
        }
        
        return baseEstimate;
    }
    
    /**
     * Get performance monitoring data.
     */
    public PerformanceMonitor getPerformanceMonitor() {
        return monitor;
    }
    
    /**
     * Calculate scene complexity metric for adaptive algorithms.
     */
    public float calculateSceneComplexity(BoundingBox sceneRegion, int triangleCount, 
                                        int octreeDepth) {
        String key = String.format("%.2f,%.2f,%.2f-%d-%d", 
            sceneRegion.getVolume(), sceneRegion.getSurfaceArea(), 
            triangleCount / Math.max(1.0f, sceneRegion.getVolume()), 
            triangleCount, octreeDepth);
        
        return sceneComplexityCache.computeIfAbsent(key, k -> {
            float density = triangleCount / Math.max(0.001f, sceneRegion.getVolume());
            float depthFactor = (float) Math.log(octreeDepth + 1);
            float surfaceComplexity = sceneRegion.getSurfaceArea() / sceneRegion.getVolume();
            
            return (density * TRIANGLE_DENSITY_WEIGHT + 
                   depthFactor * OCTREE_DEPTH_WEIGHT + 
                   surfaceComplexity) / 3.0f;
        });
    }
    
    // Helper methods
    
    private List<BoundingBox> subdivideRegion(BoundingBox region, int subdivisions) {
        var subRegions = new ArrayList<BoundingBox>();
        
        // Simple spatial subdivision (could be improved with better heuristics)
        int subdivisionsPerAxis = (int) Math.ceil(Math.cbrt(subdivisions));
        
        float dx = (region.max.x - region.min.x) / subdivisionsPerAxis;
        float dy = (region.max.y - region.min.y) / subdivisionsPerAxis;
        float dz = (region.max.z - region.min.z) / subdivisionsPerAxis;
        
        for (int x = 0; x < subdivisionsPerAxis; x++) {
            for (int y = 0; y < subdivisionsPerAxis; y++) {
                for (int z = 0; z < subdivisionsPerAxis; z++) {
                    float minX = region.min.x + x * dx;
                    float maxX = region.min.x + (x + 1) * dx;
                    float minY = region.min.y + y * dy;
                    float maxY = region.min.y + (y + 1) * dy;
                    float minZ = region.min.z + z * dz;
                    float maxZ = region.min.z + (z + 1) * dz;
                    
                    subRegions.add(new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ));
                }
            }
        }
        
        // Trim to desired count
        while (subRegions.size() > subdivisions) {
            subRegions.remove(subRegions.size() - 1);
        }
        
        return subRegions;
    }
    
    private List<Integer> estimateTriangleDistribution(int totalTriangles, List<BoundingBox> regions) {
        var distribution = new ArrayList<Integer>();
        float totalVolume = (float) regions.stream().mapToDouble(r -> r.getVolume()).sum();
        
        for (var region : regions) {
            float volumeRatio = region.getVolume() / totalVolume;
            int triangleCount = Math.round(totalTriangles * volumeRatio);
            distribution.add(triangleCount);
        }
        
        return distribution;
    }
    
    private int estimateLocalOctreeDepth(BoundingBox region, int triangleCount, int maxDepth) {
        // Estimate local depth based on triangle density
        float density = triangleCount / Math.max(0.001f, region.getVolume());
        int estimatedDepth = (int) Math.ceil(Math.log(density + 1) / Math.log(2));
        return Math.min(estimatedDepth, maxDepth);
    }
    
    private int estimateLocalRayCount(BoundingBox region, int totalRayCount) {
        // Simplified ray distribution (could be improved with actual ray data)
        return Math.max(1, totalRayCount / 64); // Assume even distribution
    }
}