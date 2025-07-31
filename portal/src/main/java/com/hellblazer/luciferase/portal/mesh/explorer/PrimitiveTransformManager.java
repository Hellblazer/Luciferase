/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.mesh.explorer;

import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.util.HashMap;
import java.util.Map;

/**
 * Unified manager for transform-based rendering of all primitive types in the visualization.
 * This class creates and caches reference meshes for each primitive type and provides
 * transformation calculations to position, scale, and orient instances as needed.
 * 
 * Based on proven patterns from TetreeCellViews, but generalized for all primitives.
 *
 * @author hal.hildebrand
 */
public class PrimitiveTransformManager {
    
    /**
     * Supported primitive types
     */
    public enum PrimitiveType {
        SPHERE,
        CYLINDER,
        BOX,
        LINE,
        TETRAHEDRON_S0,
        TETRAHEDRON_S1,
        TETRAHEDRON_S2,
        TETRAHEDRON_S3,
        TETRAHEDRON_S4,
        TETRAHEDRON_S5
    }
    
    /**
     * Key for caching transforms
     */
    private static class TransformKey {
        final PrimitiveType type;
        final Point3f position;
        final Vector3f scale;
        final Vector3f rotation;
        
        TransformKey(PrimitiveType type, Point3f position, Vector3f scale, Vector3f rotation) {
            this.type = type;
            this.position = position;
            this.scale = scale;
            this.rotation = rotation;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TransformKey that = (TransformKey) o;
            return type == that.type &&
                   position.equals(that.position) &&
                   scale.equals(that.scale) &&
                   (rotation == null ? that.rotation == null : rotation.equals(that.rotation));
        }
        
        @Override
        public int hashCode() {
            int result = type.hashCode();
            result = 31 * result + position.hashCode();
            result = 31 * result + scale.hashCode();
            result = 31 * result + (rotation != null ? rotation.hashCode() : 0);
            return result;
        }
    }
    
    // Standard tetrahedron edges (vertex indices)
    private static final int[][] TETRAHEDRON_EDGES = {
        {0, 1}, {0, 2}, {0, 3},  // Edges from vertex 0
        {1, 2}, {1, 3},          // Edges from vertex 1
        {2, 3}                   // Edge from vertex 2 to 3
    };
    
    // Reference meshes for each primitive type
    private final Map<PrimitiveType, TriangleMesh> referenceMeshes = new HashMap<>();
    
    // Transform cache for performance
    private final Map<TransformKey, Affine> transformCache = new HashMap<>();
    
    // Material pool for efficient material management
    final MaterialPool materialPool;
    
    // Configuration
    private final int sphereSegments;
    private final double defaultEdgeThickness = 0.01;
    
    /**
     * Initialize the transform manager with default settings.
     */
    public PrimitiveTransformManager() {
        this(24, new MaterialPool()); // 24 segments for smooth spheres
    }
    
    /**
     * Initialize the transform manager with custom settings.
     *
     * @param sphereSegments Number of segments for sphere meshes
     * @param materialPool The material pool to use
     */
    public PrimitiveTransformManager(int sphereSegments, MaterialPool materialPool) {
        this.sphereSegments = sphereSegments;
        this.materialPool = materialPool;
        initializeReferenceMeshes();
    }
    
    /**
     * Create a sphere instance.
     *
     * @param position The position in world coordinates
     * @param radius The radius of the sphere
     * @param material The material to apply
     * @return A transformed MeshView
     */
    public MeshView createSphere(Point3f position, float radius, Material material) {
        return createPrimitive(PrimitiveType.SPHERE, position, 
                             new Vector3f(radius * 2, radius * 2, radius * 2), 
                             null, material);
    }
    
    /**
     * Create a cylinder instance (e.g., for axes).
     *
     * @param position The position of the cylinder center
     * @param radius The radius of the cylinder
     * @param height The height of the cylinder
     * @param rotation The rotation vector (x, y, z angles in degrees)
     * @param material The material to apply
     * @return A transformed MeshView
     */
    public MeshView createCylinder(Point3f position, float radius, float height, 
                                   Vector3f rotation, Material material) {
        return createPrimitive(PrimitiveType.CYLINDER, position,
                             new Vector3f(radius * 2, height, radius * 2),
                             rotation, material);
    }
    
