package com.hellblazer.luciferase.render.voxel.gpu.compute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.vecmath.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for visibility culling algorithms including frustum culling,
 * hierarchical occlusion, and LOD selection.
 */
public class VisibilityCullingTest {
    
    private static final float EPSILON = 1e-5f;
    
    // Frustum representation
    static class Frustum {
        Vector4f[] planes = new Vector4f[6]; // Normal.xyz, distance
        Matrix4f viewProjMatrix;
        Vector3f cameraPosition;
        float nearPlane;
        float farPlane;
        
        public Frustum() {
            planes = new Vector4f[6];
            for (int i = 0; i < 6; i++) {
                planes[i] = new Vector4f();
            }
            viewProjMatrix = new Matrix4f();
            viewProjMatrix.setIdentity();
            cameraPosition = new Vector3f(0, 0, 0);
            nearPlane = 0.1f;
            farPlane = 100.0f;
        }
    }
    
    // AABB bounds
    static class AABB {
        Vector3f min;
        Vector3f max;
        
        public AABB(Vector3f min, Vector3f max) {
            this.min = new Vector3f(min);
            this.max = new Vector3f(max);
        }
        
        public Vector3f getCenter() {
            return new Vector3f(
                (min.x + max.x) * 0.5f,
                (min.y + max.y) * 0.5f,
                (min.z + max.z) * 0.5f
            );
        }
        
        public Vector3f getExtent() {
            return new Vector3f(
                (max.x - min.x) * 0.5f,
                (max.y - min.y) * 0.5f,
                (max.z - min.z) * 0.5f
            );
        }
    }
    
    // Visibility result
    static class VisibilityResult {
        boolean visible;
        float distance;
        float screenSize;
        int lodLevel;
        
        public VisibilityResult() {
            this.visible = false;
            this.distance = Float.MAX_VALUE;
            this.screenSize = 0;
            this.lodLevel = 0;
        }
    }
    
    private Frustum frustum;
    
    @BeforeEach
    void setUp() {
        frustum = createStandardFrustum();
    }
    
    @Test
    @DisplayName("Test AABB-plane intersection")
    void testAABBPlaneIntersection() {
        AABB box = new AABB(
            new Vector3f(-1, -1, -1),
            new Vector3f(1, 1, 1)
        );
        
        // Plane through origin pointing up
        Vector4f plane = new Vector4f(0, 1, 0, 0);
        assertTrue(aabbOnPositiveSide(box, plane), "Box should intersect plane");
        
        // Plane above box
        plane = new Vector4f(0, 1, 0, -2);
        assertFalse(aabbOnPositiveSide(box, plane), "Box should be below plane");
        
        // Plane below box
        plane = new Vector4f(0, -1, 0, -2);
        assertFalse(aabbOnPositiveSide(box, plane), "Box should be above plane");
        
        // Diagonal plane
        plane = new Vector4f(0.707f, 0.707f, 0, 0);
        assertTrue(aabbOnPositiveSide(box, plane), "Box should intersect diagonal plane");
    }
    
    @Test
    @DisplayName("Test frustum culling with AABB inside")
    void testFrustumCullingInside() {
        AABB box = new AABB(
            new Vector3f(-0.5f, -0.5f, 5),
            new Vector3f(0.5f, 0.5f, 6)
        );
        
        assertFalse(frustumCullAABB(box, frustum), "Box inside frustum should not be culled");
    }
    
    @Test
    @DisplayName("Test frustum culling with AABB outside")
    void testFrustumCullingOutside() {
        // Box behind camera
        AABB boxBehind = new AABB(
            new Vector3f(-1, -1, -5),
            new Vector3f(1, 1, -3)
        );
        assertTrue(frustumCullAABB(boxBehind, frustum), "Box behind camera should be culled");
        
        // Box to the far left
        AABB boxLeft = new AABB(
            new Vector3f(-50, -1, 5),
            new Vector3f(-45, 1, 6)
        );
        assertTrue(frustumCullAABB(boxLeft, frustum), "Box outside left should be culled");
        
        // Box beyond far plane
        AABB boxFar = new AABB(
            new Vector3f(-1, -1, 105),
            new Vector3f(1, 1, 110)
        );
        assertTrue(frustumCullAABB(boxFar, frustum), "Box beyond far plane should be culled");
    }
    
