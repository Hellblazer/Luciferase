package com.dyada.refinement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RefinementCriteria Tests")
class RefinementCriteriaTest {
    
    @Test
    @DisplayName("Should create criteria with builder pattern")
    void testBuilderPattern() {
        var criteria = RefinementCriteria.builder()
            .refinementThreshold(0.8)
            .coarseningThreshold(0.2)
            .gradientThreshold(1.5)
            .maxRefinementLevel(10)
            .minCellSize(0.01)
            .adaptivityFactor(1.2)
            .build();
        
        assertEquals(0.8, criteria.refinementThreshold());
        assertEquals(0.2, criteria.coarseningThreshold());
        assertTrue(criteria.gradientThreshold().isPresent());
        assertEquals(1.5, criteria.gradientThreshold().get());
        assertTrue(criteria.maxRefinementLevel().isPresent());
        assertEquals(10, criteria.maxRefinementLevel().get());
        assertTrue(criteria.minCellSize().isPresent());
        assertEquals(0.01, criteria.minCellSize().get());
        assertEquals(1.2, criteria.adaptivityFactor());
    }
    
    @Test
    @DisplayName("Should create criteria with default values")
    void testDefaultValues() {
        var criteria = RefinementCriteria.builder()
            .refinementThreshold(0.5)
            .coarseningThreshold(0.1)
            .build();
        
        assertEquals(0.5, criteria.refinementThreshold());
        assertEquals(0.1, criteria.coarseningThreshold());
        assertTrue(criteria.gradientThreshold().isEmpty());
        assertTrue(criteria.maxRefinementLevel().isEmpty());
        assertTrue(criteria.minCellSize().isEmpty());
        assertEquals(1.0, criteria.adaptivityFactor());
    }
    
    @Test
    @DisplayName("Should validate threshold ordering")
    void testThresholdValidation() {
        assertThrows(IllegalArgumentException.class, () -> 
            RefinementCriteria.builder()
                .refinementThreshold(0.2)  // Lower than coarsening
                .coarseningThreshold(0.8)  // Higher than refinement
                .build()
        );
    }
    
    @Test
    @DisplayName("Should validate positive values")
    void testPositiveValueValidation() {
        assertThrows(IllegalArgumentException.class, () -> 
            RefinementCriteria.builder()
                .refinementThreshold(-0.5)
                .coarseningThreshold(0.1)
                .build()
        );
        
        assertThrows(IllegalArgumentException.class, () -> 
            RefinementCriteria.builder()
                .refinementThreshold(0.5)
                .coarseningThreshold(-0.1)
                .build()
        );
        
        assertThrows(IllegalArgumentException.class, () -> 
            RefinementCriteria.builder()
                .refinementThreshold(0.5)
                .coarseningThreshold(0.1)
                .minCellSize(-0.01)
                .build()
        );
    }
    
    @Test
    @DisplayName("Should validate adaptivity factor")
    void testAdaptivityFactorValidation() {
        assertThrows(IllegalArgumentException.class, () -> 
            RefinementCriteria.builder()
                .refinementThreshold(0.5)
                .coarseningThreshold(0.1)
                .adaptivityFactor(0.0)
                .build()
        );
        
        assertThrows(IllegalArgumentException.class, () -> 
            RefinementCriteria.builder()
                .refinementThreshold(0.5)
                .coarseningThreshold(0.1)
                .adaptivityFactor(-1.0)
                .build()
        );
    }
    
    @Test
    @DisplayName("Should create error-based criteria")
    void testErrorBasedCriteria() {
        var criteria = RefinementCriteria.errorBased(0.005, 0.01, 0.001);
        
        assertEquals(0.01, criteria.refinementThreshold());
        assertEquals(0.001, criteria.coarseningThreshold());
        assertTrue(criteria.gradientThreshold().isEmpty());
    }
    
    @Test
    @DisplayName("Should create gradient-based criteria")
    void testGradientBasedCriteria() {
        var criteria = RefinementCriteria.gradientBased(10.0, 2.0, 1.0);
        
        assertEquals(2.0, criteria.refinementThreshold());
        assertEquals(1.0, criteria.coarseningThreshold());
        assertTrue(criteria.gradientThreshold().isPresent());
        assertEquals(10.0, criteria.gradientThreshold().get());
    }
    
    @Test
    @DisplayName("Should create adaptive criteria")
    void testAdaptiveCriteria() {
        var criteria = RefinementCriteria.adaptive(0.5, 0.1, 1.5);
        
        assertEquals(0.5, criteria.refinementThreshold());
        assertEquals(0.1, criteria.coarseningThreshold());
        assertEquals(1.5, criteria.adaptivityFactor());
    }
    
    @Test
    @DisplayName("Should handle boundary conditions in validation")
    void testBoundaryConditions() {
        // Test valid thresholds (refinement > coarsening)
        var criteria = RefinementCriteria.builder()
            .refinementThreshold(0.6)
            .coarseningThreshold(0.4)
            .build();
        
        assertEquals(0.6, criteria.refinementThreshold());
        assertEquals(0.4, criteria.coarseningThreshold());
        
        // Test equal thresholds (should throw exception)
        assertThrows(IllegalArgumentException.class, () -> 
            RefinementCriteria.builder()
                .refinementThreshold(0.5)
                .coarseningThreshold(0.5)
                .build()
        );
    }
    
    @Test
    @DisplayName("Should handle maximum refinement level validation")
    void testMaxRefinementLevelValidation() {
        assertThrows(IllegalArgumentException.class, () -> 
            RefinementCriteria.builder()
                .refinementThreshold(0.5)
                .coarseningThreshold(0.1)
                .maxRefinementLevel(-1)
                .build()
        );
        
        // Zero should be allowed
        var criteria = RefinementCriteria.builder()
            .refinementThreshold(0.5)
            .coarseningThreshold(0.1)
            .maxRefinementLevel(0)
            .build();
        
        assertTrue(criteria.maxRefinementLevel().isPresent());
        assertEquals(0, criteria.maxRefinementLevel().get());
    }
}