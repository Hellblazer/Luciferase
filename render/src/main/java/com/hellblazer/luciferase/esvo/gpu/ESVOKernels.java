package com.hellblazer.luciferase.esvo.gpu;

/**
 * ESVO (Efficient Sparse Voxel Octrees) GPU kernel definitions.
 * Contains OpenCL and GLSL compute shader code for ray traversal through voxel octrees.
 */
public class ESVOKernels {
    
    /**
     * OpenCL kernel for ESVO ray traversal through sparse voxel octrees.
     * Based on the Laine & Karras 2010 algorithm.
     */
    public static final String OPENCL_RAY_TRAVERSAL = """
        // ESVO Ray Traversal Kernel - OpenCL Implementation
        
        typedef struct {
            float3 origin;
            float3 direction;
            float tMin;
            float tMax;
        } Ray;
        
        typedef struct {
            uint childDescriptor;  // [childptr(14)|far(1)|childmask(8)|leafmask(8)]
            uint contourData;      // [contour_ptr(24)|contour_mask(8)]
        } OctreeNode;
        
        typedef struct {
            float3 min;
            float3 max;
        } AABB;
        
        typedef struct {
            uint nodeIdx;
            float tEntry;
            float tExit;
            uint scale;
            float3 pos;
        } StackEntry;
        
        // Ray-AABB intersection
        bool intersectAABB(Ray ray, AABB box, float* tEntry, float* tExit) {
            float3 invDir = 1.0f / ray.direction;
            float3 t0 = (box.min - ray.origin) * invDir;
            float3 t1 = (box.max - ray.origin) * invDir;
            
            float3 tMin = min(t0, t1);
            float3 tMax = max(t0, t1);
            
            *tEntry = max(max(tMin.x, tMin.y), max(tMin.z, ray.tMin));
            *tExit = min(min(tMax.x, tMax.y), min(tMax.z, ray.tMax));
            
            return *tEntry <= *tExit && *tExit >= 0.0f;
        }
        
        // Get child index from position within parent
        uint getChildIndex(float3 pos, float3 center) {
            uint idx = 0;
            if (pos.x >= center.x) idx |= 1;
            if (pos.y >= center.y) idx |= 2;
            if (pos.z >= center.z) idx |= 4;
            return idx;
        }
        
        // Main ray traversal kernel
        __kernel void traverseOctree(
            __global const Ray* rays,
            __global const OctreeNode* octree,
            __global float4* hitResults,  // xyz = hit point, w = distance
            __global uint* hitVoxels,      // Voxel data at hit point
            const uint maxDepth,
            const float3 sceneMin,
            const float3 sceneMax)
        {
            int gid = get_global_id(0);
            Ray ray = rays[gid];
            
            // Initialize result
            hitResults[gid] = (float4)(0.0f, 0.0f, 0.0f, -1.0f);
            hitVoxels[gid] = 0;
            
            // Check scene bounds
            float tEntry, tExit;
            AABB sceneBounds = { sceneMin, sceneMax };
            if (!intersectAABB(ray, sceneBounds, &tEntry, &tExit)) {
                return;
            }
            
            // Stack for traversal
            __local StackEntry stack[32];
            int stackPtr = 0;
            
            // Initialize root
            stack[0].nodeIdx = 0;
            stack[0].tEntry = tEntry;
            stack[0].tExit = tExit;
            stack[0].scale = 0;
            stack[0].pos = sceneMin;
            stackPtr = 1;
            
            float closestHit = ray.tMax;
            uint hitVoxel = 0;
            float3 hitPoint = (float3)(0.0f);
            
            // Traverse octree
            while (stackPtr > 0) {
                StackEntry current = stack[--stackPtr];
                
                // Skip if beyond closest hit
                if (current.tEntry > closestHit) {
                    continue;
                }
                
                OctreeNode node = octree[current.nodeIdx];
                
                // Extract child mask from packed descriptor
                uint childMask = (node.childDescriptor >> 8) & 0xFF;
                
                // Leaf node - check for voxel
                if (childMask == 0 || current.scale >= maxDepth) {
                    // Extract contour data for voxel information
                    uint contourPtr = (node.contourData >> 8) & 0xFFFFFF;
                    if (contourPtr != 0 && current.tEntry < closestHit) {
                        closestHit = current.tEntry;
                        hitVoxel = contourPtr;  // Use contour pointer as voxel data
                        hitPoint = ray.origin + ray.direction * closestHit;
                    }
                    continue;
                }
                
                // Internal node - traverse children
                float nodeSize = (sceneMax.x - sceneMin.x) / (1 << (current.scale + 1));
                float3 center = current.pos + nodeSize;
                
                // Determine traversal order based on ray direction
                uint firstChild = getChildIndex(
                    ray.origin + ray.direction * current.tEntry, 
                    center);
                
                // Extract child pointer from packed descriptor
                uint childPtr = (node.childDescriptor >> 17) & 0x3FFF;
                
                // Add children to stack in reverse order for correct traversal
                for (int i = 7; i >= 0; i--) {
                    if (!(childMask & (1 << i))) {
                        continue;
                    }
                    
                    // Calculate child bounds
                    float3 childMin = current.pos;
                    float3 childMax = current.pos + nodeSize * 2.0f;
                    
                    if (i & 1) childMin.x += nodeSize;
                    else childMax.x -= nodeSize;
                    if (i & 2) childMin.y += nodeSize;
                    else childMax.y -= nodeSize;
                    if (i & 4) childMin.z += nodeSize;
                    else childMax.z -= nodeSize;
                    
                    AABB childBox = { childMin, childMax };
                    float childTEntry, childTExit;
                    
                    if (intersectAABB(ray, childBox, &childTEntry, &childTExit)) {
                        if (childTEntry < closestHit && stackPtr < 31) {
                            // CUDA reference sparse indexing: parent_ptr + popcount(child_masks & ((1 << i) - 1))
                            uint childIdx = childPtr + popcount(childMask & ((1 << i) - 1));
                            stack[stackPtr].nodeIdx = childIdx;
                            stack[stackPtr].tEntry = childTEntry;
                            stack[stackPtr].tExit = childTExit;
                            stack[stackPtr].scale = current.scale + 1;
                            stack[stackPtr].pos = childMin;
                            stackPtr++;
                        }
                    }
                }
            }
            
            // Write results
            if (hitVoxel != 0) {
                hitResults[gid] = (float4)(hitPoint.x, hitPoint.y, hitPoint.z, closestHit);
                hitVoxels[gid] = hitVoxel;
            }
        }
        
        // Optimized beam traversal for coherent rays
        __kernel void traverseBeam(
            __global const Ray* rays,
            __global const OctreeNode* octree,
            __global float4* hitResults,
            const uint raysPerBeam,
            const uint maxDepth,
            const float3 sceneMin,
            const float3 sceneMax)
        {
            int beamId = get_global_id(0);
            int firstRay = beamId * raysPerBeam;
            
            // Compute beam frustum from ray bundle
            float3 frustumMin = (float3)(INFINITY);
            float3 frustumMax = (float3)(-INFINITY);
            
            for (uint i = 0; i < raysPerBeam; i++) {
                Ray ray = rays[firstRay + i];
                float3 nearPoint = ray.origin + ray.direction * ray.tMin;
                float3 farPoint = ray.origin + ray.direction * ray.tMax;
                
                frustumMin = min(frustumMin, min(nearPoint, farPoint));
                frustumMax = max(frustumMax, max(nearPoint, farPoint));
            }
            
            // Traverse using beam frustum for early rejection
            // ... beam-specific traversal logic ...
        }
        """;
    