    /**
     * Create a box instance.
     *
     * @param position The position of the box center
     * @param size The size vector (width, height, depth)
     * @param material The material to apply
     * @return A transformed MeshView
     */
    public MeshView createBox(Point3f position, Vector3f size, Material material) {
        return createPrimitive(PrimitiveType.BOX, position, size, null, material);
    }
    
    /**
     * Create a line instance (thin cylinder).
     *
     * @param start The start point
     * @param end The end point
     * @param thickness The line thickness
     * @param material The material to apply
     * @return A transformed MeshView
     */
    public MeshView createLine(Point3f start, Point3f end, float thickness, Material material) {
        // Calculate position (midpoint)
        Point3f position = new Point3f(
            (start.x + end.x) / 2,
            (start.y + end.y) / 2,
            (start.z + end.z) / 2
        );
        
        // Calculate length
        float dx = end.x - start.x;
        float dy = end.y - start.y;
        float dz = end.z - start.z;
        float length = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
        
        // Calculate rotation to align with line direction
        Vector3f rotation = calculateLineRotation(start, end);
        
        return createPrimitive(PrimitiveType.LINE, position,
                             new Vector3f(thickness, length, thickness),
                             rotation, material);
    }
    
    /**
     * Create a tetrahedron instance.
     *
     * @param type The tetrahedron type (0-5)
     * @param position The position of the tetrahedron anchor
     * @param size The edge length of the tetrahedron
     * @param material The material to apply
     * @return A transformed MeshView
     */
    public MeshView createTetrahedron(int type, Point3f position, float size, Material material) {
        PrimitiveType primType = switch (type) {
            case 0 -> PrimitiveType.TETRAHEDRON_S0;
            case 1 -> PrimitiveType.TETRAHEDRON_S1;
            case 2 -> PrimitiveType.TETRAHEDRON_S2;
            case 3 -> PrimitiveType.TETRAHEDRON_S3;
            case 4 -> PrimitiveType.TETRAHEDRON_S4;
            case 5 -> PrimitiveType.TETRAHEDRON_S5;
            default -> throw new IllegalArgumentException("Invalid tetrahedron type: " + type);
        };
        
        return createPrimitive(primType, position,
                             new Vector3f(size, size, size),
                             null, material);
    }
    
    /**
     * Clear the transform cache.
     */
    public void clearTransformCache() {
        transformCache.clear();
    }
    
