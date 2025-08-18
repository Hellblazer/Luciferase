package com.dyada.visualization.data;

import com.dyada.TestBase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MeshVisualizationData Tests")
class MeshVisualizationDataTest extends TestBase {
    
    private static final Bounds BOUNDS_2D = new Bounds(new double[]{0.0, 0.0}, new double[]{10.0, 10.0});
    private static final Bounds BOUNDS_3D = new Bounds(new double[]{0.0, 0.0, 0.0}, new double[]{10.0, 10.0, 10.0});
    
    private List<MeshVertex> vertices2D;
    private List<MeshVertex> vertices3D;
    private List<MeshCell> cells2D;
    private List<MeshCell> cells3D;
    
    @BeforeEach
    void setUp() {
        // 2D vertices forming a square
        vertices2D = List.of(
            MeshVertex.create2D(0, 0.0, 0.0),
            MeshVertex.create2D(1, 5.0, 0.0),
            MeshVertex.create2D(2, 5.0, 5.0),
            MeshVertex.create2D(3, 0.0, 5.0)
        );
        
        // 3D vertices forming a tetrahedron
        vertices3D = List.of(
            MeshVertex.create3D(0, 0.0, 0.0, 0.0),
            MeshVertex.create3D(1, 5.0, 0.0, 0.0),
            MeshVertex.create3D(2, 2.5, 5.0, 0.0),
            MeshVertex.create3D(3, 2.5, 2.5, 5.0)
        );
        
        // 2D cells (triangles)
        cells2D = List.of(
            MeshCell.triangle(0, 0, 1, 2, 0),
            MeshCell.triangle(1, 0, 2, 3, 0)
        );
        
        // 3D cells (tetrahedron)
        cells3D = List.of(
            MeshCell.tetrahedron(0, 0, 1, 2, 3, 0)
        );
    }
    
    @Test
    @DisplayName("Basic constructor with valid parameters")
    void testValidConstruction() {
        var timestamp = Instant.now();
        var metadata = Map.<String, Object>of("type", "adaptive_mesh");
        
        var meshData = new MeshVisualizationData(
            "mesh-id", timestamp, 2, BOUNDS_2D, metadata,
            vertices2D, cells2D, Map.of(), Map.of()
        );
        
        assertEquals("mesh-id", meshData.id());
        assertEquals(timestamp, meshData.timestamp());
        assertEquals(2, meshData.dimensions());
        assertEquals(BOUNDS_2D, meshData.bounds());
        assertEquals(metadata, meshData.metadata());
        assertEquals(vertices2D, meshData.vertices());
        assertEquals(cells2D, meshData.cells());
        assertEquals(4, meshData.vertexCount());
        assertEquals(2, meshData.cellCount());
    }
    
    @Test
    @DisplayName("Constructor with null collections should use empty lists/maps")
    void testNullCollectionsDefaultToEmpty() {
        var meshData = new MeshVisualizationData(
            "mesh-id", Instant.now(), 2, BOUNDS_2D, Map.of(),
            null, null, null, null
        );
        
        assertNotNull(meshData.vertices());
        assertNotNull(meshData.cells());
        assertNotNull(meshData.scalarFields());
        assertNotNull(meshData.vectorFields());
        assertTrue(meshData.vertices().isEmpty());
        assertTrue(meshData.cells().isEmpty());
        assertTrue(meshData.scalarFields().isEmpty());
        assertTrue(meshData.vectorFields().isEmpty());
        assertEquals(0, meshData.vertexCount());
        assertEquals(0, meshData.cellCount());
    }
    
    @Test
    @DisplayName("Vertex dimension validation")
    void testVertexDimensionValidation() {
        // 3D vertex in 2D mesh should fail
        var invalid3DVertex = MeshVertex.create3D(0, 1.0, 2.0, 3.0);
        var invalidVertices = List.of(invalid3DVertex);
        
        assertThrows(IllegalArgumentException.class, () ->
            new MeshVisualizationData(
                "mesh-id", Instant.now(), 2, BOUNDS_2D, Map.of(),
                invalidVertices, List.of(), Map.of(), Map.of()
            )
        );
        
        // 2D vertex in 3D mesh should fail
        var invalid2DVertex = MeshVertex.create2D(0, 1.0, 2.0);
        var invalidVertices2D = List.of(invalid2DVertex);
        
        assertThrows(IllegalArgumentException.class, () ->
            new MeshVisualizationData(
                "mesh-id", Instant.now(), 3, BOUNDS_3D, Map.of(),
                invalidVertices2D, List.of(), Map.of(), Map.of()
            )
        );
    }
    
