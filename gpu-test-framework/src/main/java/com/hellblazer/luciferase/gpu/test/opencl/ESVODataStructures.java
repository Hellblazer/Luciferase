package com.hellblazer.luciferase.gpu.test.opencl;

import org.lwjgl.BufferUtils;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * OpenCL data structures for ESVO (Efficient Sparse Voxel Octrees) testing.
 * Provides host-side representations that match GPU memory layout.
 */
public class ESVODataStructures {

    /**
     * Octree node structure matching GPU layout.
     * Size: 32 bytes (aligned)
     */
    public static class OctreeNode {
        public int childDescriptor;    // 4 bytes: child pointer and validity mask
        public int contourPointer;     // 4 bytes: pointer to contour data
        public float minValue;          // 4 bytes: minimum voxel value
        public float maxValue;          // 4 bytes: maximum voxel value
        public int attributes;          // 4 bytes: node attributes and flags
        public int padding1;            // 4 bytes: alignment padding
        public int padding2;            // 4 bytes: alignment padding
        public int padding3;            // 4 bytes: alignment padding
        
        public static final int SIZE_BYTES = 32;
        
        public ByteBuffer toBuffer() {
            ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(SIZE_BYTES);
            buffer.putInt(childDescriptor);
            buffer.putInt(contourPointer);
            buffer.putFloat(minValue);
            buffer.putFloat(maxValue);
            buffer.putInt(attributes);
            buffer.putInt(padding1);
            buffer.putInt(padding2);
            buffer.putInt(padding3);
            buffer.flip();
            return buffer;
        }
        
        public static OctreeNode fromBuffer(ByteBuffer buffer) {
            OctreeNode node = new OctreeNode();
            node.childDescriptor = buffer.getInt();
            node.contourPointer = buffer.getInt();
            node.minValue = buffer.getFloat();
            node.maxValue = buffer.getFloat();
            node.attributes = buffer.getInt();
            node.padding1 = buffer.getInt();
            node.padding2 = buffer.getInt();
            node.padding3 = buffer.getInt();
            return node;
        }
    }
    
    /**
     * Ray structure for GPU ray traversal.
     * Size: 32 bytes (aligned)
     */
    public static class Ray {
        public float originX, originY, originZ;        // 12 bytes: ray origin
        public float directionX, directionY, directionZ; // 12 bytes: ray direction (normalized)
        public float tMin;                              // 4 bytes: minimum t parameter
        public float tMax;                              // 4 bytes: maximum t parameter
        
        public static final int SIZE_BYTES = 32;
        
        public ByteBuffer toBuffer() {
            ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(SIZE_BYTES);
            buffer.putFloat(originX);
            buffer.putFloat(originY);
            buffer.putFloat(originZ);
            buffer.putFloat(directionX);
            buffer.putFloat(directionY);
            buffer.putFloat(directionZ);
            buffer.putFloat(tMin);
            buffer.putFloat(tMax);
            buffer.flip();
            return buffer;
        }
        
        public static Ray fromBuffer(ByteBuffer buffer) {
            Ray ray = new Ray();
            ray.originX = buffer.getFloat();
            ray.originY = buffer.getFloat();
            ray.originZ = buffer.getFloat();
            ray.directionX = buffer.getFloat();
            ray.directionY = buffer.getFloat();
            ray.directionZ = buffer.getFloat();
            ray.tMin = buffer.getFloat();
            ray.tMax = buffer.getFloat();
            return ray;
        }
    }
    
    /**
     * Traversal stack entry for ray-octree intersection.
     * Size: 16 bytes (aligned)
     */
    public static class StackEntry {
        public int nodeIndex;           // 4 bytes: index of octree node
        public float tEntry;            // 4 bytes: ray parameter at entry
        public float tExit;             // 4 bytes: ray parameter at exit
        public int level;               // 4 bytes: octree level (0 = root)
        
        public static final int SIZE_BYTES = 16;
        
        public ByteBuffer toBuffer() {
            ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(SIZE_BYTES);
            buffer.putInt(nodeIndex);
            buffer.putFloat(tEntry);
            buffer.putFloat(tExit);
            buffer.putInt(level);
            buffer.flip();
            return buffer;
        }
    }
    
