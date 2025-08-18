package com.dyada.integration;

import com.dyada.TestBase;
import com.dyada.core.coordinates.Bounds;
import com.dyada.core.coordinates.Coordinate;
import com.dyada.discretization.SpatialDiscretizer;
import com.dyada.refinement.*;
import com.dyada.visualization.data.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DyAda Integration Tests")
class DyAdaIntegrationTest extends TestBase {
    
    private Bounds testBounds;
    private AdaptiveMesh mesh;
    
    @BeforeEach
    void setupIntegrationTest() {
        testBounds = new Bounds(
            new double[]{0.0, 0.0},
            new double[]{100.0, 100.0}
        );
        mesh = new AdaptiveMesh(testBounds, 8, 15, 0.05);
    }
    
    @Test
    @DisplayName("Complete adaptive mesh refinement workflow")
    void testCompleteAdaptiveMeshWorkflow() {
        // Step 1: Insert entities with varying density
        insertEntitiesWithDensityGradient();
        
        // Step 2: Apply error-based refinement
        var errorStrategy = new ErrorBasedRefinement();
        var criteria = RefinementCriteria.simple(0.1, 0.01, 5);
        
        mesh.refine(errorStrategy, criteria);
        
        // Step 3: Verify refinement occurred
        assertTrue(mesh.getActiveNodes().size() > 1);
        assertEquals(100, mesh.getEntityCount());
        
        // Step 4: Update some entity positions
        updateEntityPositions();
        
        // Step 5: Apply gradient-based refinement
        var gradientStrategy = new GradientBasedRefinement(1.0, 2.0, 0.5);
        mesh.refine(gradientStrategy, criteria);
        
        // Step 6: Create visualization data
        var vizData = createMeshVisualization();
        assertNotNull(vizData);
        assertTrue(vizData.vertexCount() > 0);
        assertTrue(vizData.cellCount() > 0);
        
        // Step 7: Test coarsening
        removeEntitiesFromLowDensityRegions();
        var coarsenCriteria = RefinementCriteria.simple(1.0, 0.1, 2);
        mesh.coarsen(errorStrategy, coarsenCriteria);
        
        // Verify final state
        var finalStats = mesh.computeStatistics();
        assertTrue(finalStats.totalCells() < 100);
        assertTrue(finalStats.activeCells() > 0);
    }
    
    @Test
    @DisplayName("Spatial discretization and query workflow")
    void testSpatialDiscretizationWorkflow() {
        // Create discretizer
        var coordinate1 = new Coordinate(new double[]{0.0, 0.0});
        var coordinate2 = new Coordinate(new double[]{100.0, 100.0});
        var interval = new com.dyada.core.coordinates.CoordinateInterval(coordinate1, coordinate2);
        var descriptor = com.dyada.core.descriptors.RefinementDescriptor.create(2);
        
        var discretizer = new SpatialDiscretizer(descriptor, interval);
        
        // Test coordinate to cell mapping
        var testCoordinate = coordinate2D(25.5, 75.3);
        var cellIndex = discretizer.discretize(testCoordinate);
        assertNotNull(cellIndex);
        
        // Test cell to coordinate mapping
        var cellCoordinate = discretizer.undiscretize(cellIndex);
        assertNotNull(cellCoordinate);
        
        // Test range queries
        var queryCenter = coordinate2D(50.0, 50.0);
        var radius = 20.0;
        var cellsInRange = discretizer.rangeQuery(queryCenter, radius);
        
        assertNotNull(cellsInRange);
        assertFalse(cellsInRange.isEmpty());
    }
    
    @Test
    @DisplayName("Visualization pipeline workflow")
    void testVisualizationPipeline() {
        // Setup mesh with entities
        insertEntitiesWithDensityGradient();
        
        // Apply refinement
        var strategy = new ErrorBasedRefinement();
        var criteria = RefinementCriteria.simple(0.05, 0.005, 4);
        mesh.refine(strategy, criteria);
        
        // Create mesh visualization
        var meshViz = createMeshVisualization();
        assertNotNull(meshViz);
        
        // Create grid visualization
        var gridViz = createGridVisualization();
        assertNotNull(gridViz);
        
        // Create refinement visualization
        var refinementViz = createRefinementVisualization(strategy, criteria);
        assertNotNull(refinementViz);
        
        // Test rendering options
        testRenderingOptions(meshViz);
    }
    