    @Test
    @DisplayName("Create from base VisualizationData")
    void testFromBaseVisualizationData() {
        var baseData = new VisualizationData(
            "base-id", Instant.now(), 2, BOUNDS_2D, Map.of("source", "base")
        );
        
        var meshData = MeshVisualizationData.from(baseData, vertices2D, cells2D);
        
        assertEquals("base-id", meshData.id());
        assertEquals(baseData.timestamp(), meshData.timestamp());
        assertEquals(2, meshData.dimensions());
        assertEquals(BOUNDS_2D, meshData.bounds());
        assertEquals(Map.of("source", "base"), meshData.metadata());
        assertEquals(vertices2D, meshData.vertices());
        assertEquals(cells2D, meshData.cells());
        assertTrue(meshData.scalarFields().isEmpty());
        assertTrue(meshData.vectorFields().isEmpty());
    }
    
    @Test
    @DisplayName("Add scalar field data")
    void testWithScalarField() {
        var meshData = new MeshVisualizationData(
            "mesh-id", Instant.now(), 2, BOUNDS_2D, Map.of(),
            vertices2D, cells2D, Map.of(), Map.of()
        );
        
        var scalarValues = new double[]{1.0, 2.0, 3.0, 4.0}; // One value per vertex
        var updatedMesh = meshData.withScalarField("temperature", scalarValues);
        
        // Original should be unchanged
        assertTrue(meshData.scalarFields().isEmpty());
        
        // Updated should have scalar field
        assertEquals(1, updatedMesh.scalarFields().size());
        assertArrayEquals(scalarValues, updatedMesh.scalarFields().get("temperature"), 1e-10);
        
        // Other fields should be identical
        assertEquals(meshData.id(), updatedMesh.id());
        assertEquals(meshData.vertices(), updatedMesh.vertices());
        assertEquals(meshData.cells(), updatedMesh.cells());
    }
    
    @Test
    @DisplayName("Add multiple scalar fields")
    void testMultipleScalarFields() {
        var meshData = new MeshVisualizationData(
            "mesh-id", Instant.now(), 2, BOUNDS_2D, Map.of(),
            vertices2D, cells2D, Map.of(), Map.of()
        );
        
        var temperature = new double[]{100.0, 200.0, 300.0, 400.0};
        var pressure = new double[]{1.0, 2.0, 3.0, 4.0};
        
        var updatedMesh = meshData
            .withScalarField("temperature", temperature)
            .withScalarField("pressure", pressure);
        
        assertEquals(2, updatedMesh.scalarFields().size());
        assertArrayEquals(temperature, updatedMesh.scalarFields().get("temperature"), 1e-10);
        assertArrayEquals(pressure, updatedMesh.scalarFields().get("pressure"), 1e-10);
    }
    
    @Test
    @DisplayName("Add vector field data")
    void testWithVectorField() {
        var meshData = new MeshVisualizationData(
            "mesh-id", Instant.now(), 2, BOUNDS_2D, Map.of(),
            vertices2D, cells2D, Map.of(), Map.of()
        );
        
        var velocityVectors = new double[][]{
            {1.0, 0.0}, {0.0, 1.0}, {-1.0, 0.0}, {0.0, -1.0}
        };
        var updatedMesh = meshData.withVectorField("velocity", velocityVectors);
        
        // Original should be unchanged
        assertTrue(meshData.vectorFields().isEmpty());
        
        // Updated should have vector field
        assertEquals(1, updatedMesh.vectorFields().size());
        assertArrayEquals(velocityVectors, updatedMesh.vectorFields().get("velocity"));
    }
    