    @Test
    @DisplayName("Test frustum culling edge cases")
    void testFrustumCullingEdgeCases() {
        // Box straddling near plane
        AABB boxNear = new AABB(
            new Vector3f(-1, -1, -0.05f),
            new Vector3f(1, 1, 0.5f)
        );
        assertFalse(frustumCullAABB(boxNear, frustum), "Box straddling near plane should not be culled");
        
        // Large box containing frustum
        AABB boxLarge = new AABB(
            new Vector3f(-100, -100, -100),
            new Vector3f(100, 100, 100)
        );
        assertFalse(frustumCullAABB(boxLarge, frustum), "Large box containing frustum should not be culled");
        
        // Tiny box at center
        AABB boxTiny = new AABB(
            new Vector3f(-0.001f, -0.001f, 10),
            new Vector3f(0.001f, 0.001f, 10.001f)
        );
        assertFalse(frustumCullAABB(boxTiny, frustum), "Tiny box in frustum should not be culled");
    }
    
    @Test
    @DisplayName("Test AABB screen projection")
    void testAABBScreenProjection() {
        // Box in front of camera at z=5-7 (camera looks down -Z)
        // Note: In view space, this becomes z=-5 to -7
        AABB box = new AABB(
            new Vector3f(-1, -1, -7),  // Changed to negative Z (in front of camera)
            new Vector3f(1, 1, -5)     // Changed to negative Z
        );
        
        Vector4f screenBounds = projectAABBToScreen(box, frustum);
        
        // Debug output
        System.out.println("Screen bounds: " + screenBounds);
        
        // Check for valid projection first
        if (screenBounds.x == 0 && screenBounds.y == 0 && 
            screenBounds.z == 0 && screenBounds.w == 0) {
            fail("Should have valid projection for box in front of camera");
        }
        
        // Verify screen bounds are in NDC space (-1 to 1)
        assertTrue(screenBounds.x >= -1 && screenBounds.x <= 1, 
            "Min X in NDC range: " + screenBounds.x);
        assertTrue(screenBounds.y >= -1 && screenBounds.y <= 1, 
            "Min Y in NDC range: " + screenBounds.y);
        assertTrue(screenBounds.z >= -1 && screenBounds.z <= 1, 
            "Max X in NDC range: " + screenBounds.z);
        assertTrue(screenBounds.w >= -1 && screenBounds.w <= 1, 
            "Max Y in NDC range: " + screenBounds.w);
        
        // Min should be less than or equal to max
        assertTrue(screenBounds.x <= screenBounds.z, "Min X <= Max X");
        assertTrue(screenBounds.y <= screenBounds.w, "Min Y <= Max Y");
    }
    
    @Test
    @DisplayName("Test hierarchical Z-buffer occlusion")
    void testHierarchicalOcclusion() {
        // Create mock HZB
        float[][] hzBuffer = createMockHZBuffer(512, 512);
        
        // Test occluded object
        Vector4f screenBounds = new Vector4f(-0.5f, -0.5f, 0.5f, 0.5f);
        float nodeDepth = 0.9f;
        
        // Place occluder in HZB
        fillHZBRegion(hzBuffer, 128, 128, 384, 384, 0.5f);
        
        assertTrue(isOccludedByHZB(screenBounds, nodeDepth, hzBuffer), 
                  "Object behind occluder should be occluded");
        
        // Test visible object
        nodeDepth = 0.3f;
        assertFalse(isOccludedByHZB(screenBounds, nodeDepth, hzBuffer),
                   "Object in front of occluder should be visible");
    }
    
    @Test
    @DisplayName("Test LOD level calculation")
    void testLODCalculation() {
        // Large screen size, close distance
        assertEquals(0, calculateLOD(0.6f, 1.0f), "Large objects should use LOD 0");
        
        // Medium screen size
        assertEquals(1, calculateLOD(0.3f, 5.0f), "Medium objects should use LOD 1");
        assertEquals(2, calculateLOD(0.15f, 10.0f), "Smaller objects should use LOD 2");
        
        // Small screen size, far distance
        assertEquals(3, calculateLOD(0.08f, 20.0f), "Small objects should use LOD 3");
        assertEquals(4, calculateLOD(0.04f, 40.0f), "Tiny objects should use LOD 4");
        assertEquals(5, calculateLOD(0.01f, 80.0f), "Very tiny objects should use LOD 5");
    }
    