    /**
     * GLSL compute shader for ESVO ray traversal.
     */
    public static final String GLSL_RAY_TRAVERSAL = """
        #version 450
        
        // ESVO Ray Traversal - GLSL Compute Shader Implementation
        
        layout(local_size_x = 64) in;
        
        struct Ray {
            vec3 origin;
            float tMin;
            vec3 direction;
            float tMax;
        };
        
        struct OctreeNode {
            uint childDescriptor;  // [childptr(14)|far(1)|childmask(8)|leafmask(8)]
            uint contourData;      // [contour_ptr(24)|contour_mask(8)]
        };
        
        struct HitResult {
            vec3 position;
            float distance;
            uint voxelData;
        };
        
        // Input/Output buffers
        layout(std430, binding = 0) readonly buffer RayBuffer {
            Ray rays[];
        };
        
        layout(std430, binding = 1) readonly buffer OctreeBuffer {
            OctreeNode nodes[];
        };
        
        layout(std430, binding = 2) writeonly buffer ResultBuffer {
            HitResult results[];
        };
        
        // Uniforms
        uniform uint maxDepth;
        uniform vec3 sceneMin;
        uniform vec3 sceneMax;
        
        bool intersectAABB(Ray ray, vec3 boxMin, vec3 boxMax, out float tEntry, out float tExit) {
            vec3 invDir = 1.0 / ray.direction;
            vec3 t0 = (boxMin - ray.origin) * invDir;
            vec3 t1 = (boxMax - ray.origin) * invDir;
            
            vec3 tMin = min(t0, t1);
            vec3 tMax = max(t0, t1);
            
            tEntry = max(max(tMin.x, tMin.y), max(tMin.z, ray.tMin));
            tExit = min(min(tMax.x, tMax.y), min(tMax.z, ray.tMax));
            
            return tEntry <= tExit && tExit >= 0.0;
        }
        
        void main() {
            uint gid = gl_GlobalInvocationID.x;
            if (gid >= rays.length()) return;
            
            Ray ray = rays[gid];
            HitResult result;
            result.position = vec3(0.0);
            result.distance = -1.0;
            result.voxelData = 0;
            
            // Check scene bounds
            float tEntry, tExit;
            if (!intersectAABB(ray, sceneMin, sceneMax, tEntry, tExit)) {
                results[gid] = result;
                return;
            }
            
            // Stack-based traversal
            uint stack[32];
            float stackT[32];
            int stackPtr = 0;
            
            // Start with root
            stack[0] = 0;
            stackT[0] = tEntry;
            stackPtr = 1;
            
            float closestHit = ray.tMax;
            
            while (stackPtr > 0) {
                uint nodeIdx = stack[--stackPtr];
                float nodeTEntry = stackT[stackPtr];
                
                if (nodeTEntry > closestHit) continue;
                
                OctreeNode node = nodes[nodeIdx];
                
                // Extract child mask from packed descriptor  
                uint childMask = (node.childDescriptor >> 8) & 0xFF;
                
                // Leaf node
                if (childMask == 0) {
                    // Extract contour data for voxel information
                    uint contourPtr = (node.contourData >> 8) & 0xFFFFFF;
                    if (contourPtr != 0 && nodeTEntry < closestHit) {
                        closestHit = nodeTEntry;
                        result.position = ray.origin + ray.direction * closestHit;
                        result.distance = closestHit;
                        result.voxelData = contourPtr;
                    }
                    continue;
                }
                
                // Traverse children
                // ... child traversal logic ...
            }
            
            results[gid] = result;
        }
        """;
    