    @Test
    @DisplayName("Add multiple vector fields")
    void testMultipleVectorFields() {
        var meshData = new MeshVisualizationData(
            "mesh-id", Instant.now(), 3, BOUNDS_3D, Map.of(),
            vertices3D, cells3D, Map.of(), Map.of()
        );
        
        var velocity = new double[][]{
            {1.0, 0.0, 0.0}, {0.0, 1.0, 0.0}, {0.0, 0.0, 1.0}, {1.0, 1.0, 1.0}
        };
        var force = new double[][]{
            {0.5, 0.0, 0.0}, {0.0, 0.5, 0.0}, {0.0, 0.0, 0.5}, {0.5, 0.5, 0.5}
        };
        
        var updatedMesh = meshData
            .withVectorField("velocity", velocity)
            .withVectorField("force", force);
        
        assertEquals(2, updatedMesh.vectorFields().size());
        assertArrayEquals(velocity, updatedMesh.vectorFields().get("velocity"));
        assertArrayEquals(force, updatedMesh.vectorFields().get("force"));
    }
    
    @Test
    @DisplayName("Find vertices in region")
    void testGetVerticesInRegion() {
        var meshData = new MeshVisualizationData(
            "mesh-id", Instant.now(), 2, BOUNDS_2D, Map.of(),
            vertices2D, cells2D, Map.of(), Map.of()
        );
        
        // Region covering bottom-left quarter
        var region = new Bounds(new double[]{0.0, 0.0}, new double[]{2.5, 2.5});
        var verticesInRegion = meshData.getVerticesInRegion(region);
        
        assertEquals(1, verticesInRegion.size());
        assertEquals(0, verticesInRegion.get(0).id()); // Only vertex at (0,0)
    }
    
    @Test
    @DisplayName("Find vertices in region - no vertices found")
    void testGetVerticesInRegionEmpty() {
        var meshData = new MeshVisualizationData(
            "mesh-id", Instant.now(), 2, BOUNDS_2D, Map.of(),
            vertices2D, cells2D, Map.of(), Map.of()
        );
        
        // Region outside mesh bounds
        var region = new Bounds(new double[]{10.0, 10.0}, new double[]{15.0, 15.0});
        var verticesInRegion = meshData.getVerticesInRegion(region);
        
        assertTrue(verticesInRegion.isEmpty());
    }
    
    @Test
    @DisplayName("Find vertices in region - all vertices")
    void testGetVerticesInRegionAll() {
        var meshData = new MeshVisualizationData(
            "mesh-id", Instant.now(), 2, BOUNDS_2D, Map.of(),
            vertices2D, cells2D, Map.of(), Map.of()
        );
        
        // Region covering entire mesh
        var region = new Bounds(new double[]{-1.0, -1.0}, new double[]{6.0, 6.0});
        var verticesInRegion = meshData.getVerticesInRegion(region);
        
        assertEquals(4, verticesInRegion.size());
    }
    
    @Test
    @DisplayName("Convert to base VisualizationData")
    void testAsVisualizationData() {
        var timestamp = Instant.now();
        var metadata = Map.<String, Object>of("mesh_type", "adaptive");
        
        var meshData = new MeshVisualizationData(
            "mesh-id", timestamp, 2, BOUNDS_2D, metadata,
            vertices2D, cells2D, Map.of("temperature", new double[]{1, 2, 3, 4}), Map.of()
        );
        
        var baseData = meshData.asVisualizationData();
        
        assertEquals("mesh-id", baseData.id());
        assertEquals(timestamp, baseData.timestamp());
        assertEquals(2, baseData.dimensions());
        assertEquals(BOUNDS_2D, baseData.bounds());
        assertEquals(metadata, baseData.metadata());
        
        // Should be a VisualizationData instance, not MeshVisualizationData
        assertEquals(VisualizationData.class, baseData.getClass());
    }
    
