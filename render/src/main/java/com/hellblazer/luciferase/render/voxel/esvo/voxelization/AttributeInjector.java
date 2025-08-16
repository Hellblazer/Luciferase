package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

import java.nio.ByteBuffer;

/**
 * Injects material properties and attributes into voxelized geometry.
 * Handles interpolation, compression, and attribute mapping.
 */
public class AttributeInjector {
    
    private static final float EPSILON = 1e-6f;
    
    /**
     * Inject vertex colors using barycentric interpolation
     */
    public VoxelAttribute injectVertexColors(Voxel voxel, float[] v0, float[] v1, float[] v2,
                                            float[] color0, float[] color1, float[] color2) {
        VoxelAttribute attribute = new VoxelAttribute();
        
        // Get voxel center position
        float[] point = voxel.getPosition();
        
        // Compute barycentric coordinates
        float[] bary = computeBarycentricCoordinates(point, v0, v1, v2);
        
        // Interpolate colors
        float[] color = new float[4];
        for (int i = 0; i < 4; i++) {
            color[i] = bary[0] * color0[i] + bary[1] * color1[i] + bary[2] * color2[i];
        }
        
        attribute.setColor(color);
        return attribute;
    }
    
    /**
     * Interpolate texture coordinates
     */
    public VoxelAttribute injectTextureCoordinates(Voxel voxel, float[] v0, float[] v1, float[] v2,
                                                  float[] uv0, float[] uv1, float[] uv2) {
        VoxelAttribute attribute = new VoxelAttribute();
        
        // Get voxel center position
        float[] point = voxel.getPosition();
        
        // Compute barycentric coordinates
        float[] bary = computeBarycentricCoordinates(point, v0, v1, v2);
        
        // Interpolate UVs
        float[] uv = new float[2];
        uv[0] = bary[0] * uv0[0] + bary[1] * uv1[0] + bary[2] * uv2[0];
        uv[1] = bary[0] * uv0[1] + bary[1] * uv1[1] + bary[2] * uv2[1];
        
        attribute.setTextureCoords(uv);
        return attribute;
    }
    
    /**
     * Inject material properties
     */
    public VoxelAttribute injectMaterial(Voxel voxel, Material material) {
        VoxelAttribute attribute = new VoxelAttribute();
        
        // Copy diffuse color
        attribute.setDiffuse(material.getDiffuseColor().clone());
        
        // Copy specular color
        attribute.setSpecular(material.getSpecularColor().clone());
        
        // Handle emissive
        if (material.getEmissiveIntensity() > 0) {
            float[] emissive = new float[3];
            float[] emissiveColor = material.getEmissiveColor();
            for (int i = 0; i < 3; i++) {
                emissive[i] = emissiveColor[i] * material.getEmissiveIntensity();
            }
            attribute.setEmissive(emissive);
        }
        
        // Copy material properties
        attribute.setRoughness(material.getRoughness());
        attribute.setMetallic(material.getMetallic());
        attribute.setOpacity(material.getOpacity());
        
        return attribute;
    }
    
    /**
     * Compute barycentric coordinates for point in triangle
     */
    public float[] computeBarycentricCoordinates(float[] p, float[] v0, float[] v1, float[] v2) {
        // Vectors from v0
        float[] v0v1 = {v1[0] - v0[0], v1[1] - v0[1], v1[2] - v0[2]};
        float[] v0v2 = {v2[0] - v0[0], v2[1] - v0[1], v2[2] - v0[2]};
        float[] v0p = {p[0] - v0[0], p[1] - v0[1], p[2] - v0[2]};
        
        // Compute dot products
        float d00 = dot(v0v1, v0v1);
        float d01 = dot(v0v1, v0v2);
        float d11 = dot(v0v2, v0v2);
        float d20 = dot(v0p, v0v1);
        float d21 = dot(v0p, v0v2);
        
        // Compute barycentric coordinates
        float denom = d00 * d11 - d01 * d01;
        if (Math.abs(denom) < EPSILON) {
            // Degenerate triangle
            return new float[]{0.333f, 0.333f, 0.333f};
        }
        
        float v = (d11 * d20 - d01 * d21) / denom;
        float w = (d00 * d21 - d01 * d20) / denom;
        float u = 1.0f - v - w;
        
        return new float[]{u, v, w};
    }
    
    /**
     * Inject normal map with tangent space
     */
    public VoxelAttribute injectNormalMap(Voxel voxel, float[] v0, float[] v1, float[] v2,
                                         float[] n0, float[] n1, float[] n2,
                                         float[] tangent, float[] bitangent) {
        VoxelAttribute attribute = new VoxelAttribute();
        
        // Get voxel position
        float[] point = voxel.getPosition();
        
        // Compute barycentric coordinates
        float[] bary = computeBarycentricCoordinates(point, v0, v1, v2);
        
        // Interpolate normals
        float[] normal = new float[3];
        for (int i = 0; i < 3; i++) {
            normal[i] = bary[0] * n0[i] + bary[1] * n1[i] + bary[2] * n2[i];
        }
        
        // Normalize
        normalize(normal);
        attribute.setNormal(normal);
        
        return attribute;
    }
    
    /**
     * Map texture coordinates to atlas
     */
    public float[] mapToAtlas(float[] uv, String textureName, TextureAtlas atlas) {
        TextureAtlas.TextureRegion region = atlas.getRegion(textureName);
        if (region == null) {
            return uv;
        }
        
        float[] atlasUV = new float[2];
        atlasUV[0] = (region.x + uv[0] * region.width) / (float) atlas.getWidth();
        atlasUV[1] = (region.y + uv[1] * region.height) / (float) atlas.getHeight();
        
        return atlasUV;
    }
    