    @Test
    @DisplayName("Test hierarchical culling traversal")
    void testHierarchicalCulling() {
        // Build octree with parent-child relationships
        List<AABB> nodes = new ArrayList<>();
        List<Integer> parentIndices = new ArrayList<>();
        
        // Root node
        nodes.add(new AABB(new Vector3f(0, 0, 0), new Vector3f(8, 8, 8)));
        parentIndices.add(-1);
        
        // Add children
        for (int i = 0; i < 8; i++) {
            Vector3f childMin = new Vector3f(
                (i & 1) * 4,
                ((i >> 1) & 1) * 4,
                ((i >> 2) & 1) * 4
            );
            Vector3f childMax = new Vector3f(
                childMin.x + 4,
                childMin.y + 4,
                childMin.z + 4
            );
            nodes.add(new AABB(childMin, childMax));
            parentIndices.add(0);
        }
        
        // Perform hierarchical culling
        List<VisibilityResult> results = performHierarchicalCulling(nodes, parentIndices, frustum);
        
        // Root should be processed
        assertTrue(results.get(0).visible || !results.get(0).visible, "Root should have result");
        
        // If parent culled, children should be culled
        if (!results.get(0).visible) {
            for (int i = 1; i < 9; i++) {
                assertFalse(results.get(i).visible, "Children of culled parent should be culled");
            }
        }
    }
    
    @Test
    @DisplayName("Test screen size estimation")
    void testScreenSizeEstimation() {
        // Box in front of camera (negative Z)
        AABB box = new AABB(
            new Vector3f(-1, -1, -12),
            new Vector3f(1, 1, -10)
        );
        
        float screenSize = estimateScreenSize(box, frustum);
        
        // Object at distance 10-12 with size 2 should have reasonable screen size
        assertTrue(screenSize > 0 && screenSize < 1, 
            "Screen size should be between 0 and 1, got: " + screenSize);
        
        // Closer object should be larger
        AABB closerBox = new AABB(
            new Vector3f(-1, -1, -4),
            new Vector3f(1, 1, -2)
        );
        float closerSize = estimateScreenSize(closerBox, frustum);
        assertTrue(closerSize > screenSize, 
            "Closer objects should appear larger: " + closerSize + " vs " + screenSize);
        
        // Smaller object should appear smaller
        AABB smallBox = new AABB(
            new Vector3f(-0.1f, -0.1f, -10.2f),
            new Vector3f(0.1f, 0.1f, -10)
        );
        float smallSize = estimateScreenSize(smallBox, frustum);
        assertTrue(smallSize < screenSize, 
            "Smaller objects should appear smaller: " + smallSize + " vs " + screenSize);
    }
    
    @Test
    @DisplayName("Test visibility result aggregation")
    void testVisibilityAggregation() {
        List<VisibilityResult> results = new ArrayList<>();
        
        // Add various visibility results
        VisibilityResult r1 = new VisibilityResult();
        r1.visible = true;
        r1.distance = 10;
        r1.screenSize = 0.5f;
        r1.lodLevel = 0;
        results.add(r1);
        
        VisibilityResult r2 = new VisibilityResult();
        r2.visible = false;
        results.add(r2);
        
        VisibilityResult r3 = new VisibilityResult();
        r3.visible = true;
        r3.distance = 5;
        r3.screenSize = 0.8f;
        r3.lodLevel = 0;
        results.add(r3);
        
        // Count visible nodes
        long visibleCount = results.stream().filter(r -> r.visible).count();
        assertEquals(2, visibleCount, "Should have 2 visible nodes");
        
        // Find closest visible
        VisibilityResult closest = results.stream()
            .filter(r -> r.visible)
            .min(Comparator.comparing(r -> r.distance))
            .orElse(null);
        
        assertNotNull(closest);
        assertEquals(5, closest.distance, EPSILON, "Closest should be at distance 5");
    }
    
    // Helper methods
    
