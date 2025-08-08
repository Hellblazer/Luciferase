# ESVO Java Implementation Plan

## Project Overview

This document provides a detailed implementation plan for translating the Efficient Sparse Voxel Octrees (ESVO) system to Java 24 in the render module. The plan leverages Java 24's Foreign Function & Memory (FFM) API for zero-copy GPU operations and native interop. The implementation follows a phased approach with clear milestones and validation criteria.

## Implementation Timeline

**Total Duration**: 16 weeks
- Phase 1: Core Infrastructure (2 weeks)
- Phase 2: Data Structures & I/O (2 weeks)
- Phase 3: Voxelization Pipeline (3 weeks)
- Phase 4: Building Pipeline (3 weeks)
- Phase 5: GPU Integration (4 weeks)
- Phase 6: Optimization & Polish (2 weeks)

## Phase 1: Core Infrastructure (Weeks 1-2)

### Objectives
- Set up project structure and dependencies
- Implement core math utilities
- Create memory management foundation
- Establish testing framework

### Tasks

#### Week 1: Project Setup and Math Library

**1.1 Project Configuration**
```xml
<!-- render/pom.xml additions -->
<dependencies>
    <!-- Math -->
    <dependency>
        <groupId>javax.vecmath</groupId>
        <artifactId>vecmath</artifactId>
        <version>1.5.2</version>
    </dependency>
    
    <!-- High-performance collections -->
    <dependency>
        <groupId>it.unimi.dsi</groupId>
        <artifactId>fastutil</artifactId>
        <version>8.5.12</version>
    </dependency>
    
    <!-- GPU Framework: WebGPU (primary) -->
    <dependency>
        <groupId>org.lwjgl</groupId>
        <artifactId>lwjgl-webgpu</artifactId>
        <version>3.3.3</version>
    </dependency>
    <dependency>
        <groupId>org.lwjgl</groupId>
        <artifactId>lwjgl-webgpu</artifactId>
        <version>3.3.3</version>
        <classifier>${lwjgl.natives}</classifier>
    </dependency>
    
    <!-- CUDA Fallback (optional) -->
    <dependency>
        <groupId>org.jcuda</groupId>
        <artifactId>jcuda</artifactId>
        <version>11.8.0</version>
        <optional>true</optional>
    </dependency>
    
    <!-- Compression -->
    <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-compress</artifactId>
        <version>1.24.0</version>
    </dependency>
</dependencies>
```

**1.2 Math Utilities**
```java
package com.hellblazer.luciferase.render.util;

public class VoxelMortonEncoder {
    // 23-bit coordinate system
    private static final int COORD_BITS = 23;
    private static final long COORD_MASK = (1L << COORD_BITS) - 1;
    
    public static long encode(float x, float y, float z, byte level) {
        // Convert to 23-bit fixed point
        int ix = (int)(x * (1 << COORD_BITS));
        int iy = (int)(y * (1 << COORD_BITS));
        int iz = (int)(z * (1 << COORD_BITS));
        
        // Shift for level
        int shift = COORD_BITS - level;
        ix >>= shift;
        iy >>= shift;
        iz >>= shift;
        
        // Interleave bits
        return interleaveBits(ix, iy, iz);
    }
}
```

**1.3 Coordinate System**
```java
public class VoxelCoordinates {
    public static final int UNIT_SCALE = 23;
    public static final float UNIT_SIZE = 1.0f / (1 << UNIT_SCALE);
    
    public static int toFixed23(float value) {
        return (int)(value * (1 << UNIT_SCALE));
    }
    
    public static float fromFixed23(int fixed) {
        return fixed / (float)(1 << UNIT_SCALE);
    }
}
```

#### Week 2: Memory Management with Foreign Function & Memory API

**2.1 Native Page Allocator (FFM)**
```java
import java.lang.foreign.*;
import java.lang.invoke.*;

public class NativePageAllocator {
    private static final long PAGE_SIZE = 8192; // 8KB
    private static final int PAGE_ALIGNMENT = 4096; // 4KB alignment for GPU
    private final Arena arena;
    private final Queue<MemorySegment> freePages = new ConcurrentLinkedQueue<>();
    private final AtomicLong allocatedBytes = new AtomicLong();
    
    public NativePageAllocator() {
        // Use confined arena for explicit lifecycle control
        this.arena = Arena.ofConfined();
    }
    
    public MemorySegment allocatePage() {
        MemorySegment page = freePages.poll();
        if (page == null) {
            // Allocate aligned native memory for GPU compatibility
            page = arena.allocate(PAGE_SIZE, PAGE_ALIGNMENT);
            allocatedBytes.addAndGet(PAGE_SIZE);
        }
        // Clear memory
        page.fill((byte) 0);
        return page;
    }
    
    public void releasePage(MemorySegment page) {
        if (page.byteSize() == PAGE_SIZE) {
            freePages.offer(page);
        }
    }
    
    // Get native address for GPU mapping
    public long getNativeAddress(MemorySegment page) {
        return page.address();
    }
}
```

