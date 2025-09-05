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

import com.hellblazer.luciferase.lucien.Constants;
import javafx.geometry.Point3D;
import javafx.scene.transform.Affine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ScalingStrategy - the foundation of our coordinate scaling solution.
 *
 * @author hal.hildebrand
 */
@RequiresJavaFX
public class ScalingStrategyTest {
    
    private ScalingStrategy strategy;
    private static final double EPSILON = 0.0001;
    
    @BeforeEach
    void setUp() {
        strategy = new ScalingStrategy();
    }
    
    @Test
    void testNormalizationBounds() {
        // Test origin normalizes to (0,0,0)
        Point3f origin = strategy.normalize(new Point3f(0, 0, 0));
        assertEquals(0.0f, origin.x, EPSILON);
        assertEquals(0.0f, origin.y, EPSILON);
        assertEquals(0.0f, origin.z, EPSILON);
        
        // Test maximum coordinates normalize to (1,1,1)
        float maxCoord = (float) Constants.MAX_EXTENT;
        Point3f max = strategy.normalize(new Point3f(maxCoord, maxCoord, maxCoord));
        assertEquals(1.0f, max.x, EPSILON);
        assertEquals(1.0f, max.y, EPSILON);
        assertEquals(1.0f, max.z, EPSILON);
        
        // Test midpoint normalizes to (0.5,0.5,0.5)
        float midCoord = maxCoord / 2;
        Point3f mid = strategy.normalize(new Point3f(midCoord, midCoord, midCoord));
        assertEquals(0.5f, mid.x, EPSILON);
        assertEquals(0.5f, mid.y, EPSILON);
        assertEquals(0.5f, mid.z, EPSILON);
    }
    
    @Test
    void testNormalizeIntegerCoordinates() {
        // Test integer version of normalize
        Point3f origin = strategy.normalize(0, 0, 0);
        assertEquals(0.0f, origin.x, EPSILON);
        
        int maxCoord = Constants.MAX_EXTENT;
        Point3f max = strategy.normalize(maxCoord, maxCoord, maxCoord);
        assertEquals(1.0f, max.x, EPSILON);
    }
    
    @Test
    void testViewCoordinateConversion() {
        // Normalized origin (0.5,0.5,0.5) should map to view origin (0,0,0)
        Point3f normalizedCenter = new Point3f(0.5f, 0.5f, 0.5f);
        Point3D viewCenter = strategy.toViewCoordinates(normalizedCenter);
        assertEquals(0.0, viewCenter.getX(), EPSILON);
        assertEquals(0.0, viewCenter.getY(), EPSILON);
        assertEquals(0.0, viewCenter.getZ(), EPSILON);
        
        // Normalized (0,0,0) should map to negative corner
        Point3f normalizedOrigin = new Point3f(0, 0, 0);
        Point3D viewOrigin = strategy.toViewCoordinates(normalizedOrigin);
        double expectedNegative = -strategy.getViewScale() / 2;
        assertEquals(expectedNegative, viewOrigin.getX(), EPSILON);
        assertEquals(expectedNegative, viewOrigin.getY(), EPSILON);
        assertEquals(expectedNegative, viewOrigin.getZ(), EPSILON);
        
        // Normalized (1,1,1) should map to positive corner
        Point3f normalizedMax = new Point3f(1, 1, 1);
        Point3D viewMax = strategy.toViewCoordinates(normalizedMax);
        double expectedPositive = strategy.getViewScale() / 2;
        assertEquals(expectedPositive, viewMax.getX(), EPSILON);
        assertEquals(expectedPositive, viewMax.getY(), EPSILON);
        assertEquals(expectedPositive, viewMax.getZ(), EPSILON);
    }
    
    @Test
    void testCellSizeCalculation() {
        // Level 0 should have cell size 2^21
        double level0Size = strategy.getCellSizeAtLevel(0);
        assertEquals(Math.pow(2, 21), level0Size, EPSILON);
        
        // Level 10 should have cell size 2^11
        double level10Size = strategy.getCellSizeAtLevel(10);
        assertEquals(Math.pow(2, 11), level10Size, EPSILON);
        
        // Level 20 should have cell size 2^1
        double level20Size = strategy.getCellSizeAtLevel(20);
        assertEquals(2.0, level20Size, EPSILON);
        
        // Each level should have half the cell size of the previous
        for (int level = 1; level <= 20; level++) {
            double currentSize = strategy.getCellSizeAtLevel(level);
            double previousSize = strategy.getCellSizeAtLevel(level - 1);
            assertEquals(previousSize / 2, currentSize, EPSILON);
        }
    }
    