    @Test
    @DisplayName("Performance and scalability test")
    void testPerformanceAndScalability() {
        var entityCount = 1000;
        var startTime = System.nanoTime();
        
        // Insert large number of entities
        for (int i = 0; i < entityCount; i++) {
            var position = generateClusteredPosition(i, entityCount);
            mesh.insertEntity("entity_" + i, position);
        }
        
        var insertTime = System.nanoTime() - startTime;
        logPerformance("Insert " + entityCount + " entities", insertTime);
        
        // Measure refinement performance
        startTime = System.nanoTime();
        var strategy = new ErrorBasedRefinement();
        var criteria = RefinementCriteria.simple(0.1, 0.01, 6);
        mesh.refine(strategy, criteria);
        
        var refinementTime = System.nanoTime() - startTime;
        logPerformance("Refine mesh with " + entityCount + " entities", refinementTime);
        
        // Measure query performance
        startTime = System.nanoTime();
        var queryResults = mesh.queryEntitiesInRange(coordinate2D(50.0, 50.0), 10.0);
        var queryTime = System.nanoTime() - startTime;
        
        logPerformance("Range query", queryTime);
        assertNotNull(queryResults);
        
        // Verify reasonable performance (these are rough benchmarks)
        assertTrue(insertTime < 1_000_000_000L, "Insert time too slow: " + insertTime + " ns");
        assertTrue(refinementTime < 2_000_000_000L, "Refinement time too slow: " + refinementTime + " ns");
        assertTrue(queryTime < 10_000_000L, "Query time too slow: " + queryTime + " ns");
    }
    
    @Test
    @DisplayName("Error handling and recovery")
    void testErrorHandlingAndRecovery() {
        // Test invalid entity operations
        assertThrows(IllegalArgumentException.class, 
            () -> mesh.insertEntity(null, coordinate2D(5.0, 5.0)));
        
        assertThrows(IllegalArgumentException.class, 
            () -> mesh.insertEntity("test", null));
        
        assertThrows(IllegalArgumentException.class, 
            () -> mesh.insertEntity("test", coordinate2D(200.0, 200.0))); // Outside bounds
        
        // Test that mesh remains functional after errors
        mesh.insertEntity("valid_entity", coordinate2D(50.0, 50.0));
        assertTrue(mesh.containsEntity("valid_entity"));
        
        // Test recovery from invalid refinement parameters
        var strategy = new ErrorBasedRefinement();
        
        // This should not crash the system
        try {
            var invalidCriteria = RefinementCriteria.simple(-1.0, -1.0, -1);
            mesh.refine(strategy, invalidCriteria);
        } catch (Exception e) {
            // Expected - but mesh should still be functional
            log.debug("Expected error: {}", e.getMessage());
        }
        
        // Verify mesh is still functional
        assertTrue(mesh.containsEntity("valid_entity"));
        mesh.insertEntity("recovery_test", coordinate2D(75.0, 75.0));
        assertTrue(mesh.containsEntity("recovery_test"));
    }
    
    // Helper methods
    
    private void insertEntitiesWithDensityGradient() {
        // High density in center
        for (int i = 0; i < 50; i++) {
            var position = coordinate2D(
                45.0 + random.nextGaussian() * 5.0,
                45.0 + random.nextGaussian() * 5.0
            );
            // Clamp to bounds
            position = new Coordinate(new double[]{
                Math.max(0.0, Math.min(100.0, position.get(0))),
                Math.max(0.0, Math.min(100.0, position.get(1)))
            });
            mesh.insertEntity("center_" + i, position);
        }
        
        // Lower density elsewhere
        for (int i = 0; i < 50; i++) {
            var position = randomCoordinate2D(0.0, 100.0);
            mesh.insertEntity("sparse_" + i, position);
        }
    }
    
    private void updateEntityPositions() {
        // Move some center entities outward
        for (int i = 0; i < 10; i++) {
            var entityId = "center_" + i;
            if (mesh.containsEntity(entityId)) {
                var newPosition = randomCoordinate2D(60.0, 90.0);
                mesh.updateEntityPosition(entityId, newPosition);
            }
        }
    }
    
