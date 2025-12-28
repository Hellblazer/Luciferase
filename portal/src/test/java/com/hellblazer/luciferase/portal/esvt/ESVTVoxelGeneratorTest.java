/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.portal.esvt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates ESVTVoxelGenerator produces shapes inscribed within S0 tetrahedron.
 */
class ESVTVoxelGeneratorTest {

    @Test
    void testInscribedSphereProducesVoxels() {
        var generator = new ESVTVoxelGenerator();
        var voxels = generator.generate(ESVTVoxelGenerator.Shape.SPHERE, 32);

        assertFalse(voxels.isEmpty(), "Inscribed sphere should produce voxels");
        System.out.printf("SPHERE at resolution 32: %d voxels%n", voxels.size());

        // Should be a reasonable count - sphere inscribed in S0 is smaller than full cube
        assertTrue(voxels.size() > 100, "Should have at least 100 voxels");
        assertTrue(voxels.size() < 32 * 32 * 32 / 6, "Should be less than 1/6 of cube (S0 is 1/6 of cube)");
    }

    @Test
    void testTetrahedronProducesVoxels() {
        var generator = new ESVTVoxelGenerator();
        var voxels = generator.generate(ESVTVoxelGenerator.Shape.TETRAHEDRON, 32);

        assertFalse(voxels.isEmpty(), "Tetrahedron should produce voxels");
        System.out.printf("TETRAHEDRON at resolution 32: %d voxels%n", voxels.size());

        // S0 tetrahedron is 1/6 of the unit cube
        int expectedMax = 32 * 32 * 32 / 6;
        assertTrue(voxels.size() <= expectedMax + 1000,
            "Tetrahedron voxels should be roughly 1/6 of cube volume");
    }

    @Test
    void testTorusProducesVoxels() {
        var generator = new ESVTVoxelGenerator();
        var voxels = generator.generate(ESVTVoxelGenerator.Shape.TORUS, 32);

        assertFalse(voxels.isEmpty(), "Torus should produce voxels");
        System.out.printf("TORUS at resolution 32: %d voxels%n", voxels.size());
    }

    @Test
    void testIncenterAndInradius() {
        var generator = new ESVTVoxelGenerator();
        var incenter = generator.getS0Incenter();
        var inradius = generator.getS0Inradius();

        System.out.printf("S0 Incenter: (%.4f, %.4f, %.4f)%n",
            incenter.x, incenter.y, incenter.z);
        System.out.printf("S0 Inradius: %.4f%n", inradius);

        // Incenter should be inside the unit cube
        assertTrue(incenter.x > 0 && incenter.x < 1, "Incenter x should be in (0,1)");
        assertTrue(incenter.y > 0 && incenter.y < 1, "Incenter y should be in (0,1)");
        assertTrue(incenter.z > 0 && incenter.z < 1, "Incenter z should be in (0,1)");

        // Inradius should be positive and less than 1
        assertTrue(inradius > 0, "Inradius should be positive");
        assertTrue(inradius < 0.5, "Inradius should be less than 0.5 (tetrahedron is small)");
    }

    @Test
    void testHigherResolution() {
        var generator = new ESVTVoxelGenerator();

        var lowRes = generator.generate(ESVTVoxelGenerator.Shape.SPHERE, 16);
        var highRes = generator.generate(ESVTVoxelGenerator.Shape.SPHERE, 32);

        System.out.printf("SPHERE: 16x=%d, 32x=%d%n", lowRes.size(), highRes.size());

        // Higher resolution should have more voxels (roughly 8x for 2x linear resolution)
        assertTrue(highRes.size() > lowRes.size() * 4,
            "32x resolution should have significantly more voxels than 16x");
    }
}
