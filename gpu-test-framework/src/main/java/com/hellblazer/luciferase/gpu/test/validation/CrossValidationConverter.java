package com.hellblazer.luciferase.gpu.test.validation;

import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts between OpenGL compute shader data formats and OpenCL kernel formats
 * for cross-validation testing of ESVO algorithms.
 */
public class CrossValidationConverter {
    
    /**
     * OpenGL-compatible octree node format (matches GLSL shader structs).
     */
    public static class GLOctreeNode {
        public int childPointer;      // Combined child pointer and mask
        public int contourIndex;      // Index into contour buffer
        public float density;          // Voxel density value
        public int attributes;         // Material and flags
        
        public static final int SIZE_BYTES = 16;
        
        public ByteBuffer toBuffer() {
            ByteBuffer buffer = BufferUtils.createByteBuffer(SIZE_BYTES);
            buffer.putInt(childPointer);
            buffer.putInt(contourIndex);
            buffer.putFloat(density);
            buffer.putInt(attributes);
            buffer.flip();
            return buffer;
        }
        
        public static GLOctreeNode fromBuffer(ByteBuffer buffer) {
            GLOctreeNode node = new GLOctreeNode();
            node.childPointer = buffer.getInt();
            node.contourIndex = buffer.getInt();
            node.density = buffer.getFloat();
            node.attributes = buffer.getInt();
            return node;
        }
    }
    
    /**
     * OpenGL ray format (vec3 + vec3 + vec2 in GLSL).
     */
    public static class GLRay {
        public float[] origin = new float[3];
        public float[] direction = new float[3];
        public float tMin, tMax;
        
        public static final int SIZE_BYTES = 32;
        
        public ByteBuffer toBuffer() {
            ByteBuffer buffer = BufferUtils.createByteBuffer(SIZE_BYTES);
            for (float o : origin) buffer.putFloat(o);
            for (float d : direction) buffer.putFloat(d);
            buffer.putFloat(tMin);
            buffer.putFloat(tMax);
            buffer.flip();
            return buffer;
        }
    }
    
    /**
     * OpenGL intersection result format.
     */
    public static class GLIntersectionResult {
        public boolean hit;
        public float t;
        public float[] normal = new float[3];
        public int voxelData;
        public int nodeIndex;
        
        public static final int SIZE_BYTES = 28;
        
        public static GLIntersectionResult fromBuffer(ByteBuffer buffer) {
            GLIntersectionResult result = new GLIntersectionResult();
            result.hit = buffer.getInt() != 0;
            result.t = buffer.getFloat();
            for (int i = 0; i < 3; i++) {
                result.normal[i] = buffer.getFloat();
            }
            result.voxelData = buffer.getInt();
            result.nodeIndex = buffer.getInt();
            return result;
        }
    }
    
    /**
     * Converts OpenGL octree nodes to OpenCL format.
     */
    public static ESVODataStructures.OctreeNode[] convertGLToCLNodes(GLOctreeNode[] glNodes) {
        ESVODataStructures.OctreeNode[] clNodes = new ESVODataStructures.OctreeNode[glNodes.length];
        
        for (int i = 0; i < glNodes.length; i++) {
            clNodes[i] = new ESVODataStructures.OctreeNode();
            
            // Extract child mask and pointer from combined field
            clNodes[i].childDescriptor = glNodes[i].childPointer & 0xFF;  // Lower 8 bits are mask
            int childBase = (glNodes[i].childPointer >> 8) * 8;          // Upper bits are base index
            
            // Map contour pointer
            clNodes[i].contourPointer = glNodes[i].contourIndex;
            
            // Map density to min/max range
            clNodes[i].minValue = 0.0f;
            clNodes[i].maxValue = glNodes[i].density;
            
            // Copy attributes
            clNodes[i].attributes = glNodes[i].attributes;
        }
        
        return clNodes;
    }
    
