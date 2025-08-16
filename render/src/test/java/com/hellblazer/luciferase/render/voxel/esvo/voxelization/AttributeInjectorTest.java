package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-first development for attribute injection.
 * Injects material properties and attributes into voxelized geometry.
 */
@DisplayName("Attribute Injector Tests")
class AttributeInjectorTest {
    
    private static final float EPSILON = 0.001f;
    private AttributeInjector injector;
    
    @BeforeEach
    void setup() {
        injector = new AttributeInjector();
    }
    
    @Test
    @DisplayName("Should inject vertex colors")
    void testVertexColorInjection() {
        // Triangle with vertex colors
        float[] v0 = {0, 0, 0};
        float[] v1 = {1, 0, 0};
        float[] v2 = {0, 1, 0};
        
        float[] color0 = {1, 0, 0, 1}; // Red
        float[] color1 = {0, 1, 0, 1}; // Green
        float[] color2 = {0, 0, 1, 1}; // Blue
        
        var voxel = new Voxel(0.33f, 0.33f, 0); // Center of triangle
        
        var attribute = injector.injectVertexColors(
            voxel, v0, v1, v2, color0, color1, color2
        );
        
        assertNotNull(attribute);
        assertNotNull(attribute.getColor());
        
        // Should be roughly gray (average of RGB)
        float[] color = attribute.getColor();
        assertEquals(0.333f, color[0], 0.1f);
        assertEquals(0.333f, color[1], 0.1f);
        assertEquals(0.333f, color[2], 0.1f);
    }
    
    @Test
    @DisplayName("Should interpolate texture coordinates")
    void testTextureCoordinateInterpolation() {
        float[] v0 = {0, 0, 0};
        float[] v1 = {1, 0, 0};
        float[] v2 = {0, 1, 0};
        
        float[] uv0 = {0, 0};
        float[] uv1 = {1, 0};
        float[] uv2 = {0, 1};
        
        var voxel = new Voxel(0.5f, 0.25f, 0);
        
        var attribute = injector.injectTextureCoordinates(
            voxel, v0, v1, v2, uv0, uv1, uv2
        );
        
        assertNotNull(attribute);
        float[] uv = attribute.getTextureCoords();
        assertNotNull(uv);
        
        // Barycentric interpolation
        assertEquals(0.5f, uv[0], EPSILON);
        assertEquals(0.25f, uv[1], EPSILON);
    }
    
    @Test
    @DisplayName("Should inject material properties")
    void testMaterialInjection() {
        var material = new Material()
            .withDiffuseColor(0.8f, 0.6f, 0.4f)
            .withSpecularColor(1.0f, 1.0f, 1.0f)
            .withRoughness(0.3f)
            .withMetallic(0.0f);
            
        var voxel = new Voxel(0, 0, 0);
        
        var attribute = injector.injectMaterial(voxel, material);
        
        assertNotNull(attribute);
        assertEquals(0.8f, attribute.getDiffuse()[0], EPSILON);
        assertEquals(0.3f, attribute.getRoughness(), EPSILON);
        assertEquals(0.0f, attribute.getMetallic(), EPSILON);
    }
    
    @Test
    @DisplayName("Should compute barycentric coordinates")
    void testBarycentricCoordinates() {
        float[] v0 = {0, 0, 0};
        float[] v1 = {1, 0, 0};
        float[] v2 = {0, 1, 0};
        
        // Point at v0
        float[] p1 = {0, 0, 0};
        float[] bary1 = injector.computeBarycentricCoordinates(p1, v0, v1, v2);
        assertEquals(1.0f, bary1[0], EPSILON);
        assertEquals(0.0f, bary1[1], EPSILON);
        assertEquals(0.0f, bary1[2], EPSILON);
        
        // Point at center
        float[] p2 = {0.333f, 0.333f, 0};
        float[] bary2 = injector.computeBarycentricCoordinates(p2, v0, v1, v2);
        assertEquals(0.333f, bary2[0], 0.01f);
        assertEquals(0.333f, bary2[1], 0.01f);
        assertEquals(0.333f, bary2[2], 0.01f);
    }
    
    @Test
    @DisplayName("Should inject normal maps")
    void testNormalMapInjection() {
        float[] v0 = {0, 0, 0};
        float[] v1 = {1, 0, 0};
        float[] v2 = {0, 1, 0};
        
        // Tangent space normals
        float[] n0 = {0, 0, 1};
        float[] n1 = {0, 0, 1};
        float[] n2 = {0, 0, 1};
        
        // Tangent and bitangent
        float[] tangent = {1, 0, 0};
        float[] bitangent = {0, 1, 0};
        
        var voxel = new Voxel(0.5f, 0.5f, 0);
        
        var attribute = injector.injectNormalMap(
            voxel, v0, v1, v2, n0, n1, n2, tangent, bitangent
        );
        
        assertNotNull(attribute);
        float[] normal = attribute.getNormal();
        assertNotNull(normal);
        
        // Should point in Z direction
        assertEquals(0, normal[0], EPSILON);
        assertEquals(0, normal[1], EPSILON);
        assertEquals(1, normal[2], EPSILON);
    }
    
