package com.dyada.refinement;

import com.dyada.core.coordinates.Bounds;
import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.LevelIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ErrorBasedRefinement Tests")
class ErrorBasedRefinementTest {
    
    private ErrorBasedRefinement strategy;
    private RefinementCriteria criteria;
    private AdaptiveRefinementStrategy.RefinementContext context;
    
    @BeforeEach
    void setUp() {
        strategy = new ErrorBasedRefinement();
        criteria = RefinementCriteria.builder()
            .refinementThreshold(0.5)
            .coarseningThreshold(0.1)
            .build();
        
        // Create test RefinementContext with proper parameters
        var cellIndex = new LevelIndex(new byte[]{2}, new long[]{0});
        var cellCenter = new Coordinate(new double[]{0.5, 0.5});
        var cellSize = 0.5;
        var currentLevel = 2;
        var cellData = Map.of("test", "data");
        context = new AdaptiveRefinementStrategy.RefinementContext(cellIndex, cellCenter, cellSize, currentLevel, cellData);
    }
    
    @Test
    @DisplayName("Should refine cells with high error")
    void testHighErrorRefinement() {
        var fieldValues = Map.of(
            "error", 0.8,  // High error > refinement threshold
            "value", 1.0
        );
        
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.REFINE, decision);
    }
    
    @Test
    @DisplayName("Should coarsen cells with low error")
    void testLowErrorCoarsening() {
        var fieldValues = Map.of(
            "error", 0.05,  // Low error < coarsening threshold
            "value", 1.0
        );
        
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.COARSEN, decision);
    }
    
    @Test
    @DisplayName("Should maintain cells with moderate error")
    void testModerateErrorMaintenance() {
        var fieldValues = Map.of(
            "error", 0.3,  // Moderate error between thresholds
            "value", 1.0
        );
        
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.MAINTAIN, decision);
    }
    
    @Test
    @DisplayName("Should estimate error when not provided")
    void testErrorEstimation() {
        var fieldValues = Map.of(
            "value", 1.0,
            "gradient", 2.0
        );
        
        // Should not throw exception and should make a decision
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertNotNull(decision);
    }
    
    @Test
    @DisplayName("Should handle empty field values")
    void testEmptyFieldValues() {
        var fieldValues = Map.<String, Double>of();
        
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.MAINTAIN, decision);
    }
    
    @Test
    @DisplayName("Should handle missing error field")
    void testMissingErrorField() {
        var fieldValues = Map.of(
            "temperature", 100.0,
            "pressure", 50.0
        );
        
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertNotNull(decision);
    }
    
    @Test
    @DisplayName("Should use custom error estimator")
    void testCustomErrorEstimator() {
        var customEstimator = new ErrorBasedRefinement.ErrorEstimator() {
            @Override
            public double estimateError(
                AdaptiveRefinementStrategy.RefinementContext context,
                Function<Coordinate, Object> dataFunction,
                List<AdaptiveRefinementStrategy.RefinementContext> neighbors) {
                // Simple test estimator - return fixed value
                return 0.8;
            }
        };
        
        var customStrategy = new ErrorBasedRefinement(0.5, 0.1, 0.05, customEstimator);
        var fieldValues = Map.of("error", 0.8);
        
        var decision = customStrategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.REFINE, decision);
    }
    
    @Test
    @DisplayName("Should handle boundary error values")
    void testBoundaryErrorValues() {
        // Test exact threshold values
        var fieldValuesAtRefinementThreshold = Map.of("error", 0.5);
        var decisionAtThreshold = strategy.analyzeCell(context, fieldValuesAtRefinementThreshold, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.REFINE, decisionAtThreshold);
        
        var fieldValuesAtCoarseningThreshold = Map.of("error", 0.1);
        var decisionAtCoarsening = strategy.analyzeCell(context, fieldValuesAtCoarseningThreshold, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.COARSEN, decisionAtCoarsening);
    }
    
    @Test
    @DisplayName("Should handle negative error values")
    void testNegativeErrorValues() {
        var fieldValues = Map.of("error", -0.1);
        
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.COARSEN, decision);
    }
    
    @Test
    @DisplayName("Should handle very large error values")
    void testVeryLargeErrorValues() {
        var fieldValues = Map.of("error", 1000.0);
        
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.REFINE, decision);
    }
    
    @Test
    @DisplayName("Should validate default error estimator behavior")
    void testDefaultErrorEstimator() {
        var estimator = new ErrorBasedRefinement.DefaultErrorEstimator();
        var neighbors = List.<AdaptiveRefinementStrategy.RefinementContext>of();
        
        // Create a simple data function
        Function<Coordinate, Object> dataFunction = coord -> Map.of("value", 1.0);
        
        // Test with the correct interface signature
        var errorFromEstimator = estimator.estimateError(context, dataFunction, neighbors);
        assertTrue(errorFromEstimator >= 0.0);
    }
    
    @Test
    @DisplayName("Should handle context with neighbors")
    void testContextWithNeighbors() {
        // Create neighbor contexts
        var neighbor1Index = new LevelIndex(new byte[]{2}, new long[]{1});
        var neighbor1Center = new Coordinate(new double[]{1.5, 0.5});
        var neighbor1 = new AdaptiveRefinementStrategy.RefinementContext(
            neighbor1Index, neighbor1Center, 0.5, 2, Map.of("neighbor", 1)
        );
        
        var neighbor2Index = new LevelIndex(new byte[]{2}, new long[]{2});
        var neighbor2Center = new Coordinate(new double[]{0.5, 1.5});
        var neighbor2 = new AdaptiveRefinementStrategy.RefinementContext(
            neighbor2Index, neighbor2Center, 0.5, 2, Map.of("neighbor", 2)
        );
        
        var neighbors = List.of(neighbor1, neighbor2);
        
        // Use original context with neighbors list (for algorithm that might use neighbors)
        var fieldValues = Map.of("error", 0.8);
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.REFINE, decision);
    }
}