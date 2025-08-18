package com.dyada.visualization.data;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.LevelIndex;
import com.dyada.refinement.AdaptiveRefinementStrategy.RefinementDecision;
import com.dyada.refinement.AdaptiveRefinementStrategy.RefinementContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RefinementVisualizationDataTest {

    @Test
    @DisplayName("Basic constructor and field access")
    void testBasicConstructor() {
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var timestamp = Instant.now();
        var stats = RefinementStatistics.empty();
        
        var refinementData = new RefinementVisualizationData(
            "refinement-1",
            timestamp,
            2,
            bounds,
            Map.of("type", "adaptive"),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of(),
            stats
        );
        
        assertEquals("refinement-1", refinementData.id());
        assertEquals(timestamp, refinementData.timestamp());
        assertEquals(2, refinementData.dimensions());
        assertEquals(bounds, refinementData.bounds());
        assertEquals("adaptive", refinementData.metadata().get("type"));
        assertTrue(refinementData.refinementDecisions().isEmpty());
        assertTrue(refinementData.errorValues().isEmpty());
        assertTrue(refinementData.gradientVectors().isEmpty());
        assertTrue(refinementData.refinementRegions().isEmpty());
        assertEquals(stats, refinementData.statistics());
    }

    @Test
    @DisplayName("Constructor with refinement decisions")
    void testConstructorWithDecisions() {
        var context1 = createRefinementContext(0, 0, 0, 1.0);
        var context2 = createRefinementContext(1, 0, 0, 1.0);
        var decisions = Map.of(
            context1, RefinementDecision.REFINE,
            context2, RefinementDecision.COARSEN
        );
        
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var refinementData = new RefinementVisualizationData(
            "decisions-test",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            decisions,
            Map.of(),
            Map.of(),
            List.of(),
            RefinementStatistics.empty()
        );
        
        assertEquals(2, refinementData.refinementDecisions().size());
        assertEquals(RefinementDecision.REFINE, refinementData.refinementDecisions().get(context1));
        assertEquals(RefinementDecision.COARSEN, refinementData.refinementDecisions().get(context2));
    }

    @Test
    @DisplayName("Factory method from VisualizationData")
    void testFromVisualizationData() {
        var baseData = new VisualizationData(
            "base-refinement",
            Instant.now(),
            2,
            new Bounds(new double[]{0, 0}, new double[]{5, 5}),
            Map.of("source", "test")
        );
        
        var context = createRefinementContext(0, 0, 0, 1.0);
        var decisions = Map.of(context, RefinementDecision.REFINE);
        
        var refinementData = RefinementVisualizationData.from(baseData, decisions);
        
        assertEquals(baseData.id(), refinementData.id());
        assertEquals(baseData.timestamp(), refinementData.timestamp());
        assertEquals(baseData.dimensions(), refinementData.dimensions());
        assertEquals(baseData.bounds(), refinementData.bounds());
        assertEquals(baseData.metadata(), refinementData.metadata());
        assertEquals(decisions, refinementData.refinementDecisions());
        assertTrue(refinementData.errorValues().isEmpty());
    }

    @Test
    @DisplayName("Adding error data with withErrorData")
    void testWithErrorData() {
        var context1 = createRefinementContext(0, 0, 0, 1.0);
        var context2 = createRefinementContext(1, 0, 0, 1.0);
        var decisions = Map.of(
            context1, RefinementDecision.REFINE,
            context2, RefinementDecision.MAINTAIN
        );
        
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var refinementData = new RefinementVisualizationData(
            "error-test",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            decisions,
            Map.of(),
            Map.of(),
            List.of(),
            RefinementStatistics.empty()
        );
        
        var errors = Map.of(
            context1, 0.5,
            context2, 0.1
        );
        var refinementWithErrors = refinementData.withErrorData(errors);
        
        assertEquals(errors, refinementWithErrors.errorValues());
        assertEquals(0.5, refinementWithErrors.getMaxError(), 1e-10);
        assertEquals(0.1, refinementWithErrors.getMinError(), 1e-10);
        
        // Original should be unchanged
        assertTrue(refinementData.errorValues().isEmpty());
    }

    @Test
    @DisplayName("Adding gradient data with withGradientData")
    void testWithGradientData() {
        var context1 = createRefinementContext(0, 0, 0, 1.0);
        var context2 = createRefinementContext(1, 0, 0, 1.0);
        
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var refinementData = new RefinementVisualizationData(
            "gradient-test",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of(),
            RefinementStatistics.empty()
        );
        
        var gradients = Map.of(
            context1, new double[]{1.0, 0.5},
            context2, new double[]{-0.3, 0.8}
        );
        var refinementWithGradients = refinementData.withGradientData(gradients);
        
        assertEquals(gradients, refinementWithGradients.gradientVectors());
        assertArrayEquals(new double[]{1.0, 0.5}, refinementWithGradients.gradientVectors().get(context1));
        
        // Original should be unchanged
        assertTrue(refinementData.gradientVectors().isEmpty());
    }

    @Test
    @DisplayName("Adding refinement regions with withRefinementRegions")
    void testWithRefinementRegions() {
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var refinementData = new RefinementVisualizationData(
            "regions-test",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of(),
            RefinementStatistics.empty()
        );
        
        var context1 = createRefinementContext(0, 0, 0, 1.0);
        var context2 = createRefinementContext(1, 0, 0, 1.0);
        var regionBounds = new Bounds(new double[]{0, 0}, new double[]{2, 2});
        
        var regions = List.of(
            RefinementRegion.forRefinement("region1", Set.of(context1), regionBounds, 0.5),
            RefinementRegion.forCoarsening("region2", Set.of(context2), regionBounds, 0.1)
        );
        
        var refinementWithRegions = refinementData.withRefinementRegions(regions);
        
        assertEquals(regions, refinementWithRegions.refinementRegions());
        assertEquals(2, refinementWithRegions.refinementRegions().size());
        
        // Original should be unchanged
        assertTrue(refinementData.refinementRegions().isEmpty());
    }

    @Test
    @DisplayName("Adding statistics with withStatistics")
    void testWithStatistics() {
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var refinementData = new RefinementVisualizationData(
            "stats-test",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of(),
            RefinementStatistics.empty()
        );
        
        var stats = RefinementStatistics.from(100, 20, 5, 75, 0.25, 0.8, 0.1, 0.15, 3);
        var refinementWithStats = refinementData.withStatistics(stats);
        
        assertEquals(stats, refinementWithStats.statistics());
        assertEquals(100, refinementWithStats.statistics().totalCells());
        assertEquals(20, refinementWithStats.statistics().refinedCells());
        
        // Original should be unchanged
        assertEquals(0, refinementData.statistics().totalCells());
    }

    @Test
    @DisplayName("Getting refinement contexts")
    void testGetRefinementContexts() {
        var context1 = createRefinementContext(0, 0, 0, 1.0);
        var context2 = createRefinementContext(1, 0, 0, 1.0);
        var context3 = createRefinementContext(2, 0, 0, 1.0);
        
        var decisions = Map.of(
            context1, RefinementDecision.REFINE,
            context2, RefinementDecision.COARSEN,
            context3, RefinementDecision.REFINE
        );
        
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var refinementData = new RefinementVisualizationData(
            "contexts-test",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            decisions,
            Map.of(),
            Map.of(),
            List.of(),
            RefinementStatistics.empty()
        );
        
        var refinementContexts = refinementData.getRefinementContexts();
        assertEquals(2, refinementContexts.size());
        assertTrue(refinementContexts.contains(context1));
        assertTrue(refinementContexts.contains(context3));
        assertFalse(refinementContexts.contains(context2));
    }

    @Test
    @DisplayName("Getting coarsening contexts")
    void testGetCoarseningContexts() {
        var context1 = createRefinementContext(0, 0, 0, 1.0);
        var context2 = createRefinementContext(1, 0, 0, 1.0);
        var context3 = createRefinementContext(2, 0, 0, 1.0);
        
        var decisions = Map.of(
            context1, RefinementDecision.REFINE,
            context2, RefinementDecision.COARSEN,
            context3, RefinementDecision.COARSEN
        );
        
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var refinementData = new RefinementVisualizationData(
            "coarsening-test",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            decisions,
            Map.of(),
            Map.of(),
            List.of(),
            RefinementStatistics.empty()
        );
        
        var coarseningContexts = refinementData.getCoarseningContexts();
        assertEquals(2, coarseningContexts.size());
        assertTrue(coarseningContexts.contains(context2));
        assertTrue(coarseningContexts.contains(context3));
        assertFalse(coarseningContexts.contains(context1));
    }

    @Test
    @DisplayName("Error value statistics")
    void testErrorStatistics() {
        var context1 = createRefinementContext(0, 0, 0, 1.0);
        var context2 = createRefinementContext(1, 0, 0, 1.0);
        var context3 = createRefinementContext(2, 0, 0, 1.0);
        
        var errors = Map.of(
            context1, 0.8,
            context2, 0.3,
            context3, 0.1
        );
        
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var refinementData = new RefinementVisualizationData(
            "error-stats-test",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            Map.of(),
            errors,
            Map.of(),
            List.of(),
            RefinementStatistics.empty()
        );
        
        assertEquals(0.8, refinementData.getMaxError(), 1e-10);
        assertEquals(0.1, refinementData.getMinError(), 1e-10);
    }

    @Test
    @DisplayName("Empty refinement data handling")
    void testEmptyRefinementData() {
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var emptyRefinement = new RefinementVisualizationData(
            "empty-refinement",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of(),
            RefinementStatistics.empty()
        );
        
        assertTrue(emptyRefinement.getRefinementContexts().isEmpty());
        assertTrue(emptyRefinement.getCoarseningContexts().isEmpty());
        assertEquals(0.0, emptyRefinement.getMaxError(), 1e-10);
        assertEquals(0.0, emptyRefinement.getMinError(), 1e-10);
        assertEquals(0, emptyRefinement.statistics().totalCells());
    }

    @Test
    @DisplayName("Complex refinement scenario")
    void testComplexRefinementScenario() {
        var context1 = createRefinementContext(0, 0, 0, 2.0);
        var context2 = createRefinementContext(1, 0, 1, 1.0);
        var context3 = createRefinementContext(2, 1, 1, 1.0);
        var context4 = createRefinementContext(3, 1, 0, 0.5);
        
        var decisions = Map.of(
            context1, RefinementDecision.REFINE,
            context2, RefinementDecision.REFINE,
            context3, RefinementDecision.COARSEN,
            context4, RefinementDecision.MAINTAIN
        );
        
        var errors = Map.of(
            context1, 0.9,
            context2, 0.7,
            context3, 0.2,
            context4, 0.15
        );
        
        var gradients = Map.of(
            context1, new double[]{1.5, -0.8},
            context2, new double[]{0.9, 1.2},
            context3, new double[]{0.1, 0.05},
            context4, new double[]{0.2, -0.1}
        );
        
        var refinementRegion = RefinementRegion.forRefinement(
            "high-error-region",
            Set.of(context1, context2),
            new Bounds(new double[]{0, 0}, new double[]{2, 2}),
            0.8
        );
        
        var coarseningRegion = RefinementRegion.forCoarsening(
            "low-error-region",
            Set.of(context3),
            new Bounds(new double[]{2, 2}, new double[]{3, 3}),
            0.2
        );
        
        var stats = RefinementStatistics.from(4, 2, 1, 1, 0.5, 0.9, 0.15, 0.3, 2);
        
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var complexRefinement = new RefinementVisualizationData(
            "complex-scenario",
            Instant.now(),
            2,
            bounds,
            Map.of("scenario", "complex"),
            decisions,
            errors,
            gradients,
            List.of(refinementRegion, coarseningRegion),
            stats
        );
        
        // Test refinement contexts
        assertEquals(2, complexRefinement.getRefinementContexts().size());
        assertTrue(complexRefinement.getRefinementContexts().contains(context1));
        assertTrue(complexRefinement.getRefinementContexts().contains(context2));
        
        // Test coarsening contexts
        assertEquals(1, complexRefinement.getCoarseningContexts().size());
        assertTrue(complexRefinement.getCoarseningContexts().contains(context3));
        
        // Test error statistics
        assertEquals(0.9, complexRefinement.getMaxError(), 1e-10);
        assertEquals(0.15, complexRefinement.getMinError(), 1e-10);
        
        // Test regions
        assertEquals(2, complexRefinement.refinementRegions().size());
        
        // Test statistics
        assertEquals(4, complexRefinement.statistics().totalCells());
        assertEquals(2, complexRefinement.statistics().refinedCells());
        assertEquals(1, complexRefinement.statistics().coarsenedCells());
        assertEquals(1, complexRefinement.statistics().unchangedCells());
    }

    @Test
    @DisplayName("Immutable builder pattern")
    void testImmutableBuilderPattern() {
        var context = createRefinementContext(0, 0, 0, 1.0);
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var baseData = new VisualizationData("builder-test", Instant.now(), 2, bounds, Map.of());
        
        var step1 = RefinementVisualizationData.from(baseData, Map.of(context, RefinementDecision.REFINE));
        var step2 = step1.withErrorData(Map.of(context, 0.5));
        var step3 = step2.withGradientData(Map.of(context, new double[]{1.0, 0.0}));
        var step4 = step3.withStatistics(RefinementStatistics.from(1, 1, 0, 0, 0.5, 0.5, 0.5, 0.0, 1));
        
        // Each step should be independent
        assertTrue(step1.errorValues().isEmpty());
        assertTrue(step1.gradientVectors().isEmpty());
        assertEquals(0, step1.statistics().totalCells());
        
        assertFalse(step2.errorValues().isEmpty());
        assertTrue(step2.gradientVectors().isEmpty());
        assertEquals(0, step2.statistics().totalCells());
        
        assertFalse(step3.errorValues().isEmpty());
        assertFalse(step3.gradientVectors().isEmpty());
        assertEquals(0, step3.statistics().totalCells());
        
        assertFalse(step4.errorValues().isEmpty());
        assertFalse(step4.gradientVectors().isEmpty());
        assertEquals(1, step4.statistics().totalCells());
    }

    @Test
    @DisplayName("Conversion to VisualizationData")
    void testAsVisualizationData() {
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var timestamp = Instant.now();
        var metadata = Map.<String, Object>of("test", "refinement");
        
        var refinementData = new RefinementVisualizationData(
            "convert-test",
            timestamp,
            2,
            bounds,
            metadata,
            Map.of(),
            Map.of(),
            Map.of(),
            List.of(),
            RefinementStatistics.empty()
        );
        
        var baseData = refinementData.asVisualizationData();
        
        assertEquals(refinementData.id(), baseData.id());
        assertEquals(refinementData.timestamp(), baseData.timestamp());
        assertEquals(refinementData.dimensions(), baseData.dimensions());
        assertEquals(refinementData.bounds(), baseData.bounds());
        assertEquals(refinementData.metadata(), baseData.metadata());
    }

    // Helper method to create RefinementContext instances
    private RefinementContext createRefinementContext(int index, int x, int y, double size) {
        // Create proper 2D LevelIndex - calculate appropriate level for the given index
        byte level = (byte) (index == 0 ? 0 : Math.max(1, 32 - Integer.numberOfLeadingZeros(index)));
        // Use modular arithmetic to keep index within valid range for the level
        long validIndex = index % (1L << level);
        var levelIndex = new LevelIndex(new byte[]{level, level}, new long[]{validIndex, 0});
        var coordinate = new Coordinate(new double[]{x, y});
        return new RefinementContext(levelIndex, coordinate, size, 0, null);
    }
}