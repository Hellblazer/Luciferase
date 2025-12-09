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

package com.hellblazer.luciferase.portal.esvo;

import com.hellblazer.luciferase.geometry.Point3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for ProceduralVoxelGenerator.
 * 
 * @author hal.hildebrand
 */
class ProceduralVoxelGeneratorTest {
    
    private final ProceduralVoxelGenerator generator = new ProceduralVoxelGenerator();
    
    @Test
    void testGenerateSphere() {
        var resolution = 32;
        var voxels = generator.generateSphere(resolution);
        
        assertNotNull(voxels);
        assertFalse(voxels.isEmpty(), "Sphere should contain voxels");
        
        // Verify all voxels are within bounds
        for (var voxel : voxels) {
            assertTrue(voxel.x >= 0 && voxel.x < resolution);
            assertTrue(voxel.y >= 0 && voxel.y < resolution);
            assertTrue(voxel.z >= 0 && voxel.z < resolution);
        }
        
        // Verify approximate spherical shape by checking center and radius
        var center = resolution / 2.0f;
        var radius = resolution / 2.0f * 0.8f;
        
        // Check that center point is filled
        var centerPoint = new Point3i((int) center, (int) center, (int) center);
        assertTrue(voxels.contains(centerPoint), "Sphere center should be filled");
        
        // Check that corners are empty
        assertFalse(voxels.contains(new Point3i(0, 0, 0)), "Corner should be empty");
        assertFalse(voxels.contains(new Point3i(resolution - 1, resolution - 1, resolution - 1)), 
                   "Corner should be empty");
        
        // Verify approximate volume (sphere volume = 4/3 * pi * r^3)
        var expectedVolume = (4.0 / 3.0) * Math.PI * radius * radius * radius;
        var actualVolume = voxels.size();
        var tolerance = expectedVolume * 0.15; // 15% tolerance for discretization
        
        assertTrue(Math.abs(actualVolume - expectedVolume) < tolerance,
                  String.format("Sphere volume should be approximately %.0f, got %d", 
                               expectedVolume, actualVolume));
    }
    
    @Test
    void testGenerateCube() {
        var resolution = 32;
        var voxels = generator.generateCube(resolution);
        
        assertNotNull(voxels);
        assertFalse(voxels.isEmpty(), "Cube should contain voxels");
        
        // Verify all voxels are within bounds
        for (var voxel : voxels) {
            assertTrue(voxel.x >= 0 && voxel.x < resolution);
            assertTrue(voxel.y >= 0 && voxel.y < resolution);
            assertTrue(voxel.z >= 0 && voxel.z < resolution);
        }
        
        // Calculate expected size with margin
        var margin = (int) (resolution * 0.1f);
        var size = resolution - 2 * margin;
        var expectedCount = size * size * size;
        
        assertEquals(expectedCount, voxels.size(), 
                    "Cube should contain exactly size^3 voxels");
        
        // Verify corners within margin are empty
        assertFalse(voxels.contains(new Point3i(0, 0, 0)), "Corner should be empty");
        assertFalse(voxels.contains(new Point3i(resolution - 1, resolution - 1, resolution - 1)), 
                   "Corner should be empty");
        
        // Verify center is filled
        var center = resolution / 2;
        assertTrue(voxels.contains(new Point3i(center, center, center)), 
                  "Cube center should be filled");
    }
    
    @Test
    void testGenerateTorus() {
        var resolution = 32;
        var voxels = generator.generateTorus(resolution);
        
        assertNotNull(voxels);
        assertFalse(voxels.isEmpty(), "Torus should contain voxels");
        
        // Verify all voxels are within bounds
        for (var voxel : voxels) {
            assertTrue(voxel.x >= 0 && voxel.x < resolution);
            assertTrue(voxel.y >= 0 && voxel.y < resolution);
            assertTrue(voxel.z >= 0 && voxel.z < resolution);
        }
        
        // Verify center is empty (hole in donut)
        var center = resolution / 2;
        assertFalse(voxels.contains(new Point3i(center, center, center)), 
                   "Torus center should be empty (hole)");
        
        // Verify corners are empty
        assertFalse(voxels.contains(new Point3i(0, 0, 0)), "Corner should be empty");
        
        // Verify torus ring exists by checking points on the major radius
        var majorRadius = resolution / 2.0f * 0.6f;
        var ringPoint = new Point3i(
            (int) (center + majorRadius), 
            center, 
            center
        );
        
        // Should have voxels near the ring
        var hasRingVoxels = voxels.stream()
            .anyMatch(v -> {
                var dist = Math.sqrt(
                    Math.pow(v.x - ringPoint.x, 2) + 
                    Math.pow(v.y - ringPoint.y, 2) + 
                    Math.pow(v.z - ringPoint.z, 2)
                );
                return dist < 3; // Within 3 voxels of ring point
            });
        
        assertTrue(hasRingVoxels, "Should have voxels near the torus ring");
    }
    