**2.2 Memory Pools**
```java
public class VoxelMemoryPools {
    private final PageAllocator pageAllocator = new PageAllocator();
    private final ObjectPool<VoxelData> voxelPool;
    private final ObjectPool<TriangleData> trianglePool;
    
    public VoxelMemoryPools() {
        this.voxelPool = new ObjectPool<>(VoxelData::new, 65536);
        this.trianglePool = new ObjectPool<>(TriangleData::new, 262144);
    }
}
```

**2.3 Testing Framework**
```java
@TestClass
public class VoxelMortonEncoderTest {
    @Test
    void testEncoding() {
        long morton = VoxelMortonEncoder.encode(0.5f, 0.5f, 0.5f, (byte)10);
        // Verify encoding
    }
    
    @Test 
    void testDecoding() {
        float[] coords = VoxelMortonEncoder.decode(0x123456789L, (byte)10);
        // Verify decoding
    }
}
```

### Deliverables
- Working math library with Morton encoding
- Page-based memory allocator
- Unit tests for core utilities
- Performance benchmarks

## Phase 2: Data Structures & I/O (Weeks 3-4)

### Objectives
- Implement voxel octree node structure
- Create file format readers/writers
- Build slice management system
- Implement compression codecs

### Tasks

#### Week 3: Core Data Structures

**3.1 Voxel Node Structure**
```java
public class VoxelOctreeNode {
    // Packed into 8 bytes
    private long data;
    
    // Bit layout:
    // [0-7]   Valid mask
    // [8-15]  Non-leaf mask  
    // [16]    Far pointer flag
    // [17-31] Child pointer (15 bits)
    // [32-39] Contour mask
    // [40-63] Contour pointer (24 bits)
    
    public byte getValidMask() {
        return (byte)(data & 0xFF);
    }
    
    public boolean isLeaf() {
        return (data & 0xFF00) == 0;
    }
    
    public int getChildPointer() {
        return (int)((data >> 17) & 0x7FFF);
    }
}
```

**3.2 Voxel Attachment Data**
```java
public interface VoxelAttachment {
    AttachmentType getType();
    ByteBuffer serialize();
    void deserialize(ByteBuffer buffer);
}

public class ColorNormalDXT implements VoxelAttachment {
    private short color0, color1;  // DXT1 reference colors
    private int colorBits;         // 2 bits per voxel
    private long normalBits;       // Normal encoding
}
```

**3.3 Slice Structure**
```java
public class VoxelSlice {
    private final int id;
    private final Vec3i cubePos;
    private final int cubeScale;
    private final int nodeScale;
    
    private ByteBuffer nodeData;
    private Map<AttachmentType, ByteBuffer> attachments;
    private BitSet splitMask;
    
    public void writeTo(ByteBuffer buffer) {
        // Serialize slice data
    }
}
```

#### Week 4: File I/O

**4.1 Clustered File Format**
```java
public class ClusteredFile {
    private static final String FORMAT_ID = "Clusters";
    private static final int FORMAT_VERSION = 2;
    
    public void write(Path path, List<ClusterData> clusters) {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            // Write header
            ByteBuffer header = ByteBuffer.allocate(32)
                .order(ByteOrder.LITTLE_ENDIAN);
            header.put(FORMAT_ID.getBytes());
            header.putInt(FORMAT_VERSION);
            header.putInt(clusters.size());
            // ... write remaining header
            
            // Write chunks with compression
            for (ClusterData cluster : clusters) {
                writeCluster(file, cluster);
            }
        }
    }
}
```

**4.2 Octree File Format**
```java
public class OctreeFile {
    private static final String FORMAT_ID = "Octree  ";
    
    public static class Header {
        String formatId = FORMAT_ID;
        int version = 1;
        int numObjects;
        int numSlices;
    }
    
    public void writeOctree(Path path, VoxelOctree octree) {
        try (FileChannel channel = FileChannel.open(path, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.WRITE)) {
            
            // Memory map the file
            MappedByteBuffer buffer = channel.map(
                FileChannel.MapMode.READ_WRITE, 0, estimateSize(octree));
                
            // Write header and data
            writeHeader(buffer, octree);
            writeSlices(buffer, octree);
        }
    }
}
```

**4.3 Compression Support**
```java
public class CompressionCodec {
    public ByteBuffer compress(ByteBuffer input, CompressionType type) {
        switch (type) {
            case NONE:
                return input;
            case ZLIB_LOW:
            case ZLIB_MEDIUM:
            case ZLIB_HIGH:
                return zlibCompress(input, type.getLevel());
            default:
                throw new UnsupportedOperationException();
        }
    }
    
    private ByteBuffer zlibCompress(ByteBuffer input, int level) {
        Deflater deflater = new Deflater(level);
        deflater.setInput(input);
        deflater.finish();
        
        ByteBuffer output = ByteBuffer.allocate(input.remaining() * 2);
        deflater.deflate(output);
        output.flip();
        return output;
    }
}
```

