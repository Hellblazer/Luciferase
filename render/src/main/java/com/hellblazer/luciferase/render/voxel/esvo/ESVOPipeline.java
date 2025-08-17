package com.hellblazer.luciferase.render.voxel.esvo;

import com.hellblazer.luciferase.render.voxel.esvo.gpu.*;
import com.hellblazer.luciferase.render.voxel.esvo.voxelization.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main ESVO pipeline orchestrating voxelization, octree construction, and GPU traversal.
 * Integrates all ESVO components into a unified rendering pipeline.
 */
public class ESVOPipeline {
    
    // Pipeline components
    private final TriangleVoxelizer voxelizer;
    private final AttributeInjector attributeInjector;
    private final OctreeBuilder octreeBuilder;
    private final ESVOBeamGPUTraversal gpuTraversal;
    private final ESVOMemoryManager memoryManager;
    
    // Configuration
    private final PipelineConfig config;
    
    // State
    private Octree currentOctree;
    private List<ESVONode> gpuNodes;
    private boolean initialized;
    
    // Performance monitoring
    private final PerformanceMonitor performanceMonitor;
    
    // Thread pool for async operations
    private final ExecutorService executor;
    
    public ESVOPipeline(PipelineConfig config) {
        this.config = config;
        this.voxelizer = new TriangleVoxelizer();
        this.attributeInjector = new AttributeInjector();
        this.octreeBuilder = new OctreeBuilder();
        this.gpuTraversal = new ESVOBeamGPUTraversal(
            config.getMaxBeams(), 
            config.getRaysPerBeam()
        );
        this.memoryManager = new ESVOMemoryManager(config.getMaxGPUMemory());
        this.performanceMonitor = new PerformanceMonitor();
        this.executor = Executors.newFixedThreadPool(config.getThreadCount());
        this.initialized = false;
    }
    
    /**
     * Initialize the pipeline and OpenGL resources
     */
    public void initialize() {
        if (initialized) {
            return;
        }
        
        // Check OpenGL context
        if (!GL.getCapabilities().OpenGL43) {
            throw new RuntimeException("OpenGL 4.3+ required for ESVO pipeline");
        }
        
        // Initialize GPU traversal
        gpuTraversal.setOctreeBounds(
            config.getOctreeMinX(), config.getOctreeMinY(), config.getOctreeMinZ(),
            config.getOctreeMaxX(), config.getOctreeMaxY(), config.getOctreeMaxZ()
        );
        
        // Initialize memory manager
        memoryManager.initialize();
        
        initialized = true;
    }
    
    /**
     * Process a mesh through the complete ESVO pipeline
     */
    public CompletableFuture<Octree> processMesh(TriangleMesh mesh) {
        return CompletableFuture.supplyAsync(() -> {
            performanceMonitor.startPhase("voxelization");
            
            // Phase 1: Voxelize the mesh
            var voxelConfig = new VoxelizationConfig()
                .withResolution(config.getVoxelResolution())
                .withBounds(
                    config.getOctreeMinX(), config.getOctreeMinY(), config.getOctreeMinZ(),
                    config.getOctreeMaxX(), config.getOctreeMaxY(), config.getOctreeMaxZ()
                )
                .withConservative(config.isConservativeVoxelization())
                .withComputeNormals(true);
            
            VoxelizationResult voxelResult = voxelizer.voxelizeMesh(mesh, voxelConfig);
            performanceMonitor.endPhase("voxelization");
            
            // Phase 2: Inject attributes
            performanceMonitor.startPhase("attribute_injection");
            AttributeResult attributeResult = attributeInjector.injectMeshAttributes(mesh, voxelResult);
            performanceMonitor.endPhase("attribute_injection");
            
            // Phase 3: Build octree
            performanceMonitor.startPhase("octree_construction");
            var octreeConfig = new OctreeConfig()
                .withMaxDepth(config.getMaxOctreeDepth())
                .withMinVoxelsPerNode(config.getMinVoxelsPerNode())
                .withBuildStrategy(config.getBuildStrategy())
                .withCompressHomogeneous(config.isCompressHomogeneous())
                .withStoreContours(config.isStoreContours())
                .withPageSize(ESVOPage.PAGE_BYTES)
                .withOptimizeLayout(true)
                .withLODLevels(config.getLODLevels())
                .withProgressiveEncoding(config.isProgressiveEncoding());
            
            Octree octree = octreeBuilder.buildOctree(voxelResult.getVoxels(), octreeConfig);
            performanceMonitor.endPhase("octree_construction");
            
            // Phase 4: Upload to GPU
            performanceMonitor.startPhase("gpu_upload");
            uploadToGPU(octree);
            performanceMonitor.endPhase("gpu_upload");
            
            currentOctree = octree;
            return octree;
        }, executor);
    }
    
    /**
     * Render the current octree with ray traversal
     */
    public void render(float[][] rayOrigins, float[][] rayDirections) {
        if (currentOctree == null || gpuNodes == null) {
            return;
        }
        
        performanceMonitor.startPhase("gpu_traversal");
        
        // Process rays through beam optimization
        float[] results = gpuTraversal.processRays(rayOrigins, rayDirections, gpuNodes);
        
        // Render results (simplified - would integrate with framebuffer)
        renderResults(results);
        
        performanceMonitor.endPhase("gpu_traversal");
        
        // Update statistics
        var stats = gpuTraversal.getStatistics();
        performanceMonitor.recordMetric("rays_processed", stats.totalRays);
        performanceMonitor.recordMetric("beam_efficiency", stats.getBeamEfficiency());
    }
    