    /**
     * Converts OpenCL octree nodes to OpenGL format.
     */
    public static GLOctreeNode[] convertCLToGLNodes(ESVODataStructures.OctreeNode[] clNodes) {
        GLOctreeNode[] glNodes = new GLOctreeNode[clNodes.length];
        
        for (int i = 0; i < clNodes.length; i++) {
            glNodes[i] = new GLOctreeNode();
            
            // Combine child descriptor into pointer field
            int childBase = i * 8;  // Simple indexing scheme
            glNodes[i].childPointer = (childBase << 8) | (clNodes[i].childDescriptor & 0xFF);
            
            // Map contour pointer
            glNodes[i].contourIndex = clNodes[i].contourPointer;
            
            // Use max value as density
            glNodes[i].density = clNodes[i].maxValue;
            
            // Copy attributes
            glNodes[i].attributes = clNodes[i].attributes;
        }
        
        return glNodes;
    }
    
    /**
     * Converts OpenGL rays to OpenCL format.
     */
    public static ESVODataStructures.Ray[] convertGLToCLRays(GLRay[] glRays) {
        ESVODataStructures.Ray[] clRays = new ESVODataStructures.Ray[glRays.length];
        
        for (int i = 0; i < glRays.length; i++) {
            clRays[i] = new ESVODataStructures.Ray();
            clRays[i].originX = glRays[i].origin[0];
            clRays[i].originY = glRays[i].origin[1];
            clRays[i].originZ = glRays[i].origin[2];
            clRays[i].directionX = glRays[i].direction[0];
            clRays[i].directionY = glRays[i].direction[1];
            clRays[i].directionZ = glRays[i].direction[2];
            clRays[i].tMin = glRays[i].tMin;
            clRays[i].tMax = glRays[i].tMax;
        }
        
        return clRays;
    }
    
    /**
     * Converts OpenCL rays to OpenGL format.
     */
    public static GLRay[] convertCLToGLRays(ESVODataStructures.Ray[] clRays) {
        GLRay[] glRays = new GLRay[clRays.length];
        
        for (int i = 0; i < clRays.length; i++) {
            glRays[i] = new GLRay();
            glRays[i].origin[0] = clRays[i].originX;
            glRays[i].origin[1] = clRays[i].originY;
            glRays[i].origin[2] = clRays[i].originZ;
            glRays[i].direction[0] = clRays[i].directionX;
            glRays[i].direction[1] = clRays[i].directionY;
            glRays[i].direction[2] = clRays[i].directionZ;
            glRays[i].tMin = clRays[i].tMin;
            glRays[i].tMax = clRays[i].tMax;
        }
        
        return glRays;
    }
    
    /**
     * Converts OpenCL intersection results to OpenGL format.
     */
    public static GLIntersectionResult[] convertCLToGLResults(
            ESVODataStructures.IntersectionResult[] clResults) {
        
        GLIntersectionResult[] glResults = new GLIntersectionResult[clResults.length];
        
        for (int i = 0; i < clResults.length; i++) {
            glResults[i] = new GLIntersectionResult();
            glResults[i].hit = clResults[i].hit != 0;
            glResults[i].t = clResults[i].t;
            glResults[i].normal[0] = clResults[i].normalX;
            glResults[i].normal[1] = clResults[i].normalY;
            glResults[i].normal[2] = clResults[i].normalZ;
            glResults[i].voxelData = clResults[i].voxelValue;
            glResults[i].nodeIndex = clResults[i].nodeIndex;
        }
        
        return glResults;
    }
    
    /**
     * Creates a buffer suitable for OpenGL SSBO from OpenCL nodes.
     */
    public static ByteBuffer createGLBufferFromCLNodes(ESVODataStructures.OctreeNode[] clNodes) {
        GLOctreeNode[] glNodes = convertCLToGLNodes(clNodes);
        ByteBuffer buffer = BufferUtils.createByteBuffer(glNodes.length * GLOctreeNode.SIZE_BYTES);
        
        for (GLOctreeNode node : glNodes) {
            buffer.put(node.toBuffer());
        }
        buffer.flip();
        
        return buffer;
    }
    