### Deliverables
- Complete voxel node implementation
- Working file I/O for all formats
- Compression/decompression support
- Slice serialization/deserialization

## Phase 3: Voxelization Pipeline (Weeks 5-7)

### Objectives
- Implement triangle-box intersection
- Create multi-threaded voxelization
- Build quality metrics system
- Implement attribute collection

### Tasks

#### Week 5: Core Voxelization

**5.1 Triangle-Box Intersection**
```java
public class TriangleBoxIntersection {
    public static boolean intersects(
            Vec3f v0, Vec3f v1, Vec3f v2,
            Vec3f boxCenter, Vec3f boxHalfSize) {
        
        // Move box to origin
        Vec3f tv0 = v0.subtract(boxCenter);
        Vec3f tv1 = v1.subtract(boxCenter);
        Vec3f tv2 = v2.subtract(boxCenter);
        
        // Test 1: AABB test
        Vec3f min = Vec3f.min(tv0, Vec3f.min(tv1, tv2));
        Vec3f max = Vec3f.max(tv0, Vec3f.max(tv1, tv2));
        
        if (min.x > boxHalfSize.x || max.x < -boxHalfSize.x) return false;
        if (min.y > boxHalfSize.y || max.y < -boxHalfSize.y) return false;
        if (min.z > boxHalfSize.z || max.z < -boxHalfSize.z) return false;
        
        // Test 2: Plane test
        Vec3f normal = tv1.subtract(tv0).cross(tv2.subtract(tv0));
        float d = -normal.dot(tv0);
        if (!planeBoxOverlap(normal, d, boxHalfSize)) return false;
        
        // Test 3: Edge tests (9 tests)
        return testEdges(tv0, tv1, tv2, boxHalfSize);
    }
}
```

**5.2 Triangle Clipping**
```java
public class TriangleClipper {
    public static class ClipResult {
        public final Vec2f[] vertices;  // Barycentric coordinates
        public final int count;
    }
    
    public static ClipResult clipToBox(
            Vec3f v0, Vec3f v1, Vec3f v2,
            Vec3f boxMin, Vec3f boxMax) {
        
        // Start with full triangle in barycentric space
        List<Vec2f> polygon = Arrays.asList(
            new Vec2f(1, 0),  // v0
            new Vec2f(0, 1),  // v1  
            new Vec2f(0, 0)   // v2
        );
        
        // Clip against each plane
        for (int axis = 0; axis < 3; axis++) {
            polygon = clipAgainstPlane(polygon, axis, boxMin.get(axis), false);
            polygon = clipAgainstPlane(polygon, axis, boxMax.get(axis), true);
        }
        
        return new ClipResult(polygon.toArray(new Vec2f[0]), polygon.size());
    }
}
```

#### Week 6: Multi-threaded Processing

**6.1 Voxelization Task**
```java
public class VoxelizationTask implements Callable<VoxelSlice> {
    private final List<Triangle> triangles;
    private final BoundingBox bounds;
    private final int level;
    
    @Override
    public VoxelSlice call() {
        VoxelSlice slice = new VoxelSlice(bounds, level);
        
        // Voxelize each triangle
        for (Triangle tri : triangles) {
            voxelizeTriangle(tri, slice);
        }
        
        // Filter attributes
        filterSliceAttributes(slice);
        
        return slice;
    }
    
    private void voxelizeTriangle(Triangle tri, VoxelSlice slice) {
        // Find voxels intersecting triangle
        BoundingBox triBounds = tri.getBounds();
        
        for (int x = triBounds.minX; x <= triBounds.maxX; x++) {
            for (int y = triBounds.minY; y <= triBounds.maxY; y++) {
                for (int z = triBounds.minZ; z <= triBounds.maxZ; z++) {
                    if (intersectsVoxel(tri, x, y, z)) {
                        addVoxelData(slice, x, y, z, tri);
                    }
                }
            }
        }
    }
}
```

**6.2 Thread Pool Management**
```java
public class VoxelizationPipeline {
    private final int numThreads;
    private final ExecutorService executor;
    
    public VoxelOctree voxelize(TriangleMesh mesh, BuildParams params) {
        // Spatial subdivision for load balancing
        List<SpatialRegion> regions = subdivideSpace(mesh.getBounds());
        
        // Create tasks
        List<Future<VoxelSlice>> futures = new ArrayList<>();
        for (SpatialRegion region : regions) {
            List<Triangle> regionTriangles = mesh.getTrianglesIn(region);
            VoxelizationTask task = new VoxelizationTask(
                regionTriangles, region.bounds, params.maxLevel);
            futures.add(executor.submit(task));
        }
        
        // Collect results
        VoxelOctree octree = new VoxelOctree();
        for (Future<VoxelSlice> future : futures) {
            VoxelSlice slice = future.get();
            octree.addSlice(slice);
        }
        
        return octree;
    }
}
```