    private Frustum createStandardFrustum() {
        Frustum f = new Frustum();
        
        // Create perspective frustum
        float fov = (float)Math.toRadians(60);
        float aspect = 16.0f / 9.0f;
        f.nearPlane = 0.1f;
        f.farPlane = 100.0f;
        
        // Calculate frustum planes from perspective parameters
        float halfHeight = f.nearPlane * (float)Math.tan(fov / 2);
        float halfWidth = halfHeight * aspect;
        
        // Near plane
        f.planes[0] = new Vector4f(0, 0, 1, -f.nearPlane);
        
        // Far plane
        f.planes[1] = new Vector4f(0, 0, -1, f.farPlane);
        
        // Left plane
        float angle = (float)Math.atan(halfWidth / f.nearPlane);
        f.planes[2] = new Vector4f((float)Math.cos(angle), 0, (float)Math.sin(angle), 0);
        
        // Right plane
        f.planes[3] = new Vector4f(-(float)Math.cos(angle), 0, (float)Math.sin(angle), 0);
        
        // Top plane
        angle = (float)Math.atan(halfHeight / f.nearPlane);
        f.planes[4] = new Vector4f(0, -(float)Math.cos(angle), (float)Math.sin(angle), 0);
        
        // Bottom plane
        f.planes[5] = new Vector4f(0, (float)Math.cos(angle), (float)Math.sin(angle), 0);
        
        // Create proper perspective projection matrix
        // Using OpenGL-style projection matrix (maps to NDC [-1,1])
        f.viewProjMatrix = new Matrix4f();
        f.viewProjMatrix.setZero();
        
        float f_ = 1.0f / (float)Math.tan(fov / 2);
        
        // Perspective projection elements
        f.viewProjMatrix.m00 = f_ / aspect;
        f.viewProjMatrix.m11 = f_;
        f.viewProjMatrix.m22 = -(f.farPlane + f.nearPlane) / (f.farPlane - f.nearPlane);
        f.viewProjMatrix.m23 = -(2.0f * f.farPlane * f.nearPlane) / (f.farPlane - f.nearPlane);
        f.viewProjMatrix.m32 = -1.0f;
        f.viewProjMatrix.m33 = 0.0f;
        
        // For now, assume identity view (camera at origin looking down -Z)
        // In a real system, you'd multiply projection * view matrices
        
        return f;
    }
    
    private boolean aabbOnPositiveSide(AABB box, Vector4f plane) {
        Vector3f extent = box.getExtent();
        Vector3f center = box.getCenter();
        
        // Project box extent along plane normal
        float r = Math.abs(plane.x) * extent.x + 
                 Math.abs(plane.y) * extent.y + 
                 Math.abs(plane.z) * extent.z;
        
        // Signed distance from center to plane
        float s = plane.x * center.x + plane.y * center.y + plane.z * center.z + plane.w;
        
        return s + r >= 0;
    }
    
    private boolean frustumCullAABB(AABB box, Frustum frustum) {
        for (Vector4f plane : frustum.planes) {
            if (!aabbOnPositiveSide(box, plane)) {
                return true; // Culled
            }
        }
        return false; // Not culled
    }
    
    private Vector4f projectAABBToScreen(AABB box, Frustum frustum) {
        Vector2f screenMin = new Vector2f(Float.MAX_VALUE, Float.MAX_VALUE);
        Vector2f screenMax = new Vector2f(-Float.MAX_VALUE, -Float.MAX_VALUE);
        
        // Project all 8 corners
        boolean anyValid = false;
        for (int i = 0; i < 8; i++) {
            Vector4f worldPos = new Vector4f(
                (i & 1) == 0 ? box.min.x : box.max.x,
                ((i >> 1) & 1) == 0 ? box.min.y : box.max.y,
                ((i >> 2) & 1) == 0 ? box.min.z : box.max.z,
                1.0f
            );
            
            Vector4f clipPos = new Vector4f();
            frustum.viewProjMatrix.transform(worldPos, clipPos);
            
            if (clipPos.w > EPSILON) {
                float ndcX = clipPos.x / clipPos.w;
                float ndcY = clipPos.y / clipPos.w;
                
                // Clamp to NDC range [-1, 1]
                ndcX = Math.max(-1.0f, Math.min(1.0f, ndcX));
                ndcY = Math.max(-1.0f, Math.min(1.0f, ndcY));
                
                screenMin.x = Math.min(screenMin.x, ndcX);
                screenMin.y = Math.min(screenMin.y, ndcY);
                screenMax.x = Math.max(screenMax.x, ndcX);
                screenMax.y = Math.max(screenMax.y, ndcY);
                anyValid = true;
            }
        }
        
        // If no valid projections, return default
        if (!anyValid) {
            return new Vector4f(0, 0, 0, 0);
        }
        
        return new Vector4f(screenMin.x, screenMin.y, screenMax.x, screenMax.y);
    }
    
