package com.dyada.visualization.data;

import com.dyada.core.MultiscaleIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GridVisualizationDataTest {

    @Test
    @DisplayName("Basic constructor and field access")
    void testBasicConstructor() {
        var structure = GridStructure.uniform2D(10, 10, 1.0, 1.0);
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var timestamp = Instant.now();
        
        var gridData = new GridVisualizationData(
            "grid-1",
            timestamp,
            2,
            bounds,
            Map.of("type", "uniform"),
            List.of(),
            Map.of(),
            structure
        );
        
        assertEquals("grid-1", gridData.id());
        assertEquals(timestamp, gridData.timestamp());
        assertEquals(2, gridData.dimensions());
        assertEquals(bounds, gridData.bounds());
        assertEquals("uniform", gridData.metadata().get("type"));
        assertTrue(gridData.gridCells().isEmpty());
        assertTrue(gridData.cellData().isEmpty());
        assertEquals(structure, gridData.structure());
    }

    @Test
    @DisplayName("Constructor with grid cells")
    void testConstructorWithCells() {
        var index1 = new MultiscaleIndex(new byte[]{0}, new int[]{0});
        var index2 = new MultiscaleIndex(new byte[]{0}, new int[]{1});
        
        var cell1 = GridCell.create2D(index1, 0.0, 0.0, 1.0, 1.0, 0, true);
        var cell2 = GridCell.create2D(index2, 1.0, 0.0, 1.0, 1.0, 0, true);
        var cells = List.of(cell1, cell2);
        
        var structure = GridStructure.uniform2D(10, 10, 1.0, 1.0);
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        
        var gridData = new GridVisualizationData(
            "grid-2",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            cells,
            Map.of(),
            structure
        );
        
        assertEquals(2, gridData.getCellCount());
        assertEquals(cells, gridData.gridCells());
    }

    @Test
    @DisplayName("Constructor validation - null structure")
    void testNullStructureValidation() {
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        
        assertThrows(IllegalArgumentException.class, () -> {
            new GridVisualizationData(
                "grid-bad",
                Instant.now(),
                2,
                bounds,
                Map.of(),
                List.of(),
                Map.of(),
                null
            );
        });
    }

    @Test
    @DisplayName("Constructor validation - dimension mismatch")
    void testDimensionMismatchValidation() {
        var index = new MultiscaleIndex(new byte[]{0}, new int[]{0});
        var cell3D = GridCell.create3D(index, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 0, true);
        var cells = List.of(cell3D);
        
        var structure = GridStructure.uniform2D(10, 10, 1.0, 1.0);
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        
        assertThrows(IllegalArgumentException.class, () -> {
            new GridVisualizationData(
                "grid-mismatch",
                Instant.now(),
                2, // 2D dimensions
                bounds,
                Map.of(),
                cells, // 3D cells
                Map.of(),
                structure
            );
        });
    }

    @Test
    @DisplayName("Factory method from VisualizationData")
    void testFromVisualizationData() {
        var baseData = new VisualizationData(
            "base-1",
            Instant.now(),
            2,
            new Bounds(new double[]{0, 0}, new double[]{5, 5}),
            Map.of("source", "test")
        );
        
        var structure = GridStructure.uniform2D(5, 5, 1.0, 1.0);
        var index = new MultiscaleIndex(new byte[]{0}, new int[]{0});
        var cells = List.of(GridCell.create2D(index, 0.0, 0.0, 1.0, 1.0, 0, true));
        
        var gridData = GridVisualizationData.from(baseData, cells, structure);
        
        assertEquals(baseData.id(), gridData.id());
        assertEquals(baseData.timestamp(), gridData.timestamp());
        assertEquals(baseData.dimensions(), gridData.dimensions());
        assertEquals(baseData.bounds(), gridData.bounds());
        assertEquals(baseData.metadata(), gridData.metadata());
        assertEquals(cells, gridData.gridCells());
        assertEquals(structure, gridData.structure());
        assertTrue(gridData.cellData().isEmpty());
    }

    @Test
    @DisplayName("Adding cell data with withCellData")
    void testWithCellData() {
        var index1 = new MultiscaleIndex(new byte[]{0}, new int[]{0});
        var index2 = new MultiscaleIndex(new byte[]{0}, new int[]{1});
        var cells = List.of(
            GridCell.create2D(index1, 0.0, 0.0, 1.0, 1.0, 0, true),
            GridCell.create2D(index2, 1.0, 0.0, 1.0, 1.0, 0, true)
        );
        
        var structure = GridStructure.uniform2D(10, 10, 1.0, 1.0);
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        
        var gridData = new GridVisualizationData(
            "grid-data",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            cells,
            Map.of(),
            structure
        );
        
        double[] temperature = {25.0, 30.0};
        var gridDataWithTemp = gridData.withCellData("temperature", temperature);
        
        assertArrayEquals(temperature, gridDataWithTemp.cellData().get("temperature"));
        assertEquals(1, gridDataWithTemp.getAvailableFields().size());
        assertTrue(gridDataWithTemp.getAvailableFields().contains("temperature"));
        
        // Original should be unchanged
        assertTrue(gridData.cellData().isEmpty());
    }

    @Test
    @DisplayName("Cell data validation - length mismatch")
    void testCellDataLengthValidation() {
        var index = new MultiscaleIndex(new byte[]{0}, new int[]{0});
        var cells = List.of(GridCell.create2D(index, 0.0, 0.0, 1.0, 1.0, 0, true));
        
        var structure = GridStructure.uniform2D(10, 10, 1.0, 1.0);
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        
        var gridData = new GridVisualizationData(
            "grid-data",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            cells,
            Map.of(),
            structure
        );
        
        double[] wrongSizeData = {1.0, 2.0}; // 2 values for 1 cell
        
        assertThrows(IllegalArgumentException.class, () -> {
            gridData.withCellData("field", wrongSizeData);
        });
    }

    @Test
    @DisplayName("Multiple cell data fields")
    void testMultipleCellDataFields() {
        var index1 = new MultiscaleIndex(new byte[]{0}, new int[]{0});
        var index2 = new MultiscaleIndex(new byte[]{0}, new int[]{1});
        var cells = List.of(
            GridCell.create2D(index1, 0.0, 0.0, 1.0, 1.0, 0, true),
            GridCell.create2D(index2, 1.0, 0.0, 1.0, 1.0, 0, true)
        );
        
        var structure = GridStructure.uniform2D(10, 10, 1.0, 1.0);
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        
        var gridData = new GridVisualizationData(
            "multi-field",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            cells,
            Map.of(),
            structure
        );
        
        double[] temperature = {25.0, 30.0};
        double[] pressure = {1013.25, 1015.0};
        
        var gridWithData = gridData
            .withCellData("temperature", temperature)
            .withCellData("pressure", pressure);
        
        assertEquals(2, gridWithData.getAvailableFields().size());
        assertTrue(gridWithData.getAvailableFields().contains("temperature"));
        assertTrue(gridWithData.getAvailableFields().contains("pressure"));
        
        assertArrayEquals(temperature, gridWithData.cellData().get("temperature"));
        assertArrayEquals(pressure, gridWithData.cellData().get("pressure"));
    }

    @Test
    @DisplayName("Refinement level operations")
    void testRefinementLevelOperations() {
        var index1 = new MultiscaleIndex(new byte[]{0}, new int[]{0});
        var index2 = new MultiscaleIndex(new byte[]{1}, new int[]{1});
        var index3 = new MultiscaleIndex(new byte[]{2}, new int[]{2});
        
        var cells = List.of(
            GridCell.create2D(index1, 0.0, 0.0, 2.0, 2.0, 0, true), // Level 0
            GridCell.create2D(index2, 2.0, 0.0, 1.0, 1.0, 1, true), // Level 1
            GridCell.create2D(index3, 2.0, 1.0, 0.5, 0.5, 2, true)  // Level 2
        );
        
        var structure = GridStructure.adaptive(
            new int[]{4, 4},
            new double[]{1.0, 1.0},
            new double[]{0.0, 0.0},
            2,
            "morton"
        );
        var bounds = new Bounds(new double[]{0, 0}, new double[]{4, 4});
        
        var gridData = new GridVisualizationData(
            "refinement-test",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            cells,
            Map.of(),
            structure
        );
        
        assertEquals(2, gridData.getMaxRefinementLevel());
        
        assertEquals(1, gridData.getCellsAtLevel(0).size());
        assertEquals(1, gridData.getCellsAtLevel(1).size());
        assertEquals(1, gridData.getCellsAtLevel(2).size());
        assertEquals(0, gridData.getCellsAtLevel(3).size());
    }

    @Test
    @DisplayName("Spatial region queries")
    void testSpatialRegionQueries() {
        var index1 = new MultiscaleIndex(new byte[]{0}, new int[]{0});
        var index2 = new MultiscaleIndex(new byte[]{0}, new int[]{1});
        var index3 = new MultiscaleIndex(new byte[]{0}, new int[]{2});
        
        var cells = List.of(
            GridCell.create2D(index1, 0.0, 0.0, 1.0, 1.0, 0, true),
            GridCell.create2D(index2, 2.0, 2.0, 1.0, 1.0, 0, true),
            GridCell.create2D(index3, 5.0, 5.0, 1.0, 1.0, 0, true)
        );
        
        var structure = GridStructure.uniform2D(10, 10, 1.0, 1.0);
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        
        var gridData = new GridVisualizationData(
            "region-test",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            cells,
            Map.of(),
            structure
        );
        
        // Region that contains first cell
        var region1 = new Bounds(new double[]{-0.5, -0.5}, new double[]{1.5, 1.5});
        var cellsInRegion1 = gridData.getCellsInRegion(region1);
        assertEquals(1, cellsInRegion1.size());
        assertEquals(cells.get(0), cellsInRegion1.get(0));
        
        // Region that contains first two cells
        var region2 = new Bounds(new double[]{-1.0, -1.0}, new double[]{4.0, 4.0});
        var cellsInRegion2 = gridData.getCellsInRegion(region2);
        assertEquals(2, cellsInRegion2.size());
        
        // Empty region
        var emptyRegion = new Bounds(new double[]{10.0, 10.0}, new double[]{11.0, 11.0});
        var cellsInEmptyRegion = gridData.getCellsInRegion(emptyRegion);
        assertTrue(cellsInEmptyRegion.isEmpty());
    }

    @Test
    @DisplayName("Empty grid handling")
    void testEmptyGrid() {
        var structure = GridStructure.uniform2D(10, 10, 1.0, 1.0);
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        
        var emptyGrid = new GridVisualizationData(
            "empty-grid",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            List.of(),
            Map.of(),
            structure
        );
        
        assertEquals(0, emptyGrid.getCellCount());
        assertEquals(0, emptyGrid.getMaxRefinementLevel());
        assertTrue(emptyGrid.getCellsAtLevel(0).isEmpty());
        assertTrue(emptyGrid.getAvailableFields().isEmpty());
        
        var emptyRegion = new Bounds(new double[]{0, 0}, new double[]{5, 5});
        assertTrue(emptyGrid.getCellsInRegion(emptyRegion).isEmpty());
    }

    @Test
    @DisplayName("3D grid support")
    void testThreeDimensionalGrid() {
        var index = new MultiscaleIndex(new byte[]{0}, new int[]{0});
        var cell3D = GridCell.create3D(index, 1.0, 2.0, 3.0, 0.5, 0.5, 0.5, 0, true);
        var cells = List.of(cell3D);
        
        var structure = GridStructure.uniform3D(5, 5, 5, 1.0, 1.0, 1.0);
        var bounds = new Bounds(new double[]{0, 0, 0}, new double[]{5, 5, 5});
        
        var grid3D = new GridVisualizationData(
            "grid-3d",
            Instant.now(),
            3,
            bounds,
            Map.of(),
            cells,
            Map.of(),
            structure
        );
        
        assertEquals(3, grid3D.dimensions());
        assertEquals(1, grid3D.getCellCount());
        assertEquals(cell3D, grid3D.gridCells().get(0));
    }

    @Test
    @DisplayName("Conversion to VisualizationData")
    void testAsVisualizationData() {
        var structure = GridStructure.uniform2D(10, 10, 1.0, 1.0);
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        var timestamp = Instant.now();
        var metadata = Map.<String, Object>of("test", "value");
        
        var gridData = new GridVisualizationData(
            "convert-test",
            timestamp,
            2,
            bounds,
            metadata,
            List.of(),
            Map.of(),
            structure
        );
        
        var baseData = gridData.asVisualizationData();
        
        assertEquals(gridData.id(), baseData.id());
        assertEquals(gridData.timestamp(), baseData.timestamp());
        assertEquals(gridData.dimensions(), baseData.dimensions());
        assertEquals(gridData.bounds(), baseData.bounds());
        assertEquals(gridData.metadata(), baseData.metadata());
    }

    @Test
    @DisplayName("Immutability of collections")
    void testImmutability() {
        var index = new MultiscaleIndex(new byte[]{0}, new int[]{0});
        var cells = new java.util.ArrayList<>(
            List.of(GridCell.create2D(index, 0.0, 0.0, 1.0, 1.0, 0, true))
        );
        var cellData = new java.util.HashMap<String, double[]>();
        cellData.put("temp", new double[]{25.0});
        
        var structure = GridStructure.uniform2D(10, 10, 1.0, 1.0);
        var bounds = new Bounds(new double[]{0, 0}, new double[]{10, 10});
        
        var gridData = new GridVisualizationData(
            "immutable-test",
            Instant.now(),
            2,
            bounds,
            Map.of(),
            cells,
            cellData,
            structure
        );
        
        // Modifications to original collections shouldn't affect the record
        cells.clear();
        cellData.clear();
        
        assertEquals(1, gridData.getCellCount());
        assertEquals(1, gridData.getAvailableFields().size());
    }
}