#### Week 7: Quality Metrics

**7.1 Quality Metrics**
```java
public class QualityMetrics {
    private final float colorDeviation;
    private final float normalDeviation;
    private final float contourDeviation;
    
    public boolean needsSubdivision(VoxelData voxel) {
        // Check color variation
        float colorRange = voxel.getColorMax().distance(voxel.getColorMin());
        if (colorRange > colorDeviation) return true;
        
        // Check normal variation
        float normalAngle = voxel.getNormalMax().angle(voxel.getNormalMin());
        if (normalAngle > normalDeviation) return true;
        
        // Check contour approximation
        if (voxel.hasContour() && voxel.getContourError() > contourDeviation) {
            return true;
        }
        
        return false;
    }
}
```

**7.2 Attribute Collection**
```java
public class AttributeCollector {
    public void collectAttributes(VoxelData voxel, Triangle tri, Vec2f[] baryCoords) {
        // Interpolate colors
        Color avgColor = interpolateColors(tri, baryCoords);
        voxel.addColor(avgColor);
        
        // Interpolate normals
        Vec3f avgNormal = interpolateNormals(tri, baryCoords);
        voxel.addNormal(avgNormal);
        
        // Update bounds
        voxel.updateColorBounds(avgColor);
        voxel.updateNormalBounds(avgNormal);
    }
}
```

### Deliverables
- Working triangle-box intersection
- Multi-threaded voxelization pipeline
- Quality metrics implementation
- Attribute interpolation system

## Phase 4: Building Pipeline (Weeks 8-10)

### Objectives
- Implement octree construction
- Create attribute filtering
- Build contour extraction
- Implement progressive refinement

### Tasks

#### Week 8: Octree Construction

**8.1 Octree Builder**
```java
public class VoxelOctreeBuilder {
    private final QualityMetrics metrics;
    private final AttributeFilter filter;
    
    public VoxelOctree build(VoxelSlice rootSlice) {
        VoxelOctree octree = new VoxelOctree();
        
        // Recursive subdivision
        Queue<BuildNode> workQueue = new LinkedList<>();
        workQueue.offer(new BuildNode(rootSlice, 0));
        
        while (!workQueue.isEmpty()) {
            BuildNode node = workQueue.poll();
            
            if (shouldSubdivide(node)) {
                List<BuildNode> children = subdivide(node);
                workQueue.addAll(children);
            } else {
                octree.addLeafNode(node);
            }
        }
        
        return octree;
    }
    
    private boolean shouldSubdivide(BuildNode node) {
        if (node.level >= maxLevel) return false;
        
        for (VoxelData voxel : node.slice.getVoxels()) {
            if (metrics.needsSubdivision(voxel)) {
                return true;
            }
        }
        
        return false;
    }
}
```

**8.2 Node Packing**
```java
public class NodePacker {
    public VoxelOctreeNode packNode(BuildNode node) {
        VoxelOctreeNode packed = new VoxelOctreeNode();
        
        // Set valid mask
        byte validMask = 0;
        for (int i = 0; i < 8; i++) {
            if (node.hasChild(i)) {
                validMask |= (1 << i);
            }
        }
        packed.setValidMask(validMask);
        
        // Set non-leaf mask
        byte nonLeafMask = 0;
        for (int i = 0; i < 8; i++) {
            if (node.hasChild(i) && !node.getChild(i).isLeaf()) {
                nonLeafMask |= (1 << i);
            }
        }
        packed.setNonLeafMask(nonLeafMask);
        
        // Set pointers
        if (node.hasChildren()) {
            packed.setChildPointer(allocateChildren(node));
        }
        
        return packed;
    }
}
```

#### Week 9: Attribute Filtering

**9.1 Filter Implementations**
```java
public abstract class AttributeFilter {
    public abstract VoxelData filter(VoxelData[] neighborhood);
}

public class BoxFilter extends AttributeFilter {
    private final int radius;
    
    @Override
    public VoxelData filter(VoxelData[] neighborhood) {
        Color sumColor = new Color(0, 0, 0);
        Vec3f sumNormal = new Vec3f(0, 0, 0);
        int count = 0;
        
        for (VoxelData neighbor : neighborhood) {
            if (neighbor != null) {
                sumColor.add(neighbor.getColor());
                sumNormal.add(neighbor.getNormal());
                count++;
            }
        }
        
        if (count > 0) {
            sumColor.scale(1.0f / count);
            sumNormal.normalize();
        }
        
        return new VoxelData(sumColor, sumNormal);
    }
}

public class PyramidFilter extends AttributeFilter {
    private static final float[][] WEIGHTS = {
        {1.0f, 0.75f, 0.5f},
        {0.75f, 0.5f, 0.25f},
        {0.5f, 0.25f, 0.125f}
    };
    
    @Override
    public VoxelData filter(VoxelData[] neighborhood) {
        // Weighted average based on distance
        // Implementation...
    }
}
```

