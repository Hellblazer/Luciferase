package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import com.hellblazer.luciferase.render.voxel.esvo.ESVONode;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Integrated beam-optimized GPU traversal for ESVO.
 * Combines beam optimization with persistent thread management.
 */
public class ESVOBeamGPUTraversal {
    
    // Shader programs
    private int beamShaderProgram;
    private int beamComputeShader;
    
    // Buffers
    private int nodeBufferSSBO;
    private int beamInputSSBO;
    private int resultBufferSSBO;
    
    // Uniforms
    private int octreeMinLoc;
    private int octreeMaxLoc;
    private int rootNodeLoc;
    
    // Managers
    private final BeamOptimizer beamOptimizer;
    private final PersistentThreadManager threadManager;
    
    // Configuration
    private final int maxBeams;
    private final int raysPerBeam;
    
    // Statistics
    private int totalRaysProcessed;
    private int totalBeamsProcessed;
    private float averageCoherence;
    
    public ESVOBeamGPUTraversal(int maxBeams, int raysPerBeam) {
        this.maxBeams = maxBeams;
        this.raysPerBeam = raysPerBeam;
        this.beamOptimizer = new BeamOptimizer();
        this.threadManager = new PersistentThreadManager();
        
        initialize();
    }
    
    private void initialize() {
        compileShaders();
        createBuffers();
        getUniformLocations();
    }
    