    /**
     * Get statistics about the current state.
     */
    public Map<String, Integer> getStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("referenceMeshCount", referenceMeshes.size());
        stats.put("transformCacheSize", transformCache.size());
        stats.put("materialPoolSize", materialPool.getPoolSize());
        return stats;
    }
    
    /**
     * Create a primitive with specified parameters.
     */
    private MeshView createPrimitive(PrimitiveType type, Point3f position, 
                                    Vector3f scale, Vector3f rotation, 
                                    Material material) {
        TriangleMesh referenceMesh = referenceMeshes.get(type);
        if (referenceMesh == null) {
            throw new IllegalStateException("No reference mesh for type: " + type);
        }
        
        // Create mesh view (lightweight - just references the mesh)
        MeshView meshView = new MeshView(referenceMesh);
        
        // Calculate and apply transform
        Affine transform = calculateTransform(type, position, scale, rotation);
        meshView.getTransforms().add(transform);
        
        // Apply material
        meshView.setMaterial(material);
        
        return meshView;
    }
    
    /**
     * Calculate the transform for a primitive.
     */
    private Affine calculateTransform(PrimitiveType type, Point3f position, 
                                     Vector3f scale, Vector3f rotation) {
        // Check cache first
        TransformKey key = new TransformKey(type, position, scale, rotation);
        Affine cached = transformCache.get(key);
        if (cached != null) {
            return new Affine(cached); // Return copy
        }
        
        // Create new transform
        Affine transform = new Affine();
        
        // The order matters! We need to:
        // 1. Apply translation to position the object
        // 2. Apply rotation around the object's new position
        // 3. Apply scale around the object's center
        
        // However, JavaFX applies transforms in reverse order when using append
        // So we need to append in the order: translate, rotate, scale
        
        // Apply translation first (will be applied last in the transform chain)
        transform.appendTranslation(position.x, position.y, position.z);
        
        // Apply rotation if specified
        if (rotation != null) {
            if (rotation.x != 0) {
                transform.appendRotation(rotation.x, Point3D.ZERO, Rotate.X_AXIS);
            }
            if (rotation.y != 0) {
                transform.appendRotation(rotation.y, Point3D.ZERO, Rotate.Y_AXIS);
            }
            if (rotation.z != 0) {
                transform.appendRotation(rotation.z, Point3D.ZERO, Rotate.Z_AXIS);
            }
        }
        
        // Apply scale last (will be applied first in the transform chain)
        transform.appendScale(scale.x, scale.y, scale.z);
        
        // Cache the transform
        transformCache.put(key, new Affine(transform));
        
        return transform;
    }
    
    /**
     * Initialize reference meshes for all primitive types.
     */
    private void initializeReferenceMeshes() {
        referenceMeshes.put(PrimitiveType.SPHERE, createUnitSphereMesh());
        referenceMeshes.put(PrimitiveType.CYLINDER, createUnitCylinderMesh());
        referenceMeshes.put(PrimitiveType.BOX, createUnitBoxMesh());
        referenceMeshes.put(PrimitiveType.LINE, createUnitCylinderMesh()); // Reuse cylinder
        
        // Create tetrahedron meshes for each type S0-S5
        // Using the ACTUAL vertex ordering from Tet.coordinates()
        int[][] tetIndices = { 
            { 0, 1, 3, 7 },  // S0: vertices 0, 1, 3, 7 of cube
            { 0, 2, 3, 7 },  // S1: vertices 0, 2, 3, 7 of cube
            { 0, 4, 5, 7 },  // S2: vertices 0, 4, 5, 7 of cube
            { 0, 4, 6, 7 },  // S3: vertices 0, 4, 6, 7 of cube
            { 0, 1, 5, 7 },  // S4: vertices 0, 1, 5, 7 of cube
            { 0, 2, 6, 7 }   // S5: vertices 0, 2, 6, 7 of cube
        };
        
        for (int type = 0; type < 6; type++) {
            // Create vertices from cube corners using the indices
            Point3i[] vertices = new Point3i[4];
            for (int i = 0; i < 4; i++) {
                int idx = tetIndices[type][i];
                // Convert cube vertex index to (x,y,z)
                int x = (idx & 1);
                int y = (idx >> 1) & 1;
                int z = (idx >> 2) & 1;
                vertices[i] = new Point3i(x, y, z);
            }
            
            PrimitiveType primType = switch (type) {
                case 0 -> PrimitiveType.TETRAHEDRON_S0;
                case 1 -> PrimitiveType.TETRAHEDRON_S1;
                case 2 -> PrimitiveType.TETRAHEDRON_S2;
                case 3 -> PrimitiveType.TETRAHEDRON_S3;
                case 4 -> PrimitiveType.TETRAHEDRON_S4;
                case 5 -> PrimitiveType.TETRAHEDRON_S5;
                default -> throw new IllegalStateException("Invalid type: " + type);
            };
            referenceMeshes.put(primType, createUnitTetrahedronMesh(vertices));
        }
    }
    
    /**
     * Create a unit sphere mesh (radius = 0.5).
     */
    private TriangleMesh createUnitSphereMesh() {
        TriangleMesh mesh = new TriangleMesh();
        
        int latitudeBands = sphereSegments;
        int longitudeBands = sphereSegments;
        float radius = 0.5f;
        
        // Generate vertices
        for (int lat = 0; lat <= latitudeBands; lat++) {
            float theta = lat * (float)Math.PI / latitudeBands;
            float sinTheta = (float)Math.sin(theta);
            float cosTheta = (float)Math.cos(theta);
            
            for (int lon = 0; lon <= longitudeBands; lon++) {
                float phi = lon * 2 * (float)Math.PI / longitudeBands;
                float sinPhi = (float)Math.sin(phi);
                float cosPhi = (float)Math.cos(phi);
                
                float x = cosPhi * sinTheta;
                float y = cosTheta;
                float z = sinPhi * sinTheta;
                
                mesh.getPoints().addAll(x * radius, y * radius, z * radius);
            }
        }
        
        // Generate texture coordinates
        for (int lat = 0; lat <= latitudeBands; lat++) {
            for (int lon = 0; lon <= longitudeBands; lon++) {
                float u = (float)lon / longitudeBands;
                float v = (float)lat / latitudeBands;
                mesh.getTexCoords().addAll(u, v);
            }
        }
        
        // Generate faces
        for (int lat = 0; lat < latitudeBands; lat++) {
            for (int lon = 0; lon < longitudeBands; lon++) {
                int first = lat * (longitudeBands + 1) + lon;
                int second = first + longitudeBands + 1;
                
                // First triangle
                mesh.getFaces().addAll(
                    first, first,
                    second, second,
                    first + 1, first + 1
                );
                
                // Second triangle
                mesh.getFaces().addAll(
                    second, second,
                    second + 1, second + 1,
                    first + 1, first + 1
                );
            }
        }
        
        return mesh;
    }
    
    /**
     * Create a unit cylinder mesh (height = 1, radius = 0.5).
     */
    private TriangleMesh createUnitCylinderMesh() {
        TriangleMesh mesh = new TriangleMesh();
        
        int segments = sphereSegments; // Reuse sphere segments for consistency
        float radius = 0.5f;
        float height = 1.0f;
        float halfHeight = height / 2.0f;
        
        // Generate vertices for top and bottom circles
        // Center vertices
        mesh.getPoints().addAll(0, halfHeight, 0);    // Top center (index 0)
        mesh.getPoints().addAll(0, -halfHeight, 0);   // Bottom center (index 1)
        
        // Circle vertices
        for (int i = 0; i < segments; i++) {
            float angle = i * 2 * (float)Math.PI / segments;
            float x = radius * (float)Math.cos(angle);
            float z = radius * (float)Math.sin(angle);
            
            // Top circle
            mesh.getPoints().addAll(x, halfHeight, z);
            // Bottom circle
            mesh.getPoints().addAll(x, -halfHeight, z);
        }
        
        // Generate texture coordinates
        mesh.getTexCoords().addAll(0.5f, 0.5f); // Center
        for (int i = 0; i < segments; i++) {
            float angle = i * 2 * (float)Math.PI / segments;
            float u = 0.5f + 0.5f * (float)Math.cos(angle);
            float v = 0.5f + 0.5f * (float)Math.sin(angle);
            mesh.getTexCoords().addAll(u, v);
        }
        
        // Generate faces
        // Top cap
        for (int i = 0; i < segments; i++) {
            int next = (i + 1) % segments;
            mesh.getFaces().addAll(
                0, 0,                          // Top center
                2 + i * 2, 1 + i,             // Current top vertex
                2 + next * 2, 1 + next        // Next top vertex
            );
        }
        
        // Bottom cap
        for (int i = 0; i < segments; i++) {
            int next = (i + 1) % segments;
            mesh.getFaces().addAll(
                1, 0,                          // Bottom center
                3 + next * 2, 1 + next,       // Next bottom vertex
                3 + i * 2, 1 + i              // Current bottom vertex
            );
        }
        
        // Side faces
        for (int i = 0; i < segments; i++) {
            int next = (i + 1) % segments;
            int topCurrent = 2 + i * 2;
            int topNext = 2 + next * 2;
            int bottomCurrent = 3 + i * 2;
            int bottomNext = 3 + next * 2;
            
            // First triangle
            mesh.getFaces().addAll(
                topCurrent, 1 + i,
                bottomCurrent, 1 + i,
                topNext, 1 + next
            );
            
            // Second triangle
            mesh.getFaces().addAll(
                topNext, 1 + next,
                bottomCurrent, 1 + i,
                bottomNext, 1 + next
            );
        }
        
        return mesh;
    }
    
    /**
     * Create a unit box mesh (1x1x1).
     */
    private TriangleMesh createUnitBoxMesh() {
        TriangleMesh mesh = new TriangleMesh();
        
        // Vertices of unit cube centered at origin
        mesh.getPoints().addAll(
            -0.5f, -0.5f, -0.5f,  // 0
             0.5f, -0.5f, -0.5f,  // 1
             0.5f,  0.5f, -0.5f,  // 2
            -0.5f,  0.5f, -0.5f,  // 3
            -0.5f, -0.5f,  0.5f,  // 4
             0.5f, -0.5f,  0.5f,  // 5
             0.5f,  0.5f,  0.5f,  // 6
            -0.5f,  0.5f,  0.5f   // 7
        );
        
        // Texture coordinates
        mesh.getTexCoords().addAll(0, 0);
        
        // Faces (2 triangles per cube face)
        mesh.getFaces().addAll(
            // Front
            4,0, 5,0, 6,0,
            4,0, 6,0, 7,0,
            // Back
            1,0, 0,0, 3,0,
            1,0, 3,0, 2,0,
            // Left
            0,0, 4,0, 7,0,
            0,0, 7,0, 3,0,
            // Right
            5,0, 1,0, 2,0,
            5,0, 2,0, 6,0,
            // Top
            7,0, 6,0, 2,0,
            7,0, 2,0, 3,0,
            // Bottom
            0,0, 1,0, 5,0,
            0,0, 5,0, 4,0
        );
        
        return mesh;
    }
    
    /**
     * Calculate rotation to align a line from start to end.
     * Assumes the default orientation is along the Y-axis.
     */
    private Vector3f calculateLineRotation(Point3f start, Point3f end) {
        // Calculate direction vector
        float dx = end.x - start.x;
        float dy = end.y - start.y;
        float dz = end.z - start.z;
        
        // Normalize
        float length = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (length < 1e-6f) {
            return new Vector3f(0, 0, 0); // No rotation needed for zero-length line
        }
        
        dx /= length;
        dy /= length;
        dz /= length;
        
        // Calculate rotation angles to align Y-axis with direction vector
        // First rotate around Z-axis to align projection in XY plane
        float rotZ = (float)Math.toDegrees(Math.atan2(dx, dy));
        
        // Then rotate around X-axis to achieve final alignment
        float horizontalLength = (float)Math.sqrt(dx*dx + dy*dy);
        float rotX = (float)Math.toDegrees(Math.atan2(-dz, horizontalLength));
        
        return new Vector3f(rotX, 0, rotZ);
    }
    
    /**
     * Create a unit tetrahedron mesh with vertices in standard positions.
     * Copied from TetreeCellViews for consistency.
     *
     * @param vertices The vertices of the tetrahedron
     * @return A TriangleMesh representing the tetrahedron
     */
    private TriangleMesh createUnitTetrahedronMesh(Point3i[] vertices) {
        TriangleMesh mesh = new TriangleMesh();

        // Add vertices as floats (unit cube coordinates)
        for (Point3i v : vertices) {
            mesh.getPoints().addAll(v.x, v.y, v.z);
        }

        // Add texture coordinates
        mesh.getTexCoords().addAll(0, 0, 1, 0, 0.5f, 1, 0.5f, 0.5f);

        // Add faces with correct winding for outward normals
        // Fixed: reversed winding order to produce outward-pointing normals
        mesh.getFaces().addAll(0, 0, 1, 1, 2, 2,  // Face 0-1-2 (was 0-2-1)
                               0, 0, 3, 3, 1, 1,  // Face 0-3-1 (was 0-1-3)
                               0, 0, 2, 2, 3, 3,  // Face 0-2-3 (was 0-3-2)
                               1, 1, 3, 3, 2, 2   // Face 1-3-2 (was 1-2-3)
                              );

        return mesh;
    }
}