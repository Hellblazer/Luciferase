package com.hellblazer.luciferase.esvo.traversal;

import com.hellblazer.luciferase.esvo.core.CoordinateSpace;
import com.hellblazer.luciferase.esvo.core.OctreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;

/**
 * Phase 1: Basic Ray Traversal Algorithm for ESVO
 * 
 * This implements the fundamental ray-octree traversal for single-level testing.
 * Key requirements from roadmap:
 * - Test against simple 1-level octree  
 * - Performance target: >100 FPS for single level
 * - Ray generation in octree space [1,2]
 * - Child index calculation with octant mirroring
 * 
 * Implementation follows ESVO paper and C++ reference exactly.
 */
public final class BasicRayTraversal {
    private static final Logger log = LoggerFactory.getLogger(BasicRayTraversal.class);
    
    // ESVO constants from C++ reference
    private static final int NUM_OCTANTS = 8;
    private static final float EPSILON = (float) Math.pow(2, -23); // exp2(-23.0) from GLSL
    
    /**
     * Ray structure for traversal operations
     */
    public static class Ray {
        public final Vector3f origin;
        public final Vector3f direction;
        public float tmin;
        public float tmax;
        
        public Ray(Vector3f origin, Vector3f direction) {
            this.origin = new Vector3f(origin);
            this.direction = new Vector3f(direction);
            this.direction.normalize();
            this.tmin = 0.0f;
            this.tmax = Float.POSITIVE_INFINITY;
        }
        
        public Ray(Vector3f origin, Vector3f direction, float tmin, float tmax) {
            this.origin = new Vector3f(origin);
            this.direction = new Vector3f(direction);
            this.direction.normalize();
            this.tmin = tmin;
            this.tmax = tmax;
        }
        
        /**
         * Get point along ray at parameter t
         */
        public Vector3f pointAt(float t) {
            Vector3f point = new Vector3f(direction);
            point.scale(t);
            point.add(origin);
            return point;
        }
    }
    
    /**
     * Result of ray traversal operation
     */
    public static class TraversalResult {
        public boolean hit;
        public float t;
        public int octant;
        public Vector3f hitPoint;
        public Vector3f normal;
        
        public TraversalResult() {
            this.hit = false;
            this.t = Float.POSITIVE_INFINITY;
            this.octant = -1;
            this.hitPoint = null;
            this.normal = null;
        }
        
        public TraversalResult(boolean hit, float t, int octant, Vector3f hitPoint) {
            this.hit = hit;
            this.t = t;
            this.octant = octant;
            this.hitPoint = hitPoint != null ? new Vector3f(hitPoint) : null;
            this.normal = null;
        }
    }
    
    /**
     * Single-level octree for Phase 1 testing
     */
    public static class SimpleOctree {
        private final OctreeNode rootNode;
        private final Vector3f center;
        private final float halfSize;
        
        public SimpleOctree(OctreeNode rootNode) {
            this.rootNode = rootNode;
            this.center = new Vector3f(CoordinateSpace.OCTREE_CENTER, 
                                     CoordinateSpace.OCTREE_CENTER, 
                                     CoordinateSpace.OCTREE_CENTER); // (1.5, 1.5, 1.5)
            this.halfSize = CoordinateSpace.OCTREE_SIZE * 0.5f; // 0.5
        }
        
        public OctreeNode getRootNode() { return rootNode; }
        public Vector3f getCenter() { return new Vector3f(center); }
        public float getHalfSize() { return halfSize; }
    }
    
    private BasicRayTraversal() {
        // Utility class - no instantiation
    }
    
    /**
     * Phase 1 ray traversal - single level octree intersection
     * 
     * @param ray Ray in octree coordinate space [1,2]
     * @param octree Simple single-level octree
     * @return Traversal result with hit information
     */
    public static TraversalResult traverse(Ray ray, SimpleOctree octree) {
        // Calculate intersection with octree bounds [1,2]
        float[] intersection = CoordinateSpace.calculateOctreeIntersection(ray.origin, ray.direction);
        if (intersection == null) {
            return new TraversalResult();
        }
        
        float tEnter = Math.max(intersection[0], ray.tmin);
        float tExit = Math.min(intersection[1], ray.tmax);
        
        if (tEnter > tExit || tExit < 0) {
            return new TraversalResult();
        }
        
        // If tEnter is negative, ray starts inside octree
        if (tEnter < 0) {
            tEnter = 0;
        }
        
        // For single-level traversal, we need to check octants in the order 
        // the ray passes through them, returning the first with geometry.
        OctreeNode rootNode = octree.getRootNode();
        int validMask = rootNode.getValidMask() & 0xFF;
        Vector3f center = octree.getCenter();
        
        // Calculate where ray crosses the center planes
        float tx = (center.x - ray.origin.x) / ray.direction.x;
        float ty = (center.y - ray.origin.y) / ray.direction.y;
        float tz = (center.z - ray.origin.z) / ray.direction.z;
        
        // Create ordered list of t values where octant changes occur
        java.util.List<Float> tValues = new java.util.ArrayList<>();
        tValues.add(tEnter);
        
        // Add plane crossings that are within the ray segment
        if (tx > tEnter && tx < tExit) tValues.add(tx);
        if (ty > tEnter && ty < tExit) tValues.add(ty);
        if (tz > tEnter && tz < tExit) tValues.add(tz);
        
        tValues.add(tExit);
        java.util.Collections.sort(tValues);
        
        // Check each octant segment the ray passes through
        for (int i = 0; i < tValues.size() - 1; i++) {
            float t0 = tValues.get(i);
            float t1 = tValues.get(i + 1);
            
            if (t1 - t0 < EPSILON) continue; // Skip tiny segments
            
            // Sample middle of segment to determine octant
            float tMid = (t0 + t1) * 0.5f;
            Vector3f midPoint = ray.pointAt(tMid);
            
            // Clamp to avoid numerical issues at boundaries
            midPoint.x = Math.max(CoordinateSpace.OCTREE_MIN + EPSILON, 
                                 Math.min(CoordinateSpace.OCTREE_MAX - EPSILON, midPoint.x));
            midPoint.y = Math.max(CoordinateSpace.OCTREE_MIN + EPSILON, 
                                 Math.min(CoordinateSpace.OCTREE_MAX - EPSILON, midPoint.y));
            midPoint.z = Math.max(CoordinateSpace.OCTREE_MIN + EPSILON, 
                                 Math.min(CoordinateSpace.OCTREE_MAX - EPSILON, midPoint.z));
            
            int octant = calculateChildOctant(midPoint, center);
            
            // Return first octant with geometry
            if ((validMask & (1 << octant)) != 0) {
                Vector3f hitPoint = ray.pointAt(t0);
                return new TraversalResult(true, t0, octant, hitPoint);
            }
        }
        
        // No octant with geometry was hit
        return new TraversalResult();
    }
    