    /**
     * Intersection result from ray traversal.
     * Size: 32 bytes (aligned)
     */
    public static class IntersectionResult {
        public int hit;                 // 4 bytes: 1 if hit, 0 if miss
        public float t;                 // 4 bytes: ray parameter at intersection
        public float normalX, normalY, normalZ; // 12 bytes: surface normal
        public int voxelValue;          // 4 bytes: voxel data at intersection
        public int nodeIndex;           // 4 bytes: index of intersected node
        public int iterations;          // 4 bytes: number of traversal iterations
        
        public static final int SIZE_BYTES = 32;
        
        public static IntersectionResult fromBuffer(ByteBuffer buffer) {
            IntersectionResult result = new IntersectionResult();
            result.hit = buffer.getInt();
            result.t = buffer.getFloat();
            result.normalX = buffer.getFloat();
            result.normalY = buffer.getFloat();
            result.normalZ = buffer.getFloat();
            result.voxelValue = buffer.getInt();
            result.nodeIndex = buffer.getInt();
            result.iterations = buffer.getInt();
            return result;
        }
        
        public ByteBuffer toBuffer() {
            ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(SIZE_BYTES);
            buffer.putInt(hit);
            buffer.putFloat(t);
            buffer.putFloat(normalX);
            buffer.putFloat(normalY);
            buffer.putFloat(normalZ);
            buffer.putInt(voxelValue);
            buffer.putInt(nodeIndex);
            buffer.putInt(iterations);
            buffer.flip();
            return buffer;
        }
    }
    
    /**
     * Contour data for beam optimization.
     * Size: 64 bytes (aligned)
     */
    public static class ContourData {
        public float[] vertices = new float[12];  // 48 bytes: 4 vertices * 3 components
        public int flags;                         // 4 bytes: contour flags
        public int material;                      // 4 bytes: material index
        public int padding1;                      // 4 bytes: alignment
        public int padding2;                      // 4 bytes: alignment
        
        public static final int SIZE_BYTES = 64;
        
        public ByteBuffer toBuffer() {
            ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(SIZE_BYTES);
            for (float v : vertices) {
                buffer.putFloat(v);
            }
            buffer.putInt(flags);
            buffer.putInt(material);
            buffer.putInt(padding1);
            buffer.putInt(padding2);
            buffer.flip();
            return buffer;
        }
    }
    
    /**
     * Global parameters for ESVO traversal.
     * Size: 64 bytes (aligned)
     */
    public static class TraversalParams {
        public int maxDepth;            // 4 bytes: maximum octree depth
        public int maxIterations;       // 4 bytes: maximum traversal iterations
        public float epsilon;           // 4 bytes: numerical epsilon
        public float rootSize;          // 4 bytes: size of root voxel
        public float rootX, rootY, rootZ; // 12 bytes: root voxel origin
        public int beamOptimization;    // 4 bytes: 1 to enable beam optimization
        public int contourEnabled;      // 4 bytes: 1 to enable contour extraction
        public int shadowRays;          // 4 bytes: 1 to enable shadow ray optimization
        public int padding1;            // 4 bytes
        public int padding2;            // 4 bytes
        public int padding3;            // 4 bytes
        public int padding4;            // 4 bytes
        public int padding5;            // 4 bytes
        
        public static final int SIZE_BYTES = 64;
        
        public ByteBuffer toBuffer() {
            ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(SIZE_BYTES);
            buffer.putInt(maxDepth);
            buffer.putInt(maxIterations);
            buffer.putFloat(epsilon);
            buffer.putFloat(rootSize);
            buffer.putFloat(rootX);
            buffer.putFloat(rootY);
            buffer.putFloat(rootZ);
            buffer.putInt(beamOptimization);
            buffer.putInt(contourEnabled);
            buffer.putInt(shadowRays);
            buffer.putInt(padding1);
            buffer.putInt(padding2);
            buffer.putInt(padding3);
            buffer.putInt(padding4);
            buffer.putInt(padding5);
            buffer.flip();
            return buffer;
        }
    }
    
    /**
     * Utility class for creating and managing OpenCL buffers.
     */
    public static class BufferUtils {
        