    private void compileShaders() {
        try {
            // Load beam traversal shader
            String shaderSource = Files.readString(
                Path.of("src/main/resources/shaders/esvo/beam_traversal.comp")
            );
            
            // Compile shader
            beamComputeShader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
            GL20.glShaderSource(beamComputeShader, shaderSource);
            GL20.glCompileShader(beamComputeShader);
            
            // Check compilation
            if (GL20.glGetShaderi(beamComputeShader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetShaderInfoLog(beamComputeShader);
                throw new RuntimeException("Beam shader compilation failed: " + log);
            }
            
            // Create program
            beamShaderProgram = GL20.glCreateProgram();
            GL20.glAttachShader(beamShaderProgram, beamComputeShader);
            GL20.glLinkProgram(beamShaderProgram);
            
            // Check linking
            if (GL20.glGetProgrami(beamShaderProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(beamShaderProgram);
                throw new RuntimeException("Beam program linking failed: " + log);
            }
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to load beam shader", e);
        }
    }
    
    private void createBuffers() {
        // Node buffer
        nodeBufferSSBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, nodeBufferSSBO);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, nodeBufferSSBO);
        
        // Beam input buffer
        beamInputSSBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, beamInputSSBO);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, beamInputSSBO);
        
        // Result buffer
        resultBufferSSBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, resultBufferSSBO);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, resultBufferSSBO);
    }
    
    private void getUniformLocations() {
        GL20.glUseProgram(beamShaderProgram);
        octreeMinLoc = GL20.glGetUniformLocation(beamShaderProgram, "octreeMin");
        octreeMaxLoc = GL20.glGetUniformLocation(beamShaderProgram, "octreeMax");
        rootNodeLoc = GL20.glGetUniformLocation(beamShaderProgram, "rootNode");
    }
    
    /**
     * Process rays using beam optimization
     */
    public float[] processRays(float[][] origins, float[][] directions, List<ESVONode> nodes) {
        // Upload nodes
        uploadNodes(nodes);
        
        // Create beams from rays
        List<BeamOptimizer.Beam> beams = createBeams(origins, directions);
        
        // Process statistics
        updateStatistics(beams);
        
        // Upload beams to GPU
        uploadBeams(beams);
        
        // Execute beam traversal
        executeBeamTraversal(beams.size());
        
        // Read results
        return readResults(origins.length);
    }
    
    private List<BeamOptimizer.Beam> createBeams(float[][] origins, float[][] directions) {
        List<BeamOptimizer.Beam> beams = new ArrayList<>();
        
        // Group rays into coherent beams
        for (int i = 0; i < origins.length; i += raysPerBeam) {
            int count = Math.min(raysPerBeam, origins.length - i);
            
            float[][] beamOrigins = new float[count][];
            float[][] beamDirections = new float[count][];
            
            for (int j = 0; j < count; j++) {
                beamOrigins[j] = origins[i + j];
                beamDirections[j] = directions[i + j];
            }
            
            BeamOptimizer.Beam beam = beamOptimizer.createBeam(beamOrigins, beamDirections);
            
            // Split non-coherent beams if needed
            if (!beam.isCoherent() && count > 4) {
                BeamOptimizer.Beam[] subBeams = beam.split(2, 2);
                for (BeamOptimizer.Beam subBeam : subBeams) {
                    if (subBeam.getRayCount() > 0) {
                        beams.add(subBeam);
                    }
                }
            } else {
                beams.add(beam);
            }
        }
        
        return beams;
    }
    
    private void uploadNodes(List<ESVONode> nodes) {
        ByteBuffer buffer = MemoryUtil.memAlloc(nodes.size() * 8);
        
        for (ESVONode node : nodes) {
            byte[] data = node.toBytes();
            buffer.put(data);
        }
        buffer.flip();
        
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, nodeBufferSSBO);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, buffer, GL15.GL_STATIC_DRAW);
        
        MemoryUtil.memFree(buffer);
    }
    
    private void uploadBeams(List<BeamOptimizer.Beam> beams) {
        // Each beam: origin_min(3) + origin_max(3) + dir_min(3) + dir_max(3) + ray_mask + ray_count
        int floatsPerBeam = 14;
        FloatBuffer buffer = MemoryUtil.memAllocFloat(beams.size() * floatsPerBeam);
        
        for (BeamOptimizer.Beam beam : beams) {
            BeamOptimizer.BeamFrustum frustum = beam.getFrustum();
            
            // Origin bounds
            float[] nearMin = frustum.getNearMin();
            float[] nearMax = frustum.getNearMax();
            buffer.put(nearMin[0]).put(nearMin[1]).put(nearMin[2]);
            buffer.put(nearMax[0]).put(nearMax[1]).put(nearMax[2]);
            
            // Direction bounds (simplified - would compute actual bounds)
            buffer.put(0).put(0).put(1); // dir_min
            buffer.put(0).put(0).put(1); // dir_max
            
            // Ray mask and count
            int rayMask = (1 << beam.getRayCount()) - 1;
            buffer.put(Float.intBitsToFloat(rayMask));
            buffer.put(Float.intBitsToFloat(beam.getRayCount()));
        }
        buffer.flip();
        
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, beamInputSSBO);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, buffer, GL15.GL_STATIC_DRAW);
        
        MemoryUtil.memFree(buffer);
    }
    
    private void executeBeamTraversal(int beamCount) {
        GL20.glUseProgram(beamShaderProgram);
        
        // Set uniforms
        GL20.glUniform3f(octreeMinLoc, -1, -1, -1);
        GL20.glUniform3f(octreeMaxLoc, 1, 1, 1);
        GL20.glUniform1i(rootNodeLoc, 0);
        
        // Dispatch with persistent threads
        int workGroups = Math.min(beamCount, 64); // Limit work groups
        GL43.glDispatchCompute(workGroups, 1, 1);
        
        // Memory barrier
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    
    private float[] readResults(int rayCount) {
        float[] results = new float[rayCount * 4];
        
        FloatBuffer buffer = MemoryUtil.memAllocFloat(results.length);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, resultBufferSSBO);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, buffer);
        buffer.get(results);
        MemoryUtil.memFree(buffer);
        
        return results;
    }
    
    private void updateStatistics(List<BeamOptimizer.Beam> beams) {
        totalBeamsProcessed += beams.size();
        
        float coherenceSum = 0;
        int raySum = 0;
        
        for (BeamOptimizer.Beam beam : beams) {
            coherenceSum += beam.getCoherenceMetric() * beam.getRayCount();
            raySum += beam.getRayCount();
        }
        
        totalRaysProcessed += raySum;
        
        if (raySum > 0) {
            float batchCoherence = coherenceSum / raySum;
            // Running average
            averageCoherence = (averageCoherence * (totalRaysProcessed - raySum) + coherenceSum) / totalRaysProcessed;
        }
    }
    
    /**
     * Set octree bounds
     */
    public void setOctreeBounds(float minX, float minY, float minZ,
                                float maxX, float maxY, float maxZ) {
        GL20.glUseProgram(beamShaderProgram);
        GL20.glUniform3f(octreeMinLoc, minX, minY, minZ);
        GL20.glUniform3f(octreeMaxLoc, maxX, maxY, maxZ);
    }
    
    /**
     * Get performance statistics
     */
    public Statistics getStatistics() {
        return new Statistics(totalRaysProcessed, totalBeamsProcessed, averageCoherence);
    }
    
    /**
     * Clean up resources
     */
    public void dispose() {
        if (beamShaderProgram != 0) {
            GL20.glDeleteProgram(beamShaderProgram);
        }
        if (beamComputeShader != 0) {
            GL20.glDeleteShader(beamComputeShader);
        }
        if (nodeBufferSSBO != 0) {
            GL15.glDeleteBuffers(nodeBufferSSBO);
        }
        if (beamInputSSBO != 0) {
            GL15.glDeleteBuffers(beamInputSSBO);
        }
        if (resultBufferSSBO != 0) {
            GL15.glDeleteBuffers(resultBufferSSBO);
        }
    }
    
    /**
     * Performance statistics
     */
    public static class Statistics {
        public final int totalRays;
        public final int totalBeams;
        public final float averageCoherence;
        
        public Statistics(int totalRays, int totalBeams, float averageCoherence) {
            this.totalRays = totalRays;
            this.totalBeams = totalBeams;
            this.averageCoherence = averageCoherence;
        }
        
        public float getBeamEfficiency() {
            if (totalBeams == 0) return 0;
            return (float) totalRays / totalBeams;
        }
    }
}