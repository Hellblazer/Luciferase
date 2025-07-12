/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.prism;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Geometry utilities for prism spatial elements.
 * 
 * Provides geometric calculations for prisms including centroid computation,
 * bounding box calculation, and vertex generation.
 * 
 * @author hal.hildebrand
 */
public final class PrismGeometry {
    
    private PrismGeometry() {
        // Utility class
    }
    
    /**
     * Compute the centroid of a prism in normalized coordinates.
     * 
     * @param prismKey the prism key
     * @return array of [x, y, z] centroid coordinates in [0,1] space
     */
    public static float[] computeCentroid(PrismKey prismKey) {
        var triangle = prismKey.getTriangle();
        var line = prismKey.getLine();
        
        // Get 2D centroid from triangle
        var triangleCentroid = triangle.getCentroidWorldCoordinates();
        
        // Get 1D centroid from line
        var lineCentroid = line.getCentroidWorldCoordinate();
        
        return new float[] {
            triangleCentroid[0],
            triangleCentroid[1],
            lineCentroid
        };
    }
    
    /**
     * Compute the axis-aligned bounding box of a prism in normalized coordinates.
     * 
     * @param prismKey the prism key
     * @return array of [minX, minY, minZ, maxX, maxY, maxZ] in [0,1] space
     */
    public static float[] computeBoundingBox(PrismKey prismKey) {
        var triangle = prismKey.getTriangle();
        var line = prismKey.getLine();
        
        // Get 2D bounds from triangle
        var triangleBounds = triangle.getWorldBounds();
        
        // Get 1D bounds from line
        var lineBounds = line.getWorldBounds();
        
        return new float[] {
            triangleBounds[0],  // minX
            triangleBounds[1],  // minY
            lineBounds[0],      // minZ
            triangleBounds[2],  // maxX
            triangleBounds[3],  // maxY
            lineBounds[1]       // maxZ
        };
    }
    
    /**
     * Get the vertices of a prism.
     * 
     * For a triangular prism, we have 6 vertices:
     * - 3 vertices for the bottom triangle
     * - 3 vertices for the top triangle
     * 
     * @param prismKey the prism key
     * @return list of 6 vertices
     */
    public static List<Point3f> getVertices(PrismKey prismKey) {
        var vertices = new ArrayList<Point3f>(6);
        
        var triangle = prismKey.getTriangle();
        var line = prismKey.getLine();
        
        // Get triangle vertices in 2D
        var triangleVertices = getTriangleVertices(triangle);
        
        // Get line bounds
        var lineBounds = line.getWorldBounds();
        float minZ = lineBounds[0];
        float maxZ = lineBounds[1];
        
        // Create bottom triangle vertices
        for (var v2d : triangleVertices) {
            vertices.add(new Point3f(v2d[0], v2d[1], minZ));
        }
        
        // Create top triangle vertices
        for (var v2d : triangleVertices) {
            vertices.add(new Point3f(v2d[0], v2d[1], maxZ));
        }
        
        return vertices;
    }
    
    /**
     * Get the 2D vertices of a triangle.
     * 
     * @param triangle the triangle
     * @return list of 3 vertices in 2D (x,y) coordinates
     */
    private static List<float[]> getTriangleVertices(Triangle triangle) {
        var vertices = new ArrayList<float[]>(3);
        
        var bounds = triangle.getWorldBounds();
        float minX = bounds[0];
        float minY = bounds[1];
        float maxX = bounds[2];
        float maxY = bounds[3];
        
        // Create proper triangular vertices for triangular prism
        // Standard right triangle in first quadrant with vertices at:
        // (minX, minY), (maxX, minY), (minX, maxY)
        // This ensures we stay within triangular constraint x + y <= 1.0
        
        // Bottom-left vertex (origin of triangle)
        vertices.add(new float[] {minX, minY});
        
        // Bottom-right vertex 
        vertices.add(new float[] {maxX, minY});
        
        // Top-left vertex (forms right triangle)
        vertices.add(new float[] {minX, maxY});
        
        return vertices;
    }
    
    /**
     * Test if a point is contained within a prism.
     * 
     * @param prismKey the prism key
     * @param point the point to test (in normalized coordinates)
     * @return true if the point is inside the prism
     */
    public static boolean contains(PrismKey prismKey, Point3f point) {
        var triangle = prismKey.getTriangle();
        var line = prismKey.getLine();
        
        // Check 2D containment in triangle
        if (!triangle.contains(point.x, point.y)) {
            return false;
        }
        
        // Check 1D containment in line
        if (!line.contains(point.z)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Compute the volume of a prism.
     * 
     * @param prismKey the prism key
     * @return the volume in normalized space
     */
    public static float computeVolume(PrismKey prismKey) {
        var triangle = prismKey.getTriangle();
        var line = prismKey.getLine();
        
        // Compute triangle area
        float triangleArea = computeTriangleArea(triangle);
        
        // Compute line height
        var lineBounds = line.getWorldBounds();
        float height = lineBounds[1] - lineBounds[0];
        
        // Volume = area * height
        return triangleArea * height;
    }
    
    /**
     * Compute the area of a triangle.
     * 
     * @param triangle the triangle
     * @return the area in normalized space
     */
    private static float computeTriangleArea(Triangle triangle) {
        // For simplified implementation, use half the bounding box area
        // In full implementation, would compute exact triangle area
        var bounds = triangle.getWorldBounds();
        float width = bounds[2] - bounds[0];
        float height = bounds[3] - bounds[1];
        
        // Approximate area for right triangle
        return 0.5f * width * height;
    }
    
    /**
     * Convert prism vertices to float array format.
     * This is a utility method used by the collision detection system.
     * 
     * @param prism the prism key
     * @return vertices as float array
     */
    public static float[][] getVerticesAsFloatArray(PrismKey prism) {
        var vertices = getVertices(prism);
        float[][] result = new float[vertices.size()][];
        for (int i = 0; i < vertices.size(); i++) {
            Point3f vertex = vertices.get(i);
            result[i] = new float[]{vertex.x, vertex.y, vertex.z};
        }
        return result;
    }
}