**9.2 DXT Compression**
```java
public class DXTCompressor {
    public ColorNormalDXT compress(VoxelData[] voxels) {
        // Find reference colors (furthest apart)
        Color[] colors = extractColors(voxels);
        int[] refs = findReferenceColors(colors);
        
        // Create palette
        Color[] palette = new Color[4];
        palette[0] = colors[refs[0]];
        palette[1] = colors[refs[1]];
        palette[2] = Color.lerp(palette[0], palette[1], 2.0f/3.0f);
        palette[3] = Color.lerp(palette[0], palette[1], 1.0f/3.0f);
        
        // Encode indices
        int colorBits = 0;
        for (int i = 0; i < voxels.length; i++) {
            int closest = findClosestColor(voxels[i].getColor(), palette);
            colorBits |= (closest << (i * 2));
        }
        
        // Similar for normals...
        
        return new ColorNormalDXT(
            toRGB565(palette[0]),
            toRGB565(palette[1]),
            colorBits,
            normalBits
        );
    }
}
```

#### Week 10: Contour Extraction

**10.1 Contour Shaper**
```java
public class ContourShaper {
    private final List<Plane> planes = new ArrayList<>();
    
    public void addTriangle(Triangle tri, float weight) {
        // Add triangle plane
        Vec3f normal = tri.getNormal();
        float distance = normal.dot(tri.v0);
        planes.add(new Plane(normal, distance, weight));
        
        // Add edge planes
        for (int i = 0; i < 3; i++) {
            Vec3f edge = tri.getEdge(i);
            Vec3f edgeNormal = normal.cross(edge).normalized();
            float edgeDist = edgeNormal.dot(tri.getVertex(i));
            planes.add(new Plane(edgeNormal, edgeDist, weight * 0.5f));
        }
    }
    
    public Contour extractContour() {
        // Find best-fit plane through weighted planes
        return fitContourToPlanes(planes);
    }
}
```

**10.2 Contour Encoding**
```java
public class ContourEncoder {
    public int encode(Contour contour) {
        // Quantize normal (6 bits per component)
        int nx = quantizeNormal(contour.normal.x);
        int ny = quantizeNormal(contour.normal.y);
        int nz = quantizeNormal(contour.normal.z);
        
        // Quantize position (7 bits)
        int pos = quantizePosition(contour.position);
        
        // Quantize thickness (7 bits)
        int thick = quantizeThickness(contour.thickness);
        
        // Pack into 32 bits
        return nz | (ny << 6) | (nx << 12) | (pos << 18) | (thick << 25);
    }
    
    private int quantizeNormal(float component) {
        return ((int)((component + 1.0f) * 31.5f)) ^ 32;
    }
}
```

### Deliverables
- Complete octree construction
- Working attribute filters
- DXT compression implementation
- Contour extraction and encoding

## Phase 5: GPU Integration (Weeks 11-14)

### Objectives
- Select and integrate GPU framework
- Implement GPU memory management
- Create ray tracing kernels
- Build rendering pipeline

### Tasks

#### Week 11: GPU Framework Setup (WebGPU + FFM)

**11.1 WebGPU Integration**
```java
import org.lwjgl.webgpu.*;
import static org.lwjgl.webgpu.WebGPU.*;
import java.lang.foreign.*;

public class WebGPUContext {
    private long instance;
    private long adapter;
    private long device;
    private long queue;
    private Map<String, Long> shaderModules = new HashMap<>();
    
    public void initialize() {
        // Initialize WebGPU
        WGPUInstanceDescriptor instanceDesc = WGPUInstanceDescriptor.calloc();
        instance = wgpuCreateInstance(instanceDesc);
        
        // Request adapter with high performance preference
        WGPURequestAdapterOptions adapterOpts = WGPURequestAdapterOptions.calloc()
            .powerPreference(WGPUPowerPreference_HighPerformance);
        
        adapter = wgpuInstanceRequestAdapter(instance, adapterOpts);
        
        // Request device with required features
        WGPUDeviceDescriptor deviceDesc = WGPUDeviceDescriptor.calloc()
            .requiredFeatures(WGPUFeatureName_TimestampQuery);
        
        device = wgpuAdapterRequestDevice(adapter, deviceDesc);
        queue = wgpuDeviceGetQueue(device);
    }
    
    public long createComputePipeline(String shaderCode, String entryPoint) {
        // Create shader module from WGSL code
        WGPUShaderModuleDescriptor shaderDesc = WGPUShaderModuleDescriptor.calloc()
            .code(shaderCode);
        
        long shaderModule = wgpuDeviceCreateShaderModule(device, shaderDesc);
        shaderModules.put(entryPoint, shaderModule);
        
        // Create compute pipeline
        WGPUComputePipelineDescriptor pipelineDesc = WGPUComputePipelineDescriptor.calloc()
            .compute(comp -> comp
                .module(shaderModule)
                .entryPoint(entryPoint)
            );
        
        return wgpuDeviceCreateComputePipeline(device, pipelineDesc);
    }
}
```