        /**
         * Creates an OpenCL buffer for an array of octree nodes.
         */
        public static long createNodeBuffer(long context, OctreeNode[] nodes, long flags) {
            ByteBuffer hostBuffer = org.lwjgl.BufferUtils.createByteBuffer(
                nodes.length * OctreeNode.SIZE_BYTES);
            
            for (OctreeNode node : nodes) {
                hostBuffer.put(node.toBuffer());
            }
            hostBuffer.flip();
            
            int[] errcode = new int[1];
            long buffer = CL10.clCreateBuffer(context, flags, hostBuffer, errcode);
            
            if (errcode[0] != CL10.CL_SUCCESS) {
                throw new RuntimeException("Failed to create node buffer: " + errcode[0]);
            }
            
            return buffer;
        }
        
        /**
         * Creates an OpenCL buffer for an array of rays.
         */
        public static long createRayBuffer(long context, Ray[] rays, long flags) {
            ByteBuffer hostBuffer = org.lwjgl.BufferUtils.createByteBuffer(
                rays.length * Ray.SIZE_BYTES);
            
            for (Ray ray : rays) {
                hostBuffer.put(ray.toBuffer());
            }
            hostBuffer.flip();
            
            int[] errcode = new int[1];
            long buffer = CL10.clCreateBuffer(context, flags, hostBuffer, errcode);
            
            if (errcode[0] != CL10.CL_SUCCESS) {
                throw new RuntimeException("Failed to create ray buffer: " + errcode[0]);
            }
            
            return buffer;
        }
        
        /**
         * Reads intersection results from an OpenCL buffer.
         */
        public static IntersectionResult[] readResults(long commandQueue, long buffer, int count) {
            ByteBuffer hostBuffer = org.lwjgl.BufferUtils.createByteBuffer(
                count * IntersectionResult.SIZE_BYTES);
            
            int err = CL10.clEnqueueReadBuffer(commandQueue, buffer, true, 0,
                hostBuffer, null, null);
            
            if (err != CL10.CL_SUCCESS) {
                throw new RuntimeException("Failed to read results: " + err);
            }
            
            IntersectionResult[] results = new IntersectionResult[count];
            for (int i = 0; i < count; i++) {
                results[i] = IntersectionResult.fromBuffer(hostBuffer);
            }
            
            return results;
        }
        
        /**
         * Compiles an OpenCL program from source.
         */
        public static long compileProgram(long context, String source) {
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var strings = stack.mallocPointer(1);
                var lengths = stack.mallocPointer(1);
                var sourceBuffer = stack.UTF8(source);
                strings.put(0, sourceBuffer);
                lengths.put(0, source.length());
                
                int[] errcode = new int[1];
                long program = CL10.clCreateProgramWithSource(context, strings, lengths, errcode);
                
                if (errcode[0] != CL10.CL_SUCCESS) {
                    throw new RuntimeException("Failed to create program: " + errcode[0]);
                }
                
                // Get first device from context
                var deviceBuffer = stack.mallocPointer(1);
                CL10.clGetContextInfo(context, CL10.CL_CONTEXT_DEVICES, deviceBuffer, null);
                long device = deviceBuffer.get(0);
                
                // Build program
                var devicePointer = stack.mallocPointer(1);
                devicePointer.put(0, device);
                
                int buildResult = CL10.clBuildProgram(program, devicePointer, "", null, 0);
                if (buildResult != CL10.CL_SUCCESS) {
                    // Get build log
                    var logSize = stack.mallocPointer(1);
                    CL10.clGetProgramBuildInfo(program, device, CL10.CL_PROGRAM_BUILD_LOG, (java.nio.ByteBuffer)null, logSize);
                    var log = stack.malloc((int)logSize.get(0));
                    CL10.clGetProgramBuildInfo(program, device, CL10.CL_PROGRAM_BUILD_LOG, log, null);
                    
                    String errorLog = org.lwjgl.system.MemoryUtil.memUTF8(log);
                    CL10.clReleaseProgram(program);
                    throw new RuntimeException("Program compilation failed:\n" + errorLog);
                }
                
                return program;
            }
        }
    }
}