    @Test
    @DisplayName("Empty mesh visualization")
    void testEmptyMesh() {
        var meshData = new MeshVisualizationData(
            "empty-mesh", Instant.now(), 2, BOUNDS_2D, Map.of(),
            List.of(), List.of(), Map.of(), Map.of()
        );
        
        assertEquals(0, meshData.vertexCount());
        assertEquals(0, meshData.cellCount());
        assertTrue(meshData.vertices().isEmpty());
        assertTrue(meshData.cells().isEmpty());
        
        // Operations on empty mesh should work
        var verticesInRegion = meshData.getVerticesInRegion(BOUNDS_2D);
        assertTrue(verticesInRegion.isEmpty());
    }
    
    @Test
    @DisplayName("3D mesh with tetrahedron")
    void test3DMesh() {
        var meshData = new MeshVisualizationData(
            "3d-mesh", Instant.now(), 3, BOUNDS_3D, Map.of(),
            vertices3D, cells3D, Map.of(), Map.of()
        );
        
        assertEquals(3, meshData.dimensions());
        assertEquals(4, meshData.vertexCount());
        assertEquals(1, meshData.cellCount());
        
        // All vertices should be in 3D region
        var allVertices = meshData.getVerticesInRegion(BOUNDS_3D);
        assertEquals(4, allVertices.size());
    }
    
    @Test
    @DisplayName("Scalar field immutability")
    void testScalarFieldImmutability() {
        var meshData = new MeshVisualizationData(
            "mesh-id", Instant.now(), 2, BOUNDS_2D, Map.of(),
            vertices2D, cells2D, Map.of(), Map.of()
        );
        
        var originalValues = new double[]{1.0, 2.0, 3.0, 4.0};
        var updatedMesh = meshData.withScalarField("test", originalValues);
        
        // Modifying original array should not affect the mesh data
        originalValues[0] = 999.0;
        
        var storedValues = updatedMesh.scalarFields().get("test");
        assertEquals(1.0, storedValues[0]); // Should still be original value
    }
    
    @Test
    @DisplayName("Vector field immutability")
    void testVectorFieldImmutability() {
        var meshData = new MeshVisualizationData(
            "mesh-id", Instant.now(), 2, BOUNDS_2D, Map.of(),
            vertices2D, cells2D, Map.of(), Map.of()
        );
        
        var originalVectors = new double[][]{{1.0, 2.0}, {3.0, 4.0}};
        var updatedMesh = meshData.withVectorField("test", originalVectors);
        
        // Modifying original array should not affect the mesh data
        originalVectors[0][0] = 999.0;
        
        var storedVectors = updatedMesh.vectorFields().get("test");
        assertEquals(1.0, storedVectors[0][0]); // Should still be original value
    }
    
    @Test
    @DisplayName("Record equality and hashCode")
    void testRecordEquality() {
        var timestamp = Instant.now();
        var metadata = Map.<String, Object>of("test", "value");
        
        var meshData1 = new MeshVisualizationData(
            "mesh-id", timestamp, 2, BOUNDS_2D, metadata,
            vertices2D, cells2D, Map.of(), Map.of()
        );
        
        var meshData2 = new MeshVisualizationData(
            "mesh-id", timestamp, 2, BOUNDS_2D, metadata,
            vertices2D, cells2D, Map.of(), Map.of()
        );
        
        var meshData3 = new MeshVisualizationData(
            "different-id", timestamp, 2, BOUNDS_2D, metadata,
            vertices2D, cells2D, Map.of(), Map.of()
        );
        
        assertEquals(meshData1, meshData2);
        assertEquals(meshData1.hashCode(), meshData2.hashCode());
        assertNotEquals(meshData1, meshData3);
    }
    
    @Test
    @DisplayName("Field overwriting behavior")
    void testFieldOverwriting() {
        var meshData = new MeshVisualizationData(
            "mesh-id", Instant.now(), 2, BOUNDS_2D, Map.of(),
            vertices2D, cells2D, 
            Map.of("existing", new double[]{1, 2, 3, 4}), 
            Map.of()
        );
        
        // Adding field with same name should overwrite
        var newValues = new double[]{5, 6, 7, 8};
        var updated = meshData.withScalarField("existing", newValues);
        
        assertArrayEquals(newValues, updated.scalarFields().get("existing"), 1e-10);
        assertEquals(1, updated.scalarFields().size()); // Still only one field
    }
}