    /**
     * Metal shader for ESVO ray traversal (Metal Shading Language).
     */
    public static final String METAL_RAY_TRAVERSAL = """
        #include <metal_stdlib>
        using namespace metal;
        
        // ESVO Ray Traversal - Metal Compute Shader Implementation
        
        struct Ray {
            float3 origin;
            float3 direction;
            float tMin;
            float tMax;
        };
        
        struct OctreeNode {
            uint childDescriptor;  // [childptr(14)|far(1)|childmask(8)|leafmask(8)]
            uint contourData;      // [contour_ptr(24)|contour_mask(8)]
        };
        
        struct HitResult {
            float3 position;
            float distance;
            uint voxelData;
            uint padding;
        };
        
        bool intersectAABB(Ray ray, float3 boxMin, float3 boxMax, 
                          thread float& tEntry, thread float& tExit) {
            float3 invDir = 1.0 / ray.direction;
            float3 t0 = (boxMin - ray.origin) * invDir;
            float3 t1 = (boxMax - ray.origin) * invDir;
            
            float3 tMin = min(t0, t1);
            float3 tMax = max(t0, t1);
            
            tEntry = max(max(tMin.x, tMin.y), max(tMin.z, ray.tMin));
            tExit = min(min(tMax.x, tMax.y), min(tMax.z, ray.tMax));
            
            return tEntry <= tExit && tExit >= 0.0;
        }
        
        kernel void traverseOctree(
            device const Ray* rays [[buffer(0)]],
            device const OctreeNode* nodes [[buffer(1)]],
            device HitResult* results [[buffer(2)]],
            constant uint& maxDepth [[buffer(3)]],
            constant float3& sceneMin [[buffer(4)]],
            constant float3& sceneMax [[buffer(5)]],
            uint gid [[thread_position_in_grid]])
        {
            Ray ray = rays[gid];
            HitResult result;
            result.position = float3(0.0);
            result.distance = -1.0;
            result.voxelData = 0;
            
            float tEntry, tExit;
            if (!intersectAABB(ray, sceneMin, sceneMax, tEntry, tExit)) {
                results[gid] = result;
                return;
            }
            
            // Traversal implementation...
            // Similar to OpenCL version but using Metal syntax
            
            results[gid] = result;
        }
        """;
    
    /**
     * Returns the OpenCL kernel source code for ESVO ray traversal.
     */
    public static String getOpenCLKernel() {
        return OPENCL_RAY_TRAVERSAL;
    }
    
    /**
     * Returns the GLSL compute shader source code for ESVO ray traversal.
     */
    public static String getGLSLKernel() {
        return GLSL_RAY_TRAVERSAL;
    }
    
    /**
     * Returns the Metal compute shader source code for ESVO ray traversal.
     */
    public static String getMetalKernel() {
        return METAL_RAY_TRAVERSAL;
    }
}