**11.2 GPU Memory Manager with FFM Bridge**
```java
public class WebGPUMemoryManager {
    private final WebGPUContext context;
    private final Arena arena;
    private final long maxMemory;
    private final Map<String, GPUBuffer> allocations = new HashMap<>();
    private long usedMemory = 0;
    
    record GPUBuffer(long handle, MemorySegment memory) {}
    
    public GPUBuffer allocate(String name, long size) {
        if (usedMemory + size > maxMemory) {
            throw new OutOfMemoryError("GPU memory limit exceeded");
        }
        
        // Allocate FFM memory segment
        MemorySegment memory = arena.allocate(size, 256); // 256-byte alignment
        
        // Create WebGPU buffer descriptor
        WGPUBufferDescriptor desc = WGPUBufferDescriptor.calloc()
            .size(size)
            .usage(WGPUBufferUsage_Storage | WGPUBufferUsage_CopyDst)
            .mappedAtCreation(true);
        
        // Create GPU buffer
        long buffer = wgpuDeviceCreateBuffer(context.device, desc);
        
        // Map buffer and copy initial data if needed
        long mappedData = wgpuBufferGetMappedRange(buffer, 0, size);
        
        GPUBuffer gpuBuffer = new GPUBuffer(buffer, memory);
        allocations.put(name, gpuBuffer);
        usedMemory += size;
        
        return gpuBuffer;
    }
    
    public void uploadToGPU(GPUBuffer buffer, MemorySegment data) {
        // Write to staging buffer then copy to GPU
        wgpuQueueWriteBuffer(context.queue, buffer.handle(), 0, 
                             data.address(), data.byteSize());
    }
}
```

#### Week 12: Ray Tracing Kernel

**12.1 Compute Shader Implementation (WGSL)**
```wgsl
// octree_traversal.wgsl
struct OctreeNode {
    validMask: u32,
    childData: u32,
    attachmentData: vec2<u32>
}

struct Ray {
    origin: vec3<f32>,
    direction: vec3<f32>
}

@group(0) @binding(0) var<storage, read> octreeNodes: array<OctreeNode>;
@group(0) @binding(1) var<storage, read> rays: array<Ray>;
@group(0) @binding(2) var<storage, write> results: array<f32>;
@group(0) @binding(3) var<uniform> params: RaycastParams;

@compute @workgroup_size(64)
fn castRays(@builtin(global_invocation_id) id: vec3<u32>) {
    let rayId = id.x;
    if (rayId >= params.numRays) { return; }
    
    let ray = rays[rayId];
    
    // Traverse octree using stack-based algorithm
    var t = traverseOctree(octreeNodes, ray);
    
    // Store result
    results[rayId] = t;
}

fn traverseOctree(nodes: array<OctreeNode>, ray: Ray) -> f32 {
    var stack: array<u32, 32>;
    var stackPtr = 0u;
    
    // Initialize with root
    stack[stackPtr] = 0u;
    stackPtr++;
    
    var closestT = 1e20;
    
    // Stack-based traversal
    while (stackPtr > 0u) {
        stackPtr--;
        let nodeIdx = stack[stackPtr];
        let node = nodes[nodeIdx];
        
        // Ray-box intersection test
        let bounds = getNodeBounds(nodeIdx);
        let t = rayBoxIntersection(ray, bounds);
        
        if (t.hit && t.tMin < closestT) {
            if (isLeaf(node)) {
                closestT = min(closestT, t.tMin);
            } else {
                // Push children in front-to-back order
                pushChildren(&stack, &stackPtr, node, ray);
            }
        }
    }
    
    return closestT;
}
```