    /**
     * Creates a buffer suitable for OpenGL SSBO from OpenCL rays.
     */
    public static ByteBuffer createGLBufferFromCLRays(ESVODataStructures.Ray[] clRays) {
        GLRay[] glRays = convertCLToGLRays(clRays);
        ByteBuffer buffer = BufferUtils.createByteBuffer(glRays.length * GLRay.SIZE_BYTES);
        
        for (GLRay ray : glRays) {
            buffer.put(ray.toBuffer());
        }
        buffer.flip();
        
        return buffer;
    }
    
    /**
     * Validates that OpenGL and OpenCL results match within tolerance.
     */
    public static ValidationResult validateResults(
            GLIntersectionResult[] glResults,
            ESVODataStructures.IntersectionResult[] clResults,
            float tolerance) {
        
        ValidationResult validation = new ValidationResult();
        validation.totalTests = Math.min(glResults.length, clResults.length);
        
        for (int i = 0; i < validation.totalTests; i++) {
            GLIntersectionResult gl = glResults[i];
            ESVODataStructures.IntersectionResult cl = clResults[i];
            
            // Check hit/miss match
            boolean hitMatch = (gl.hit == (cl.hit != 0));
            if (!hitMatch) {
                validation.addMismatch(i, "Hit status mismatch");
                continue;
            }
            
            // If both hit, check details
            if (gl.hit) {
                // Check t parameter
                if (Math.abs(gl.t - cl.t) > tolerance) {
                    validation.addMismatch(i, String.format(
                        "T parameter mismatch: GL=%.6f, CL=%.6f", gl.t, cl.t));
                }
                
                // Check normal
                float normalDiff = 0.0f;
                normalDiff += Math.abs(gl.normal[0] - cl.normalX);
                normalDiff += Math.abs(gl.normal[1] - cl.normalY);
                normalDiff += Math.abs(gl.normal[2] - cl.normalZ);
                
                if (normalDiff > tolerance * 3) {
                    validation.addMismatch(i, String.format(
                        "Normal mismatch: GL=(%.3f,%.3f,%.3f), CL=(%.3f,%.3f,%.3f)",
                        gl.normal[0], gl.normal[1], gl.normal[2],
                        cl.normalX, cl.normalY, cl.normalZ));
                }
                
                // Check voxel data
                if (gl.voxelData != cl.voxelValue) {
                    validation.addMismatch(i, String.format(
                        "Voxel data mismatch: GL=%d, CL=%d", gl.voxelData, cl.voxelValue));
                }
            }
            
            validation.passedTests++;
        }
        
        return validation;
    }
    
    /**
     * Result of cross-validation between OpenGL and OpenCL implementations.
     */
    public static class ValidationResult {
        public int totalTests;
        public int passedTests;
        public List<Mismatch> mismatches = new ArrayList<>();
        
        public void addMismatch(int index, String reason) {
            mismatches.add(new Mismatch(index, reason));
        }
        
        public boolean isValid() {
            return mismatches.isEmpty();
        }
        