    @Test
    void testGenerateMengerSponge() {
        var resolution = 27; // Power of 3 for clean subdivision
        var voxels = generator.generateMengerSponge(resolution);
        
        assertNotNull(voxels);
        assertFalse(voxels.isEmpty(), "Menger sponge should contain voxels");
        
        // Verify all voxels are within bounds
        for (var voxel : voxels) {
            assertTrue(voxel.x >= 0 && voxel.x < resolution);
            assertTrue(voxel.y >= 0 && voxel.y < resolution);
            assertTrue(voxel.z >= 0 && voxel.z < resolution);
        }
        
        // Verify it's less than a full cube (has holes)
        var margin = (int) (resolution * 0.1f);
        var size = resolution - 2 * margin;
        var fullCubeSize = size * size * size;
        
        assertTrue(voxels.size() < fullCubeSize, 
                  "Menger sponge should have fewer voxels than a full cube");
        
        // Verify approximate fractal dimension
        // Menger sponge has 20 cubes at level 1 (27 - 7 removed)
        var expectedRatio = 20.0 / 27.0;
        var actualRatio = (double) voxels.size() / fullCubeSize;
        var tolerance = 0.2; // 20% tolerance
        
        assertTrue(Math.abs(actualRatio - expectedRatio) < tolerance,
                  String.format("Menger sponge ratio should be approximately %.2f, got %.2f", 
                               expectedRatio, actualRatio));
    }
    
    @Test
    void testGenerateWithEnum() {
        var resolution = 16;
        
        for (var shape : ProceduralVoxelGenerator.Shape.values()) {
            var voxels = generator.generate(shape, resolution);
            assertNotNull(voxels, "Shape " + shape + " should generate voxels");
            assertFalse(voxels.isEmpty(), "Shape " + shape + " should not be empty");
        }
    }
    
    @Test
    void testGetShapeName() {
        assertEquals("Sphere", ProceduralVoxelGenerator.getShapeName(
            ProceduralVoxelGenerator.Shape.SPHERE));
        assertEquals("Cube", ProceduralVoxelGenerator.getShapeName(
            ProceduralVoxelGenerator.Shape.CUBE));
        assertEquals("Torus", ProceduralVoxelGenerator.getShapeName(
            ProceduralVoxelGenerator.Shape.TORUS));
        assertEquals("Menger Sponge", ProceduralVoxelGenerator.getShapeName(
            ProceduralVoxelGenerator.Shape.MENGER_SPONGE));
    }
    
    @Test
    void testEstimateVoxelCount() {
        var resolution = 32;
        
        for (var shape : ProceduralVoxelGenerator.Shape.values()) {
            var estimate = ProceduralVoxelGenerator.estimateVoxelCount(shape, resolution);
            var actual = generator.generate(shape, resolution).size();
            
            // Estimate should be within reasonable range (50% tolerance)
            var tolerance = actual * 0.5;
            assertTrue(Math.abs(estimate - actual) < tolerance,
                      String.format("Estimate for %s should be close to actual: estimate=%d, actual=%d", 
                                   shape, estimate, actual));
        }
    }
    
    @Test
    void testSmallResolution() {
        var resolution = 8;
        
        for (var shape : ProceduralVoxelGenerator.Shape.values()) {
            var voxels = generator.generate(shape, resolution);
            assertNotNull(voxels);
            assertFalse(voxels.isEmpty(), "Shape " + shape + " should work at low resolution");
        }
    }
    
    @Test
    void testLargeResolution() {
        var resolution = 64;
        
        // Test just sphere for performance (others would be too slow)
        var voxels = generator.generateSphere(resolution);
        assertNotNull(voxels);
        assertFalse(voxels.isEmpty());
        
        // Verify reasonable voxel count (not exponentially large)
        assertTrue(voxels.size() < resolution * resolution * resolution,
                  "Voxel count should be less than grid size");
    }
    
    @Test
    void testVoxelUniqueness() {
        var resolution = 32;
        var voxels = generator.generateSphere(resolution);
        
        // Convert to set to check for duplicates
        var uniqueVoxels = new java.util.HashSet<>(voxels);
        assertEquals(voxels.size(), uniqueVoxels.size(), 
                    "All voxels should be unique (no duplicates)");
    }
    
    @Test
    void testBoundsConstraints() {
        var resolution = 32;
        
        for (var shape : ProceduralVoxelGenerator.Shape.values()) {
            var voxels = generator.generate(shape, resolution);
            
            // Verify no voxels are outside bounds
            for (var voxel : voxels) {
                assertTrue(voxel.x >= 0 && voxel.x < resolution,
                          String.format("Voxel X out of bounds for %s: %d", shape, voxel.x));
                assertTrue(voxel.y >= 0 && voxel.y < resolution,
                          String.format("Voxel Y out of bounds for %s: %d", shape, voxel.y));
                assertTrue(voxel.z >= 0 && voxel.z < resolution,
                          String.format("Voxel Z out of bounds for %s: %d", shape, voxel.z));
            }
        }
    }
}
