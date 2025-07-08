/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision.physics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PhysicsMaterial.
 *
 * @author hal.hildebrand
 */
public class PhysicsMaterialTest {
    
    @Test
    void testMaterialCreation() {
        var material = new PhysicsMaterial(0.5f, 0.8f, 1000f);
        assertEquals(0.5f, material.friction());
        assertEquals(0.8f, material.restitution());
        assertEquals(1000f, material.density());
    }
    
    @Test
    void testMaterialValidation() {
        // Negative friction
        assertThrows(IllegalArgumentException.class, 
            () -> new PhysicsMaterial(-0.1f, 0.5f, 1000f));
        
        // Restitution > 1
        assertThrows(IllegalArgumentException.class, 
            () -> new PhysicsMaterial(0.5f, 1.1f, 1000f));
        
        // Negative restitution
        assertThrows(IllegalArgumentException.class, 
            () -> new PhysicsMaterial(0.5f, -0.1f, 1000f));
        
        // Zero or negative density
        assertThrows(IllegalArgumentException.class, 
            () -> new PhysicsMaterial(0.5f, 0.5f, 0f));
    }
    
    @Test
    void testMaterialCombination() {
        var rubber = PhysicsMaterial.RUBBER;
        var steel = PhysicsMaterial.STEEL;
        
        var combined = PhysicsMaterial.combine(rubber, steel);
        
        // Friction uses geometric mean
        float expectedFriction = (float) Math.sqrt(rubber.friction() * steel.friction());
        assertEquals(expectedFriction, combined.friction(), 0.001f);
        
        // Restitution uses minimum
        float expectedRestitution = Math.min(rubber.restitution(), steel.restitution());
        assertEquals(expectedRestitution, combined.restitution());
        
        // Density uses average
        float expectedDensity = (rubber.density() + steel.density()) * 0.5f;
        assertEquals(expectedDensity, combined.density());
    }
    
    @Test
    void testPresetMaterials() {
        // Test that all presets are valid
        assertNotNull(PhysicsMaterial.STEEL);
        assertNotNull(PhysicsMaterial.RUBBER);
        assertNotNull(PhysicsMaterial.WOOD);
        assertNotNull(PhysicsMaterial.ICE);
        assertNotNull(PhysicsMaterial.CONCRETE);
        assertNotNull(PhysicsMaterial.GLASS);
        assertNotNull(PhysicsMaterial.DEFAULT);
        
        // Ice should have very low friction
        assertTrue(PhysicsMaterial.ICE.friction() < 0.1f);
        
        // Rubber should have high restitution
        assertTrue(PhysicsMaterial.RUBBER.restitution() > 0.7f);
        
        // Steel should be dense
        assertTrue(PhysicsMaterial.STEEL.density() > 7000f);
    }
}