**12.2 Java WebGPU Ray Tracer**
```java
public class WebGPURayTracer {
    private final WebGPUContext context;
    private final WebGPUMemoryManager memory;
    private final long computePipeline;
    private final long bindGroupLayout;
    
    public WebGPURayTracer(WebGPUContext context) {
        this.context = context;
        this.memory = new WebGPUMemoryManager(context);
        
        // Load WGSL shader
        String shaderCode = loadShaderCode("octree_traversal.wgsl");
        this.computePipeline = context.createComputePipeline(shaderCode, "castRays");
        
        // Create bind group layout
        this.bindGroupLayout = createBindGroupLayout();
    }
    
    public float[] trace(Ray[] rays, VoxelOctree octree) {
        int numRays = rays.length;
        
        // Pack ray data into FFM memory
        MemorySegment rayData = packRaysFFM(rays);
        
        // Allocate GPU buffers
        var octreeBuffer = memory.allocate("octree", octree.getByteSize());
        var rayBuffer = memory.allocate("rays", rayData.byteSize());
        var resultBuffer = memory.allocate("results", numRays * 4);
        
        // Upload data using zero-copy from FFM
        memory.uploadToGPU(octreeBuffer, octree.getNativeMemory());
        memory.uploadToGPU(rayBuffer, rayData);
        
        // Create bind group
        long bindGroup = createBindGroup(octreeBuffer, rayBuffer, resultBuffer);
        
        // Create command encoder
        long encoder = wgpuDeviceCreateCommandEncoder(context.device, null);
        long computePass = wgpuCommandEncoderBeginComputePass(encoder, null);
        
        // Set pipeline and bind groups
        wgpuComputePassEncoderSetPipeline(computePass, computePipeline);
        wgpuComputePassEncoderSetBindGroup(computePass, 0, bindGroup, 0, null);
        
        // Dispatch compute workgroups
        int workgroupSize = 64;
        int numWorkgroups = (numRays + workgroupSize - 1) / workgroupSize;
        wgpuComputePassEncoderDispatchWorkgroups(computePass, numWorkgroups, 1, 1);
        
        // Submit and wait
        wgpuComputePassEncoderEnd(computePass);
        long commands = wgpuCommandEncoderFinish(encoder, null);
        wgpuQueueSubmit(context.queue, 1, commands);
        
        // Read results back
        return readResultsFFM(resultBuffer, numRays);
    }
    
    private MemorySegment packRaysFFM(Ray[] rays) {
        // Use FFM to create GPU-compatible ray data
        var layout = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_FLOAT).withName("origin"),
            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_FLOAT).withName("direction")
        );
        
        MemorySegment segment = arena.allocate(layout, rays.length);
        
        // Pack rays using VarHandles for performance
        var originHandle = layout.varHandle(MemoryLayout.PathElement.groupElement("origin"),
                                           MemoryLayout.PathElement.sequenceElement());
        var dirHandle = layout.varHandle(MemoryLayout.PathElement.groupElement("direction"),
                                        MemoryLayout.PathElement.sequenceElement());
        
        for (int i = 0; i < rays.length; i++) {
            Ray ray = rays[i];
            long offset = i * layout.byteSize();
            
            originHandle.set(segment, offset, 0L, ray.origin.x);
            originHandle.set(segment, offset, 1L, ray.origin.y);
            originHandle.set(segment, offset, 2L, ray.origin.z);
            
            dirHandle.set(segment, offset, 0L, ray.direction.x);
            dirHandle.set(segment, offset, 1L, ray.direction.y);
            dirHandle.set(segment, offset, 2L, ray.direction.z);
        }
        
        return segment;
    }
}
```

#### Week 13: Octree GPU Format

**13.1 GPU Node Format**
```java
public class GPUOctreeConverter {
    public ByteBuffer convertToGPU(VoxelOctree octree) {
        // Calculate total size
        int nodeCount = octree.getNodeCount();
        int bufferSize = nodeCount * 8; // 8 bytes per node
        
        ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize)
                                     .order(ByteOrder.LITTLE_ENDIAN);
        
        // Convert nodes to GPU format
        for (VoxelOctreeNode node : octree.getAllNodes()) {
            buffer.putLong(node.getData());
        }
        
        return buffer;
    }
}
```

**13.2 Streaming Support**
```java
public class StreamingOctree {
    private final int maxGPUNodes;
    private final LRUCache<Integer, GPUSlice> gpuCache;
    
    public void updateVisibleSlices(Frustum frustum) {
        // Find visible slices
        Set<Integer> visibleSlices = findVisibleSlices(frustum);
        
        // Evict non-visible slices
        for (Integer sliceId : gpuCache.keySet()) {
            if (!visibleSlices.contains(sliceId)) {
                evictSlice(sliceId);
            }
        }
        
        // Load new visible slices
        for (Integer sliceId : visibleSlices) {
            if (!gpuCache.containsKey(sliceId)) {
                loadSliceToGPU(sliceId);
            }
        }
    }
}
```

#### Week 14: Rendering Pipeline

**14.1 Renderer Integration**
```java
public class VoxelRenderer {
    private final RayTracer rayTracer;
    private final VoxelOctree octree;
    private final PostProcessor postProcessor;
    
    public BufferedImage render(Camera camera, int width, int height) {
        // Generate primary rays
        Ray[] rays = camera.generateRays(width, height);
        
        // Trace rays
        float[] depths = rayTracer.trace(rays, octree);
        
        // Shade pixels
        Color[] colors = shade(rays, depths, octree);
        
        // Post-process
        colors = postProcessor.process(colors, width, height);
        
        // Convert to image
        return createImage(colors, width, height);
    }
}
```

**14.2 Shading**
```java
public class VoxelShader {
    public Color shade(Ray ray, float hitDistance, VoxelData voxel) {
        // Calculate hit point
        Vec3f hitPoint = ray.origin.add(ray.direction.scale(hitDistance));
        
        // Get voxel data
        Color baseColor = voxel.getColor();
        Vec3f normal = voxel.getNormal();
        
        // Simple lighting
        Vec3f lightDir = new Vec3f(0.5f, 0.7f, 0.6f).normalized();
        float diffuse = Math.max(0, normal.dot(lightDir));
        
        // Ambient + diffuse
        return baseColor.scale(0.3f + 0.7f * diffuse);
    }
}
```

