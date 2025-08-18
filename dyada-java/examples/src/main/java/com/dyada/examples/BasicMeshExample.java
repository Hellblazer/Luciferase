package com.dyada.examples;

import com.dyada.refinement.*;
import com.dyada.core.coordinates.*;

/**
 * Basic example demonstrating adaptive mesh refinement with entity management.
 * Shows how to create a mesh, insert entities, and perform refinement operations.
 */
public class BasicMeshExample {
    
    public static void main(String[] args) {
        System.out.println("=== DyAda Basic Mesh Example ===");
        
        // Create mesh bounds (2D: 0,0 to 10,10)
        var bounds = new Bounds(
            new double[]{0.0, 0.0}, 
            new double[]{10.0, 10.0}
        );
        
        // Initialize adaptive mesh
        // Parameters: bounds, initial resolution, max refinement level, min cell size
        var mesh = new AdaptiveMesh(bounds, 4, 8, 0.1);
        System.out.printf("Created mesh with bounds: [%.1f,%.1f] to [%.1f,%.1f]%n",
            bounds.min()[0], bounds.min()[1], bounds.max()[0], bounds.max()[1]);
        
        // Insert some entities at various positions
        mesh.insertEntity("building_1", new Coordinate(new double[]{2.5, 3.7}));
        mesh.insertEntity("building_2", new Coordinate(new double[]{7.2, 1.8}));
        mesh.insertEntity("tree_cluster", new Coordinate(new double[]{4.1, 8.3}));
        mesh.insertEntity("road_intersection", new Coordinate(new double[]{5.0, 5.0}));
        
        System.out.printf("Inserted %d entities into mesh%n", mesh.getEntityCount());
        
        // Display initial mesh statistics
        var initialStats = mesh.computeStatistics();
        System.out.printf("Initial mesh: %d total cells, %d active cells, %d leaf cells%n",
            initialStats.totalCells(), initialStats.activeCells(), initialStats.leafCells());
        
        // Set up refinement criteria - error-based refinement
        var criteria = RefinementCriteria.errorBased(
            0.01,  // error tolerance
            0.1,   // refinement threshold
            0.05   // coarsening threshold
        );
        
        // Create refinement strategy
        var strategy = new ErrorBasedRefinement(0.1, 0.05, 0.01);
        
        // Perform adaptive refinement
        System.out.println("\\nPerforming adaptive refinement...");
        var result = mesh.refineAdaptively(strategy, criteria);
        
        System.out.printf("Refinement completed in %d ms%n", result.executionTimeMs());
        System.out.printf("- Cells refined: %d%n", result.cellsRefined());
        System.out.printf("- Cells coarsened: %d%n", result.cellsCoarsened());
        System.out.printf("- New active cells: %d%n", result.newActiveCells());
        
        // Display final mesh statistics
        var finalStats = result.statistics();
        System.out.printf("Final mesh: %d total cells, %d active cells, %d leaf cells%n",
            finalStats.totalCells(), finalStats.activeCells(), finalStats.leafCells());
        System.out.printf("Max refinement level: %d%n", finalStats.maxLevel());
        System.out.printf("Average refinement score: %.4f%n", finalStats.averageRefinementScore());
        
        // Show distribution of cells by level
        System.out.println("\\nCells by refinement level:");
        finalStats.cellsByLevel().forEach((level, count) ->
            System.out.printf("  Level %d: %d cells%n", level, count));
        
        // Demonstrate entity queries
        System.out.println("\\nEntity operations:");
        
        // Check if entities exist
        System.out.printf("Contains 'building_1': %b%n", mesh.containsEntity("building_1"));
        
        // Get entity position
        var building1Pos = mesh.getEntityPosition("building_1");
        System.out.printf("Building 1 position: [%.1f, %.1f]%n", 
            building1Pos.values()[0], building1Pos.values()[1]);
        
        // Query entities within range
        var center = new Coordinate(new double[]{5.0, 5.0});
        var nearbyEntities = mesh.queryEntitiesInRange(center, 3.0);
        System.out.printf("Entities within 3.0 units of center [5.0, 5.0]: %s%n", nearbyEntities);
        
        // Update entity position
        mesh.updateEntityPosition("building_2", new Coordinate(new double[]{8.5, 2.1}));
        System.out.println("Updated building_2 position to [8.5, 2.1]");
        
        // Demonstrate field value operations
        System.out.println("\\nField value operations:");
        
        // Set field values at specific locations
        mesh.setFieldValue(new Coordinate(new double[]{2.5, 3.7}), "temperature", 25.5);
        mesh.setFieldValue(new Coordinate(new double[]{7.2, 1.8}), "temperature", 23.8);
        mesh.setFieldValue(new Coordinate(new double[]{4.1, 8.3}), "humidity", 65.2);
        
        // Retrieve field values
        var temp1 = mesh.getFieldValue(new Coordinate(new double[]{2.5, 3.7}), "temperature");
        var temp2 = mesh.getFieldValue(new Coordinate(new double[]{7.2, 1.8}), "temperature");
        var humidity = mesh.getFieldValue(new Coordinate(new double[]{4.1, 8.3}), "humidity");
        
        System.out.printf("Temperature at [2.5, 3.7]: %s°C%n", 
            temp1.map(t -> String.format("%.1f", t)).orElse("not set"));
        System.out.printf("Temperature at [7.2, 1.8]: %s°C%n",
            temp2.map(t -> String.format("%.1f", t)).orElse("not set"));
        System.out.printf("Humidity at [4.1, 8.3]: %s%%%n",
            humidity.map(h -> String.format("%.1f", h)).orElse("not set"));
        
        System.out.println("\\n=== Example completed successfully ===");
    }
}