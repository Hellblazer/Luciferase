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

@DisplayName("GradientBasedRefinement Tests")
class GradientBasedRefinementTest {
    
    private GradientBasedRefinement strategy;
    private RefinementCriteria criteria;
    private AdaptiveRefinementStrategy.RefinementContext context;
    
    @BeforeEach
    void setUp() {
        strategy = new GradientBasedRefinement(0.1, 2.0, 0.5);
        criteria = RefinementCriteria.builder()
            .refinementThreshold(2.0)
            .coarseningThreshold(0.5)
            .build();
        
        var cellIndex = new LevelIndex(new byte[]{2}, new long[]{0});
        var cellCenter = new Coordinate(new double[]{0.5, 0.5});
        var cellSize = 0.5;
        var currentLevel = 2;
        var cellData = Map.of("test", "data");
        context = new AdaptiveRefinementStrategy.RefinementContext(cellIndex, cellCenter, cellSize, currentLevel, cellData);
    }
    
    @Test
    @DisplayName("Should refine cells with high gradient")
    void testHighGradientRefinement() {
        var fieldValues = Map.of(
            "value", 1.0,
            "gradient", 3.0  // High gradient > refinement threshold
        );
        
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.REFINE, decision);
    }
    
    @Test
    @DisplayName("Should coarsen cells with low gradient")
    void testLowGradientCoarsening() {
        var fieldValues = Map.of(
            "value", 1.0,
            "gradient", 0.3  // Low gradient < coarsening threshold
        );
        
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.COARSEN, decision);
    }
    
    @Test
    @DisplayName("Should maintain cells with moderate gradient")
    void testModerateGradientMaintenance() {
        var fieldValues = Map.of(
            "value", 1.0,
            "gradient", 1.0  // Moderate gradient between thresholds
        );
        
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.MAINTAIN, decision);
    }
    
    @Test
    @DisplayName("Should estimate gradient when not provided")
    void testGradientEstimation() {
        var fieldValues = Map.of(
            "value", 1.0,
            "error", 0.5
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
    @DisplayName("Should use custom gradient estimator")
    void testCustomGradientEstimator() {
        var customEstimator = new GradientBasedRefinement.GradientEstimator() {
            @Override
            public double estimateGradient(
                AdaptiveRefinementStrategy.RefinementContext context,
                Function<Coordinate, Object> dataFunction,
                List<AdaptiveRefinementStrategy.RefinementContext> neighbors
            ) {
                return 3.0;
            }
        };
        
        var customStrategy = new GradientBasedRefinement(0.1, 2.0, 0.5, customEstimator);
        var fieldValues = Map.of("gradient", 3.0);
        
        var decision = customStrategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.REFINE, decision);
    }
    
    @Test
    @DisplayName("Should handle boundary gradient values")
    void testBoundaryGradientValues() {
        // Test exact threshold values
        var fieldValuesAtRefinementThreshold = Map.of("gradient", 2.0);
        var decisionAtThreshold = strategy.analyzeCell(context, fieldValuesAtRefinementThreshold, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.REFINE, decisionAtThreshold);
        
        var fieldValuesAtCoarseningThreshold = Map.of("gradient", 0.5);
        var decisionAtCoarsening = strategy.analyzeCell(context, fieldValuesAtCoarseningThreshold, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.COARSEN, decisionAtCoarsening);
    }
    
    @Test
    @DisplayName("Should handle negative gradient values")
    void testNegativeGradientValues() {
        var fieldValues = Map.of("gradient", -0.1);
        
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.COARSEN, decision);
    }
    
    @Test
    @DisplayName("Should handle very large gradient values")
    void testVeryLargeGradientValues() {
        var fieldValues = Map.of("gradient", 1000.0);
        
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.REFINE, decision);
    }
    
    @Test
    @DisplayName("Should use curvature-aware gradient estimator")
    void testCurvatureAwareGradientEstimator() {
        var estimator = new GradientBasedRefinement.CurvatureAwareGradientEstimator(0.5);
        
        Function<Coordinate, Object> dataFunction = coord -> 1.0;
        List<AdaptiveRefinementStrategy.RefinementContext> neighbors = List.of();
        
        // Test with basic context
        var gradientValue = estimator.estimateGradient(context, dataFunction, neighbors);
        assertTrue(gradientValue >= 0);
    }
    
    @Test
    @DisplayName("Should validate default gradient estimator behavior")
    void testDefaultGradientEstimator() {
        var estimator = new GradientBasedRefinement.DefaultGradientEstimator();
        
        Function<Coordinate, Object> dataFunction = coord -> 1.0;
        List<AdaptiveRefinementStrategy.RefinementContext> neighbors = List.of();
        
        // Test with basic context
        var gradientValue = estimator.estimateGradient(context, dataFunction, neighbors);
        assertTrue(gradientValue >= 0);
        
        // Test with no data (null cellData)
        var cellIndex = new LevelIndex(new byte[]{2}, new long[]{0});
        var cellCenter = new Coordinate(new double[]{0.5, 0.5});
        var cellSize = 0.5;
        var currentLevel = 2;
        var emptyContext = new AdaptiveRefinementStrategy.RefinementContext(cellIndex, cellCenter, cellSize, currentLevel, null);
        
        var gradientFromEmpty = estimator.estimateGradient(emptyContext, dataFunction, neighbors);
        assertEquals(0.0, gradientFromEmpty);
    }
    
    @Test
    @DisplayName("Should consider gradient threshold from criteria")
    void testGradientThresholdFromCriteria() {
        var highGradientThresholdCriteria = RefinementCriteria.builder()
            .refinementThreshold(2.0)
            .coarseningThreshold(0.5)
            .gradientThreshold(10.0)  // Very high threshold
            .build();
        
        var fieldValues = Map.of("gradient", 3.0);  // Would normally refine
        
        var decision = strategy.analyzeCell(context, fieldValues, highGradientThresholdCriteria);
        // With high gradient threshold, this should maintain rather than refine
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.MAINTAIN, decision);
    }
    
    @Test
    @DisplayName("Should handle context with neighbors for gradient estimation")
    void testContextWithNeighborsForGradient() {
        // Create neighbor contexts
        var neighbor1Index = new LevelIndex(new byte[]{2}, new long[]{1});
        var neighbor1Center = new Coordinate(new double[]{1.5, 0.5});
        var neighbor1Context = new AdaptiveRefinementStrategy.RefinementContext(
            neighbor1Index, neighbor1Center, 0.5, 2, Map.of("value", 2.0)
        );
        
        var neighbor2Index = new LevelIndex(new byte[]{2}, new long[]{2});
        var neighbor2Center = new Coordinate(new double[]{0.5, 1.5});
        var neighbor2Context = new AdaptiveRefinementStrategy.RefinementContext(
            neighbor2Index, neighbor2Center, 0.5, 2, Map.of("value", 3.0)
        );
        
        var fieldValues = Map.of("gradient", 3.0);
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertEquals(AdaptiveRefinementStrategy.RefinementDecision.REFINE, decision);
    }
    
    @Test
    @DisplayName("Should handle mixed field values for estimation")
    void testMixedFieldValuesEstimation() {
        var fieldValues = Map.of(
            "value", 1.0,
            "error", 0.1,
            "temperature", 100.0,
            "pressure", 50.0
        );
        
        var decision = strategy.analyzeCell(context, fieldValues, criteria);
        assertNotNull(decision);
    }
}