### Deliverables
- Working GPU integration (JCuda or alternative)
- Ray tracing kernel implementation
- GPU memory management
- Complete rendering pipeline

## Phase 6: Optimization & Polish (Weeks 15-16)

### Objectives
- Performance optimization
- Memory optimization
- Testing and validation
- Documentation

### Tasks

#### Week 15: Performance Optimization

**15.1 Profiling**
```java
public class PerformanceProfiler {
    private final Map<String, TimingStats> timings = new HashMap<>();
    
    public void profile(String operation, Runnable task) {
        long start = System.nanoTime();
        task.run();
        long elapsed = System.nanoTime() - start;
        
        timings.computeIfAbsent(operation, k -> new TimingStats())
               .addSample(elapsed);
    }
    
    public void report() {
        for (Map.Entry<String, TimingStats> entry : timings.entrySet()) {
            System.out.printf("%s: avg=%.2fms, min=%.2fms, max=%.2fms%n",
                entry.getKey(),
                entry.getValue().getAverage() / 1_000_000.0,
                entry.getValue().getMin() / 1_000_000.0,
                entry.getValue().getMax() / 1_000_000.0
            );
        }
    }
}
```

**15.2 Cache Optimization**
```java
public class OctreeCacheOptimizer {
    public void optimizeNodeLayout(VoxelOctree octree) {
        // Reorder nodes for better cache locality
        List<VoxelOctreeNode> nodes = octree.getAllNodes();
        List<VoxelOctreeNode> optimized = new ArrayList<>();
        
        // Depth-first ordering for better traversal
        reorderDepthFirst(octree.getRoot(), optimized);
        
        // Update pointers
        updatePointers(optimized);
        
        octree.setNodes(optimized);
    }
}
```

#### Week 16: Testing and Validation

**16.1 Validation Tests**
```java
@Test
public void testFileFormatCompatibility() {
    // Load reference octree from C++ implementation
    VoxelOctree reference = OctreeFile.load("reference.oct");
    
    // Build same scene in Java
    VoxelOctree java = buildTestScene();
    
    // Compare structure
    assertEquals(reference.getNodeCount(), java.getNodeCount());
    assertOctreeStructureEquals(reference, java);
}

@Test
public void testRenderingAccuracy() {
    // Render test scene
    BufferedImage rendered = renderer.render(testCamera, 512, 512);
    
    // Load reference image
    BufferedImage reference = ImageIO.read(new File("reference.png"));
    
    // Compare pixels
    double psnr = calculatePSNR(rendered, reference);
    assertTrue("PSNR too low: " + psnr, psnr > 30.0);
}
```

**16.2 Performance Benchmarks**
```java
@Benchmark
@BenchmarkMode(Mode.Throughput)
public void benchmarkVoxelization(Blackhole blackhole) {
    VoxelOctree octree = builder.build(testMesh);
    blackhole.consume(octree);
}

@Benchmark
@BenchmarkMode(Mode.AverageTime)
public void benchmarkRayTracing(Blackhole blackhole) {
    float[] results = rayTracer.trace(testRays, octree);
    blackhole.consume(results);
}
```

### Deliverables
- Performance optimizations implemented
- Comprehensive test suite
- Performance benchmarks
- Complete documentation

## Risk Mitigation Strategies

### Technical Risks

1. **GPU Performance**
   - Risk: Java overhead impacts GPU performance
   - Mitigation: Profile early, consider JNI for critical paths
   
2. **Memory Management**
   - Risk: GC pauses during rendering
   - Mitigation: Use off-heap memory, tune GC settings
   
3. **File Compatibility**
   - Risk: Binary format incompatibility with C++
   - Mitigation: Extensive testing, byte-level validation

### Schedule Risks

1. **GPU Integration Delays**
   - Risk: Framework issues delay GPU implementation
   - Mitigation: Prototype with multiple frameworks early
   
2. **Performance Targets**
   - Risk: Cannot achieve C++ performance levels
   - Mitigation: Set realistic targets, optimize incrementally

## Success Criteria

1. **Functional Requirements**
   - Reads ESVO file format correctly
   - Builds octrees from triangle meshes
   - Renders scenes via GPU ray tracing
   - Supports all attachment types

2. **Performance Requirements**
   - Voxelization within 3x of C++ speed
   - Rendering within 2x of C++ speed
   - Memory usage within 1.5x of C++
   - Real-time rendering (30+ FPS) for moderate scenes

3. **Quality Requirements**
   - Pixel-accurate rendering vs reference
   - No memory leaks
   - Thread-safe operations
   - Comprehensive test coverage

## Conclusion

This implementation plan provides a structured approach to translating ESVO to Java. By following the phased approach and leveraging selective reuse from lucien, we can create a high-performance voxel octree renderer while maintaining code quality and architectural clarity.