        public double getPassRate() {
            return totalTests > 0 ? (double)passedTests / totalTests : 0.0;
        }
        
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Validation: %d/%d passed (%.1f%%)\n",
                passedTests, totalTests, getPassRate() * 100));
            
            if (!mismatches.isEmpty()) {
                sb.append("Mismatches:\n");
                for (Mismatch m : mismatches) {
                    sb.append(String.format("  [%d] %s\n", m.index, m.reason));
                }
            }
            
            return sb.toString();
        }
        
        public static class Mismatch {
            public final int index;
            public final String reason;
            
            public Mismatch(int index, String reason) {
                this.index = index;
                this.reason = reason;
            }
        }
    }
    
    /**
     * Generates test octree data that can be used by both implementations.
     */
    public static class TestDataGenerator {
        
        public static ESVODataStructures.OctreeNode[] generateTestOctree(
                int depth, float fillProbability) {
            
            int maxNodes = calculateMaxNodes(depth);
            List<ESVODataStructures.OctreeNode> nodes = new ArrayList<>();
            
            // Create root
            ESVODataStructures.OctreeNode root = new ESVODataStructures.OctreeNode();
            root.childDescriptor = 0xFF;  // All children exist initially
            nodes.add(root);
            
            // Build tree level by level
            buildLevel(nodes, 0, 1, depth, fillProbability);
            
            return nodes.toArray(new ESVODataStructures.OctreeNode[0]);
        }
        
        private static void buildLevel(List<ESVODataStructures.OctreeNode> nodes,
                                      int parentStart, int parentCount,
                                      int remainingDepth, float fillProbability) {
            
            if (remainingDepth <= 0) return;
            
            int childStart = nodes.size();
            int childCount = 0;
            
            // Process each parent
            for (int p = 0; p < parentCount; p++) {
                ESVODataStructures.OctreeNode parent = nodes.get(parentStart + p);
                
                // Create children based on parent's descriptor
                for (int c = 0; c < 8; c++) {
                    if ((parent.childDescriptor & (1 << c)) != 0) {
                        ESVODataStructures.OctreeNode child = new ESVODataStructures.OctreeNode();
                        
                        // Decide if this is a leaf or internal node
                        if (remainingDepth == 1 || Math.random() > 0.7) {
                            // Leaf node
                            child.childDescriptor = 0;
                            child.attributes = Math.random() < fillProbability ? 1 : 0;
                            child.minValue = 0.0f;
                            child.maxValue = child.attributes;
                        } else {
                            // Internal node with random children
                            child.childDescriptor = (int)(Math.random() * 256);
                        }
                        
                        nodes.add(child);
                        childCount++;
                    }
                }
            }
            
            // Recurse to next level
            if (childCount > 0) {
                buildLevel(nodes, childStart, childCount, remainingDepth - 1, fillProbability);
            }
        }
        
        private static int calculateMaxNodes(int depth) {
            int total = 0;
            int levelNodes = 1;
            for (int d = 0; d <= depth; d++) {
                total += levelNodes;
                levelNodes *= 8;
            }
            return total;
        }
        
        public static ESVODataStructures.Ray[] generateTestRays(int count, float spread) {
            ESVODataStructures.Ray[] rays = new ESVODataStructures.Ray[count];
            
            for (int i = 0; i < count; i++) {
                rays[i] = new ESVODataStructures.Ray();
                
                // Generate ray from random point on sphere
                double theta = Math.random() * 2 * Math.PI;
                double phi = Math.acos(2 * Math.random() - 1);
                double r = 2.0 + Math.random();
                
                rays[i].originX = (float)(r * Math.sin(phi) * Math.cos(theta));
                rays[i].originY = (float)(r * Math.sin(phi) * Math.sin(theta));
                rays[i].originZ = (float)(r * Math.cos(phi));
                
                // Point toward center with some spread
                float targetX = 0.5f + (float)(Math.random() - 0.5) * spread;
                float targetY = 0.5f + (float)(Math.random() - 0.5) * spread;
                float targetZ = 0.5f + (float)(Math.random() - 0.5) * spread;
                
                float dx = targetX - rays[i].originX;
                float dy = targetY - rays[i].originY;
                float dz = targetZ - rays[i].originZ;
                float len = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
                
                rays[i].directionX = dx / len;
                rays[i].directionY = dy / len;
                rays[i].directionZ = dz / len;
                rays[i].tMin = 0.0f;
                rays[i].tMax = 100.0f;
            }
            
            return rays;
        }
    }
}