    private void removeEntitiesFromLowDensityRegions() {
        // Remove some sparse entities
        for (int i = 30; i < 50; i++) {
            var entityId = "sparse_" + i;
            mesh.removeEntity(entityId);
        }
    }
    
    private MeshVisualizationData createMeshVisualization() {
        var bounds = new com.dyada.visualization.data.Bounds(new double[]{0.0, 0.0}, new double[]{100.0, 100.0});
        var baseData = new VisualizationData("mesh-viz", null, 2, bounds, Map.of());
        
        // Create sample vertices and cells
        var vertices = java.util.List.of(
            MeshVertex.create2D(0, 0.0, 0.0),
            MeshVertex.create2D(1, 50.0, 0.0),
            MeshVertex.create2D(2, 50.0, 50.0),
            MeshVertex.create2D(3, 0.0, 50.0)
        );
        
        var cells = java.util.List.of(
            MeshCell.triangle(0, 0, 1, 2, 1),
            MeshCell.triangle(1, 0, 2, 3, 1)
        );
        
        return MeshVisualizationData.from(baseData, vertices, cells);
    }
    
    private GridVisualizationData createGridVisualization() {
        var bounds = new com.dyada.visualization.data.Bounds(new double[]{0.0, 0.0}, new double[]{100.0, 100.0});
        var baseData = new VisualizationData("grid-viz", null, 2, bounds, Map.of());
        
        var structure = GridStructure.uniform2D(10, 10, 10.0, 10.0);
        var gridCells = java.util.List.of(
            GridCell.create2D(multiscaleIndex2D((byte) 1, 0, 0), 0.0, 0.0, 10.0, 10.0, 1, true),
            GridCell.create2D(multiscaleIndex2D((byte) 1, 1, 0), 10.0, 0.0, 10.0, 10.0, 1, true)
        );
        
        return GridVisualizationData.from(baseData, gridCells, structure);
    }
    
    private RefinementVisualizationData createRefinementVisualization(
        AdaptiveRefinementStrategy strategy, 
        RefinementCriteria criteria
    ) {
        var bounds = new com.dyada.visualization.data.Bounds(new double[]{0.0, 0.0}, new double[]{100.0, 100.0});
        var baseData = new VisualizationData("refinement-viz", null, 2, bounds, Map.of());
        
        // Create sample refinement decisions
        var context = new AdaptiveRefinementStrategy.RefinementContext(
            levelIndex2D((byte) 1, 5, 5),
            coordinate2D(50.0, 50.0),
            10.0,
            1,
            null
        );
        
        var decisions = Map.of(
            context, AdaptiveRefinementStrategy.RefinementDecision.REFINE
        );
        
        return RefinementVisualizationData.from(baseData, decisions);
    }
    
    private void testRenderingOptions(MeshVisualizationData meshViz) {
        // Test different rendering options
        var defaultOptions = RenderingOptions.defaultOptions();
        assertNotNull(defaultOptions);
        
        var wireframeOptions = RenderingOptions.wireframe();
        assertTrue(wireframeOptions.showWireframe());
        
        var errorOptions = RenderingOptions.errorVisualization();
        assertTrue(errorOptions.showErrorDistribution());
        assertEquals(RenderingOptions.ColorScheme.HEATMAP, errorOptions.colorScheme());
        
        var customOptions = defaultOptions.withTransparency(0.5);
        assertEquals(0.5, customOptions.transparency(), 1e-10);
    }
    
    private Coordinate generateClusteredPosition(int index, int total) {
        // Create clustered distribution for performance testing
        if (index < total * 0.7) {
            // 70% in main cluster
            return coordinate2D(
                50.0 + random.nextGaussian() * 10.0,
                50.0 + random.nextGaussian() * 10.0
            );
        } else if (index < total * 0.9) {
            // 20% in secondary cluster
            return coordinate2D(
                20.0 + random.nextGaussian() * 5.0,
                80.0 + random.nextGaussian() * 5.0
            );
        } else {
            // 10% scattered
            return randomCoordinate2D(0.0, 100.0);
        }
    }
}