    @Test
    @DisplayName("Should handle texture atlases")
    void testTextureAtlasMapping() {
        var atlas = new TextureAtlas(2048, 2048);
        atlas.addTexture("diffuse", 0, 0, 512, 512);
        atlas.addTexture("normal", 512, 0, 512, 512);
        
        float[] uv = {0.5f, 0.5f}; // Middle of texture
        
        float[] atlasUV = injector.mapToAtlas(uv, "diffuse", atlas);
        
        assertNotNull(atlasUV);
        // Should map to middle of first quadrant
        assertEquals(0.125f, atlasUV[0], EPSILON); // 256/2048
        assertEquals(0.125f, atlasUV[1], EPSILON);
    }
    
    @Test
    @DisplayName("Should compress attributes")
    void testAttributeCompression() {
        var attribute = new VoxelAttribute()
            .withColor(0.5f, 0.3f, 0.8f, 1.0f)
            .withNormal(0, 0, 1)
            .withRoughness(0.4f)
            .withMetallic(0.1f);
            
        byte[] compressed = injector.compressAttribute(attribute);
        
        assertNotNull(compressed);
        assertTrue(compressed.length <= 16, "Compressed size should be <= 16 bytes");
        
        // Should be able to decompress
        var decompressed = injector.decompressAttribute(compressed);
        assertNotNull(decompressed);
        
        // Check values (allowing for compression loss)
        assertEquals(0.5f, decompressed.getColor()[0], 0.01f);
        assertEquals(0.4f, decompressed.getRoughness(), 0.01f);
    }
    
    @Test
    @DisplayName("Should inject emissive properties")
    void testEmissiveInjection() {
        var material = new Material()
            .withEmissiveColor(1.0f, 0.8f, 0.3f)
            .withEmissiveIntensity(2.5f);
            
        var voxel = new Voxel(0, 0, 0);
        
        var attribute = injector.injectMaterial(voxel, material);
        
        assertNotNull(attribute.getEmissive());
        assertEquals(2.5f, attribute.getEmissive()[0], EPSILON); // R * intensity
        assertEquals(2.0f, attribute.getEmissive()[1], EPSILON); // G * intensity
        assertEquals(0.75f, attribute.getEmissive()[2], EPSILON); // B * intensity
    }
    
    @Test
    @DisplayName("Should batch inject attributes for mesh")
    void testBatchAttributeInjection() {
        // Create simple mesh
        var mesh = new TriangleMesh(
            new float[][]{{0,0,0}, {1,0,0}, {0,1,0}},
            new int[][]{{0,1,2}}
        );
        
        // Add attributes
        mesh.setVertexColors(new float[][]{
            {1,0,0,1}, {0,1,0,1}, {0,0,1,1}
        });
        
        // Voxelize
        var voxelizer = new TriangleVoxelizer();
        var config = new VoxelizationConfig().withResolution(8);
        var voxelResult = voxelizer.voxelizeMesh(mesh, config);
        
        // Inject attributes
        var attributeResult = injector.injectMeshAttributes(mesh, voxelResult);
        
        assertNotNull(attributeResult);
        assertEquals(voxelResult.getVoxelCount(), attributeResult.getAttributeCount());
        
        // All voxels should have attributes
        for (var voxel : voxelResult.getVoxels()) {
            var attr = attributeResult.getAttributeFor(voxel);
            assertNotNull(attr);
            assertNotNull(attr.getColor());
        }
    }
    
    @Test
    @DisplayName("Should handle opacity and transparency")
    void testOpacityHandling() {
        var material = new Material()
            .withDiffuseColor(1, 1, 1)
            .withOpacity(0.5f);
            
        var voxel = new Voxel(0, 0, 0);
        var attribute = injector.injectMaterial(voxel, material);
        
        assertEquals(0.5f, attribute.getOpacity(), EPSILON);
        assertTrue(attribute.isTransparent());
    }
    
    @Test
    @DisplayName("Should generate contour data")
    void testContourGeneration() {
        float[] v0 = {0, 0, 0};
        float[] v1 = {1, 0, 0};
        float[] v2 = {0, 1, 0};
        
        var voxel = new Voxel(0.33f, 0.33f, 0);
        
        var contour = injector.generateContour(voxel, v0, v1, v2);
        
        assertNotNull(contour);
        assertNotNull(contour.getNormal());
        assertTrue(contour.getDistance() >= 0);
        
        // Normal should be perpendicular to triangle
        float[] normal = contour.getNormal();
        assertEquals(0, normal[0], EPSILON);
        assertEquals(0, normal[1], EPSILON);
        assertEquals(1, Math.abs(normal[2]), EPSILON);
    }
}