    /**
     * Compress attribute to compact format
     */
    public byte[] compressAttribute(VoxelAttribute attribute) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        
        // Pack color (4 bytes - RGBA8)
        if (attribute.getColor() != null) {
            float[] color = attribute.getColor();
            buffer.put((byte) (color[0] * 255));
            buffer.put((byte) (color[1] * 255));
            buffer.put((byte) (color[2] * 255));
            buffer.put((byte) (color[3] * 255));
        } else {
            buffer.putInt(0xFFFFFFFF);
        }
        
        // Pack normal (3 bytes - 10-10-10-2 format approximation)
        if (attribute.getNormal() != null) {
            float[] normal = attribute.getNormal();
            buffer.put((byte) ((normal[0] + 1.0f) * 127.5f));
            buffer.put((byte) ((normal[1] + 1.0f) * 127.5f));
            buffer.put((byte) ((normal[2] + 1.0f) * 127.5f));
        } else {
            buffer.put((byte) 127);
            buffer.put((byte) 127);
            buffer.put((byte) 255);
        }
        
        // Pack roughness and metallic (2 bytes)
        buffer.put((byte) (attribute.getRoughness() * 255));
        buffer.put((byte) (attribute.getMetallic() * 255));
        
        // Pack texture coords (4 bytes - 16-bit per component)
        if (attribute.getTextureCoords() != null) {
            float[] uv = attribute.getTextureCoords();
            buffer.putShort((short) (uv[0] * 65535));
            buffer.putShort((short) (uv[1] * 65535));
        } else {
            buffer.putInt(0);
        }
        
        // Padding
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        
        return buffer.array();
    }
    
    /**
     * Decompress attribute from compact format
     */
    public VoxelAttribute decompressAttribute(byte[] compressed) {
        ByteBuffer buffer = ByteBuffer.wrap(compressed);
        VoxelAttribute attribute = new VoxelAttribute();
        
        // Unpack color
        float[] color = new float[4];
        color[0] = (buffer.get() & 0xFF) / 255.0f;
        color[1] = (buffer.get() & 0xFF) / 255.0f;
        color[2] = (buffer.get() & 0xFF) / 255.0f;
        color[3] = (buffer.get() & 0xFF) / 255.0f;
        attribute.setColor(color);
        
        // Unpack normal
        float[] normal = new float[3];
        normal[0] = ((buffer.get() & 0xFF) / 127.5f) - 1.0f;
        normal[1] = ((buffer.get() & 0xFF) / 127.5f) - 1.0f;
        normal[2] = ((buffer.get() & 0xFF) / 127.5f) - 1.0f;
        normalize(normal);
        attribute.setNormal(normal);
        
        // Unpack roughness and metallic
        attribute.setRoughness((buffer.get() & 0xFF) / 255.0f);
        attribute.setMetallic((buffer.get() & 0xFF) / 255.0f);
        
        // Unpack texture coords
        float[] uv = new float[2];
        uv[0] = (buffer.getShort() & 0xFFFF) / 65535.0f;
        uv[1] = (buffer.getShort() & 0xFFFF) / 65535.0f;
        attribute.setTextureCoords(uv);
        
        return attribute;
    }
    
    /**
     * Batch inject attributes for a mesh
     */
    public AttributeResult injectMeshAttributes(TriangleMesh mesh, VoxelizationResult voxelResult) {
        AttributeResult result = new AttributeResult();
        
        // Get mesh vertex colors if available
        float[][] vertexColors = mesh.getVertexColors();
        
        // Process each voxel
        for (Voxel voxel : voxelResult.getVoxels()) {
            VoxelAttribute attribute = new VoxelAttribute();
            
            // Simple color interpolation if vertex colors exist
            if (vertexColors != null && vertexColors.length > 0) {
                // Average color from all vertices (simplified)
                float[] avgColor = new float[4];
                for (float[] color : vertexColors) {
                    for (int i = 0; i < Math.min(color.length, 4); i++) {
                        avgColor[i] += color[i];
                    }
                }
                for (int i = 0; i < 4; i++) {
                    avgColor[i] /= vertexColors.length;
                }
                attribute.setColor(avgColor);
            }
            
            result.addAttribute(voxel, attribute);
        }
        
        return result;
    }
    
    /**
     * Generate contour data for a voxel
     */
    public ContourData generateContour(Voxel voxel, float[] v0, float[] v1, float[] v2) {
        // Compute triangle normal
        float[] e1 = {v1[0] - v0[0], v1[1] - v0[1], v1[2] - v0[2]};
        float[] e2 = {v2[0] - v0[0], v2[1] - v0[1], v2[2] - v0[2]};
        float[] normal = cross(e1, e2);
        normalize(normal);
        
        // Compute distance from voxel center to triangle plane
        float[] point = voxel.getPosition();
        float[] toPoint = {point[0] - v0[0], point[1] - v0[1], point[2] - v0[2]};
        float distance = Math.abs(dot(normal, toPoint));
        
        return new ContourData(normal[0], normal[1], normal[2], distance);
    }
    
    // Helper methods
    
    private float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
    
    private float[] cross(float[] a, float[] b) {
        return new float[]{
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        };
    }
    
    private void normalize(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len > EPSILON) {
            v[0] /= len;
            v[1] /= len;
            v[2] /= len;
        }
    }
}