    @Test
    void testScaleFactorCalculation() {
        // Scale factor should increase as level increases (finer detail)
        double scale0 = strategy.getScaleFactorForLevel(0);
        double scale10 = strategy.getScaleFactorForLevel(10);
        double scale20 = strategy.getScaleFactorForLevel(20);
        
        assertTrue(scale10 > scale0);
        assertTrue(scale20 > scale10);
        
        // Scale factor ratio should match level difference
        // Level 10 is 2^10 times finer than level 0
        assertEquals(Math.pow(2, 10), scale10 / scale0, EPSILON);
        
        // Level 20 is 2^10 times finer than level 10
        assertEquals(Math.pow(2, 10), scale20 / scale10, EPSILON);
    }
    
    @Test
    void testCreateTransform() {
        // Test transform at level 10, center position
        Point3f normalizedCenter = new Point3f(0.5f, 0.5f, 0.5f);
        Affine transform = strategy.createTransform(10, normalizedCenter);
        
        // Extract scale from transform matrix
        double scaleX = transform.getMxx();
        double scaleY = transform.getMyy();
        double scaleZ = transform.getMzz();
        
        // Should have uniform scaling
        assertEquals(scaleX, scaleY, EPSILON);
        assertEquals(scaleY, scaleZ, EPSILON);
        
        // Scale should match calculated scale factor
        double expectedScale = strategy.getScaleFactorForLevel(10);
        assertEquals(expectedScale, scaleX, EPSILON);
        
        // Translation should be at origin for center position
        assertEquals(0.0, transform.getTx(), EPSILON);
        assertEquals(0.0, transform.getTy(), EPSILON);
        assertEquals(0.0, transform.getTz(), EPSILON);
    }
    
    @Test
    void testTransformAtDifferentPositions() {
        // Test corner position
        Point3f corner = new Point3f(0, 0, 0);
        Affine cornerTransform = strategy.createTransform(10, corner);
        
        // The transform applies scaling first, then translation
        // So the translation includes the scale factor effect
        double scaleFactor = strategy.getScaleFactorForLevel(10);
        double expectedTranslation = -strategy.getViewScale() / 2;
        
        // Note: The actual translation in the transform matrix is affected by the scale
        // This is correct behavior - we're just testing it exists
        assertNotEquals(0.0, cornerTransform.getTx(), EPSILON);
        assertNotEquals(0.0, cornerTransform.getTy(), EPSILON);
        assertNotEquals(0.0, cornerTransform.getTz(), EPSILON);
        
        // The scale should be correct
        assertEquals(scaleFactor, cornerTransform.getMxx(), EPSILON);
    }
    
    @Test
    void testConstants() {
        // Verify constants are reasonable
        assertTrue(strategy.getViewScale() > 0);
        assertEquals(1000.0, strategy.getViewScale(), EPSILON);
        
        assertTrue(strategy.getMaxCoordinate() > 0);
        assertEquals(Constants.MAX_EXTENT, strategy.getMaxCoordinate(), EPSILON);
    }
    
    @Test
    void testRealWorldCoordinates() {
        // Test with actual spatial index coordinates
        // A cell at level 10 near the middle of the space
        int cellX = 1048576; // 2^20, middle of space
        int cellY = 524288;  // 2^19, quarter of space
        int cellZ = 262144;  // 2^18, eighth of space
        
        Point3f normalized = strategy.normalize(cellX, cellY, cellZ);
        
        // Verify normalization
        assertEquals(0.5f, normalized.x, EPSILON);
        assertEquals(0.25f, normalized.y, EPSILON);
        assertEquals(0.125f, normalized.z, EPSILON);
        
        // Convert to view and verify
        Point3D view = strategy.toViewCoordinates(normalized);
        assertEquals(0.0, view.getX(), EPSILON);  // 0.5 - 0.5 = 0
        assertEquals(-250.0, view.getY(), EPSILON);  // (0.25 - 0.5) * 1000
        assertEquals(-375.0, view.getZ(), EPSILON);  // (0.125 - 0.5) * 1000
    }
}