    /**
     * Upload octree to GPU memory
     */
    private void uploadToGPU(Octree octree) {
        // Allocate GPU memory
        long requiredMemory = octree.getESVONodes().size() * 8L;
        if (!memoryManager.allocate(requiredMemory)) {
            throw new RuntimeException("Insufficient GPU memory for octree");
        }
        
        // Convert to GPU format
        gpuNodes = octree.getESVONodes();
        
        // Upload pages if available
        if (!octree.getPages().isEmpty()) {
            memoryManager.uploadPages(octree.getPages());
        }
    }
    
    /**
     * Render ray traversal results
     */
    private void renderResults(float[] results) {
        // Simplified rendering - would integrate with actual framebuffer
        GL11.glBegin(GL11.GL_POINTS);
        for (int i = 0; i < results.length; i += 4) {
            if (results[i + 3] > 0) { // Hit
                GL11.glColor3f(results[i], results[i + 1], results[i + 2]);
                GL11.glVertex3f(results[i], results[i + 1], results[i + 2]);
            }
        }
        GL11.glEnd();
    }
    
    /**
     * Update LOD based on view distance
     */
    public void updateLOD(float viewDistance) {
        if (currentOctree == null) {
            return;
        }
        
        int targetLOD = calculateTargetLOD(viewDistance);
        
        // Get nodes for target LOD
        List<ESVONode> lodNodes = currentOctree.getNodesAtLOD(targetLOD);
        
        // Update GPU with new LOD
        if (!lodNodes.isEmpty()) {
            gpuNodes = lodNodes;
        }
    }
    
    private int calculateTargetLOD(float viewDistance) {
        // Simple LOD calculation based on distance
        if (viewDistance < config.getLODDistance0()) return 0;
        if (viewDistance < config.getLODDistance1()) return 1;
        if (viewDistance < config.getLODDistance2()) return 2;
        return 3;
    }
    
    /**
     * Get performance statistics
     */
    public PerformanceMonitor.PerformanceReport getPerformanceReport() {
        return performanceMonitor.generateReport();
    }
    
    /**
     * Clean up resources
     */
    public void dispose() {
        if (gpuTraversal != null) {
            gpuTraversal.dispose();
        }
        if (memoryManager != null) {
            memoryManager.dispose();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }
    
    /**
     * Pipeline configuration
     */
    public static class PipelineConfig {
        private int voxelResolution = 256;
        private int maxOctreeDepth = 8;
        private int minVoxelsPerNode = 4;
        private BuildStrategy buildStrategy = BuildStrategy.TOP_DOWN;
        private boolean compressHomogeneous = true;
        private boolean storeContours = true;
        private int lodLevels = 4;
        private boolean progressiveEncoding = true;
        private boolean conservativeVoxelization = true;
        
        private float octreeMinX = -1, octreeMinY = -1, octreeMinZ = -1;
        private float octreeMaxX = 1, octreeMaxY = 1, octreeMaxZ = 1;
        
        private int maxBeams = 64;
        private int raysPerBeam = 32;
        private long maxGPUMemory = 1024 * 1024 * 1024; // 1GB
        private int threadCount = 4;
        
        private float lodDistance0 = 10;
        private float lodDistance1 = 50;
        private float lodDistance2 = 100;
        
        // Getters and fluent setters
        public int getVoxelResolution() { return voxelResolution; }
        public PipelineConfig withVoxelResolution(int resolution) {
            this.voxelResolution = resolution;
            return this;
        }
        
        public int getMaxOctreeDepth() { return maxOctreeDepth; }
        public PipelineConfig withMaxOctreeDepth(int depth) {
            this.maxOctreeDepth = depth;
            return this;
        }
        
        public int getMinVoxelsPerNode() { return minVoxelsPerNode; }
        public PipelineConfig withMinVoxelsPerNode(int min) {
            this.minVoxelsPerNode = min;
            return this;
        }
        public BuildStrategy getBuildStrategy() { return buildStrategy; }
        public boolean isCompressHomogeneous() { return compressHomogeneous; }
        public boolean isStoreContours() { return storeContours; }
        public int getLODLevels() { return lodLevels; }
        public boolean isProgressiveEncoding() { return progressiveEncoding; }
        public boolean isConservativeVoxelization() { return conservativeVoxelization; }
        
        public float getOctreeMinX() { return octreeMinX; }
        public float getOctreeMinY() { return octreeMinY; }
        public float getOctreeMinZ() { return octreeMinZ; }
        public float getOctreeMaxX() { return octreeMaxX; }
        public float getOctreeMaxY() { return octreeMaxY; }
        public float getOctreeMaxZ() { return octreeMaxZ; }
        
        public int getMaxBeams() { return maxBeams; }
        public int getRaysPerBeam() { return raysPerBeam; }
        public long getMaxGPUMemory() { return maxGPUMemory; }
        public PipelineConfig withMaxGPUMemory(long memory) {
            this.maxGPUMemory = memory;
            return this;
        }
        public int getThreadCount() { return threadCount; }
        public PipelineConfig withThreadCount(int threads) {
            this.threadCount = threads;
            return this;
        }
        
        public float getLODDistance0() { return lodDistance0; }
        public float getLODDistance1() { return lodDistance1; }
        public float getLODDistance2() { return lodDistance2; }
    }
}