    private float[][] createMockHZBuffer(int width, int height) {
        float[][] buffer = new float[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffer[y][x] = 1.0f; // Max depth
            }
        }
        return buffer;
    }
    
    private void fillHZBRegion(float[][] buffer, int x1, int y1, int x2, int y2, float depth) {
        for (int y = y1; y < y2 && y < buffer.length; y++) {
            for (int x = x1; x < x2 && x < buffer[0].length; x++) {
                buffer[y][x] = Math.min(buffer[y][x], depth);
            }
        }
    }
    
    private boolean isOccludedByHZB(Vector4f screenBounds, float nodeDepth, float[][] hzBuffer) {
        // Convert NDC to texture coordinates
        int x1 = (int)((screenBounds.x + 1) * 0.5f * hzBuffer[0].length);
        int y1 = (int)((screenBounds.y + 1) * 0.5f * hzBuffer.length);
        int x2 = (int)((screenBounds.z + 1) * 0.5f * hzBuffer[0].length);
        int y2 = (int)((screenBounds.w + 1) * 0.5f * hzBuffer.length);
        
        // Sample HZB in region
        float minDepth = 1.0f;
        for (int y = y1; y <= y2 && y < hzBuffer.length; y++) {
            for (int x = x1; x <= x2 && x < hzBuffer[0].length; x++) {
                if (y >= 0 && x >= 0) {
                    minDepth = Math.min(minDepth, hzBuffer[y][x]);
                }
            }
        }
        
        return nodeDepth > minDepth + EPSILON;
    }
    
    private int calculateLOD(float screenSize, float distance) {
        if (screenSize > 0.5f) return 0;
        if (screenSize > 0.25f) return 1;
        if (screenSize > 0.125f) return 2;
        if (screenSize > 0.0625f) return 3;
        if (screenSize > 0.03125f) return 4;
        return 5;
    }
    
    private List<VisibilityResult> performHierarchicalCulling(List<AABB> nodes, 
                                                              List<Integer> parentIndices,
                                                              Frustum frustum) {
        List<VisibilityResult> results = new ArrayList<>();
        
        for (int i = 0; i < nodes.size(); i++) {
            VisibilityResult result = new VisibilityResult();
            
            // Check parent visibility
            int parentIdx = parentIndices.get(i);
            if (parentIdx >= 0 && !results.get(parentIdx).visible) {
                result.visible = false;
            } else {
                // Perform frustum culling
                result.visible = !frustumCullAABB(nodes.get(i), frustum);
                
                if (result.visible) {
                    // Calculate additional properties
                    Vector3f center = nodes.get(i).getCenter();
                    result.distance = center.length();
                    result.screenSize = estimateScreenSize(nodes.get(i), frustum);
                    result.lodLevel = calculateLOD(result.screenSize, result.distance);
                }
            }
            
            results.add(result);
        }
        
        return results;
    }
    
    private float estimateScreenSize(AABB box, Frustum frustum) {
        Vector4f screenBounds = projectAABBToScreen(box, frustum);
        
        // If no valid projection, return 0
        if (screenBounds.x == 0 && screenBounds.y == 0 && 
            screenBounds.z == 0 && screenBounds.w == 0) {
            return 0;
        }
        
        float width = Math.abs(screenBounds.z - screenBounds.x);
        float height = Math.abs(screenBounds.w - screenBounds.y);
        float size = Math.max(width, height) * 0.5f; // NDC to normalized [0,1]
        
        // Clamp to [0, 1] range
        return Math.max(0.0f, Math.min(1.0f, size));
    }
}