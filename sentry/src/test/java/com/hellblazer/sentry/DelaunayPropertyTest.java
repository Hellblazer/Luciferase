/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the 3D Incremental Voronoi system
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
package com.hellblazer.sentry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import javax.vecmath.Point3f;
import java.util.Random;
import java.util.List;
import java.util.ArrayList;

/**
 * Test class to verify the Delaunay property is maintained with different
 * predicate implementations.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class DelaunayPropertyTest {
    
    private Random random;
    
    @BeforeEach
    public void setUp() {
        // Reset the factory to ensure clean state between tests
        GeometricPredicatesFactory.reset();
        random = new Random(42); // Fixed seed for reproducibility
        
        // Reset any static state to prevent cross-test contamination
        GeometricPredicatesFactory.reset();
        SIMDSupport.setEnabled(true); // Reset to default
        
        // Force garbage collection to clear any lingering state
        System.gc();
    }
    
    @AfterEach
    public void tearDown() {
        // Reset to clean state for next test
        GeometricPredicatesFactory.reset();
    }
    
    @Test
    @DisplayName("Test Delaunay property with exact predicates")
    public void testExactPredicatesDelaunayProperty() {
        // Create configuration for exact predicates
        SentryConfiguration config = new SentryConfiguration.Builder()
            .withPredicateMode(GeometricPredicatesFactory.PredicateMode.EXACT)
            .withValidation(true)
            .build();
        
        // Create grid and predicates with specific configuration
        GeometricPredicates predicates = GeometricPredicatesFactory.create(config);
        MutableGrid grid = new MutableGrid(Grid.getFourCorners(), config);
        ValidationManager validator = grid.getValidationManager();
        
        // Ensure the singleton is set before adding points
        // This is needed because Vertex methods use the static singleton
        GeometricPredicatesFactory.setInstance(predicates);
        
        // Add challenging point set - nearly coplanar points
        addNearlyCoplanarPoints(grid, 100);
        
        // Create validation report
        ValidationManager.ValidationReport report = validator.createReport();
        System.out.println("Exact predicates report:\n" + report);
        
        // Verify Delaunay property
        assertEquals(0, report.delaunayViolations, 
            "Delaunay violations detected with exact predicates");
        assertTrue(report.isValid(), "Grid should be valid with exact predicates");
    }
    
    @Test
    @DisplayName("Test Delaunay property with adaptive predicates")
    public void testAdaptivePredicatesDelaunayProperty() {
        // Create configuration for adaptive predicates
        SentryConfiguration config = new SentryConfiguration.Builder()
            .withPredicateMode(GeometricPredicatesFactory.PredicateMode.ADAPTIVE)
            .withValidation(true)
            .build();
        
        // Create predicates and ensure singleton is set
        GeometricPredicates predicates = GeometricPredicatesFactory.create(config);
        GeometricPredicatesFactory.setInstance(predicates);
        
        MutableGrid grid = new MutableGrid(Grid.getFourCorners(), config);
        ValidationManager validator = grid.getValidationManager();
        
        // Add challenging point set
        addNearlyCoplanarPoints(grid, 100);
        
        // Create validation report
        ValidationManager.ValidationReport report = validator.createReport();
        System.out.println("Adaptive predicates report:\n" + report);
        
        // Verify Delaunay property
        assertEquals(0, report.delaunayViolations, 
            "Delaunay violations detected with adaptive predicates");
        assertTrue(report.isValid(), "Grid should be valid with adaptive predicates");
    }
    
    @Test
    @DisplayName("Test rebuild maintains vertex references")
    public void testRebuildMaintainsReferences() {
        // Create configuration for exact predicates with validation
        SentryConfiguration config = new SentryConfiguration.Builder()
            .withPredicateMode(GeometricPredicatesFactory.PredicateMode.EXACT)
            .withValidation(true)
            .build();
        
        MutableGrid grid = new MutableGrid(Grid.getFourCorners(), config);
        ValidationManager validator = grid.getValidationManager();
        
        // Add random points
        List<Vertex> vertices = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Point3f p = randomPoint();
            Vertex v = grid.track(p, random);
            if (v != null) {
                vertices.add(v);
            }
        }
        
        // Verify initial state
        ValidationManager.ValidationReport beforeReport = validator.createReport();
        assertEquals(0, beforeReport.missingVertexReferences, 
            "Should have no missing references before rebuild");
        
        // Rebuild the grid
        grid.rebuild(random);
        
        // Verify after rebuild
        ValidationManager.ValidationReport afterReport = validator.createReport();
        System.out.println("After rebuild report:\n" + afterReport);
        
        assertEquals(0, afterReport.missingVertexReferences, 
            "Should have no missing references after rebuild");
        assertEquals(0, afterReport.staleVertexReferences,
            "Should have no stale references after rebuild");
        assertEquals(0, afterReport.inconsistentVertexReferences,
            "Should have no inconsistent references after rebuild");
        
        // Verify all vertices have valid adjacent references
        for (Vertex v : grid) {
            assertNotNull(v.getAdjacent(), "Vertex should have adjacent tetrahedron after rebuild");
            assertFalse(v.getAdjacent().isDeleted(), "Adjacent tetrahedron should not be deleted");
            assertTrue(v.getAdjacent().includes(v), "Adjacent tetrahedron should contain vertex");
        }
    }
    
    @Test
    @DisplayName("Test points on a sphere")
    public void testPointsOnSphere() {
        // Create configuration for adaptive predicates
        SentryConfiguration config = new SentryConfiguration.Builder()
            .withPredicateMode(GeometricPredicatesFactory.PredicateMode.ADAPTIVE)
            .withValidation(true)
            .build();
        
        // Create predicates and ensure singleton is set
        GeometricPredicates predicates = GeometricPredicatesFactory.create(config);
        GeometricPredicatesFactory.setInstance(predicates);
        
        MutableGrid grid = new MutableGrid(Grid.getFourCorners(), config);
        ValidationManager validator = grid.getValidationManager();
        
        // Add points on a sphere - challenging for Delaunay
        addPointsOnSphere(grid, 50, 100.0f);
        
        ValidationManager.ValidationReport report = validator.createReport();
        System.out.println("Points on sphere report:\n" + report);
        
        assertEquals(0, report.delaunayViolations, 
            "Delaunay violations detected with points on sphere");
        assertTrue(report.isValid(), "Grid should be valid with points on sphere");
    }
    
    @Test
    @DisplayName("Test grid-aligned points")
    public void testGridAlignedPoints() {
        // Create configuration for exact predicates
        SentryConfiguration config = new SentryConfiguration.Builder()
            .withPredicateMode(GeometricPredicatesFactory.PredicateMode.EXACT)
            .withValidation(true)
            .build();
        
        // Create predicates and ensure singleton is set
        GeometricPredicates predicates = GeometricPredicatesFactory.create(config);
        GeometricPredicatesFactory.setInstance(predicates);
        
        MutableGrid grid = new MutableGrid(Grid.getFourCorners(), config);
        ValidationManager validator = grid.getValidationManager();
        
        // Add grid-aligned points
        addGridAlignedPoints(grid, 5, 10.0f);
        
        ValidationManager.ValidationReport report = validator.createReport();
        System.out.println("Grid-aligned points report:\n" + report);
        
        assertEquals(0, report.delaunayViolations, 
            "Delaunay violations detected with grid-aligned points");
        assertTrue(report.isValid(), "Grid should be valid with grid-aligned points");
    }
    
    @Test
    @DisplayName("Test validation performance")
    public void testValidationPerformance() {
        // Configuration with validation enabled
        SentryConfiguration configWithValidation = new SentryConfiguration.Builder()
            .withPredicateMode(GeometricPredicatesFactory.PredicateMode.SCALAR)
            .withValidation(true)
            .build();
        
        MutableGrid grid = new MutableGrid(Grid.getFourCorners(), configWithValidation);
        
        // Measure time with validation enabled
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            grid.track(randomPoint(), random);
        }
        long withValidation = System.currentTimeMillis() - startTime;
        
        // Configuration without validation
        SentryConfiguration configNoValidation = new SentryConfiguration.Builder()
            .withPredicateMode(GeometricPredicatesFactory.PredicateMode.SCALAR)
            .withValidation(false)
            .build();
        
        MutableGrid grid2 = new MutableGrid(Grid.getFourCorners(), configNoValidation);
        
        startTime = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            grid2.track(randomPoint(), random);
        }
        long withoutValidation = System.currentTimeMillis() - startTime;
        
        System.out.println("Time with validation: " + withValidation + "ms");
        System.out.println("Time without validation: " + withoutValidation + "ms");
        System.out.println("Validation overhead: " + 
            (withValidation - withoutValidation) + "ms");
    }
    
    // Helper methods
    
    private void addNearlyCoplanarPoints(MutableGrid grid, int count) {
        // Add points that are nearly coplanar - challenging for predicates
        float z = 50.0f;
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * 100.0f;
            float y = random.nextFloat() * 100.0f;
            // Add small perturbation to z
            float zPerturbation = (random.nextFloat() - 0.5f) * 0.001f;
            Point3f p = new Point3f(x, y, z + zPerturbation);
            grid.track(p, random);
        }
    }
    
    private void addPointsOnSphere(MutableGrid grid, int count, float radius) {
        // Generate points on the surface of a sphere
        for (int i = 0; i < count; i++) {
            // Use spherical coordinates
            float theta = random.nextFloat() * (float)(2 * Math.PI);
            float phi = (float)Math.acos(2 * random.nextFloat() - 1);
            
            float x = radius * (float)(Math.sin(phi) * Math.cos(theta)) + 100.0f;
            float y = radius * (float)(Math.sin(phi) * Math.sin(theta)) + 100.0f;
            float z = radius * (float)Math.cos(phi) + 100.0f;
            
            Point3f p = new Point3f(x, y, z);
            grid.track(p, random);
        }
    }
    
    private void addGridAlignedPoints(MutableGrid grid, int gridSize, float spacing) {
        // Generate points on a regular grid
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                for (int k = 0; k < gridSize; k++) {
                    float x = i * spacing + 10.0f;
                    float y = j * spacing + 10.0f;
                    float z = k * spacing + 10.0f;
                    Point3f p = new Point3f(x, y, z);
                    grid.track(p, random);
                }
            }
        }
    }
    
    private Point3f randomPoint() {
        return new Point3f(
            random.nextFloat() * 100.0f + 10.0f,
            random.nextFloat() * 100.0f + 10.0f,
            random.nextFloat() * 100.0f + 10.0f
        );
    }
}