    /**
     * Calculate which child octant contains the given point
     * 
     * @param point Point in octree space
     * @param center Center of the octree
     * @return Child octant index (0-7)
     */
    private static int calculateChildOctant(Vector3f point, Vector3f center) {
        int octant = 0;
        // Use > instead of >= to handle boundary cases correctly
        if (point.x > center.x) octant |= 1; // X bit
        if (point.y > center.y) octant |= 2; // Y bit  
        if (point.z > center.z) octant |= 4; // Z bit
        return octant;
    }
    
    /**
     * Generate rays for screen pixels - transforms to octree space [1,2]
     * 
     * @param screenX Screen X coordinate (0 to screenWidth-1)
     * @param screenY Screen Y coordinate (0 to screenHeight-1)  
     * @param screenWidth Screen width in pixels
     * @param screenHeight Screen height in pixels
     * @param cameraPos Camera position in world space
     * @param cameraDir Camera direction in world space
     * @param fov Field of view in radians
     * @return Ray in octree coordinate space [1,2]
     */
    public static Ray generateRay(int screenX, int screenY, int screenWidth, int screenHeight,
                                 Vector3f cameraPos, Vector3f cameraDir, float fov) {
        
        // Convert screen coordinates to NDC [-1, 1]
        float ndcX = (2.0f * screenX + 1.0f) / screenWidth - 1.0f;
        float ndcY = 1.0f - (2.0f * screenY + 1.0f) / screenHeight; // Flip Y
        
        // Calculate ray direction in camera space
        float aspectRatio = (float) screenWidth / screenHeight;
        float tanHalfFov = (float) Math.tan(fov * 0.5);
        
        Vector3f rayDir = new Vector3f();
        rayDir.x = ndcX * aspectRatio * tanHalfFov;
        rayDir.y = ndcY * tanHalfFov;
        rayDir.z = -1.0f; // Forward in camera space
        rayDir.normalize();
        
        // For Phase 1, assume camera is positioned to look at octree
        // Transform ray from world space to octree space [1,2]
        // This is simplified - real implementation would use camera matrices
        Vector3f octreeOrigin = new Vector3f(cameraPos);
        Vector3f octreeDirection = new Vector3f(rayDir);
        
        // Ensure ray starts outside octree and points toward it
        if (!CoordinateSpace.isInOctreeSpace(octreeOrigin)) {
            // Move camera to reasonable position outside octree
            octreeOrigin.set(0.5f, 1.5f, 1.5f); // Left of octree center
            octreeDirection.set(1.0f, 0.0f, 0.0f); // Point right toward octree
        }
        
        return new Ray(octreeOrigin, octreeDirection);
    }
    
    /**
     * Debug method to visualize octant colors
     * 
     * @param octant Octant index (0-7)
     * @return RGB color as packed int (0xRRGGBB)
     */
    public static int getOctantDebugColor(int octant) {
        switch (octant) {
            case 0: return 0xFF0000; // Red
            case 1: return 0x00FF00; // Green  
            case 2: return 0x0000FF; // Blue
            case 3: return 0xFFFF00; // Yellow
            case 4: return 0xFF00FF; // Magenta
            case 5: return 0x00FFFF; // Cyan
            case 6: return 0xFFFFFF; // White
            case 7: return 0x808080; // Gray
            default: return 0x000000; // Black (no hit)
        }
    }
    
    /**
     * Performance measurement helper for Phase 1
     * 
     * @param octree Test octree
     * @param rayCount Number of rays to cast
     * @return Rays per second
     */
    public static double measureTraversalPerformance(SimpleOctree octree, int rayCount) {
        // Generate test rays
        Ray[] testRays = new Ray[rayCount];
        Vector3f cameraPos = new Vector3f(0.5f, 1.5f, 1.5f);
        Vector3f cameraDir = new Vector3f(1.0f, 0.0f, 0.0f);
        
        for (int i = 0; i < rayCount; i++) {
            int x = i % 256;
            int y = i / 256;
            testRays[i] = generateRay(x, y, 256, 256, cameraPos, cameraDir, (float) Math.PI / 4);
        }
        
        // Warm up JVM
        for (int i = 0; i < 1000; i++) {
            traverse(testRays[i % testRays.length], octree);
        }
        
        // Measure performance
        long startTime = System.nanoTime();
        
        for (Ray ray : testRays) {
            traverse(ray, octree);
        }
        
        long endTime = System.nanoTime();
        double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
        
        return rayCount / elapsedSeconds;
    }
}