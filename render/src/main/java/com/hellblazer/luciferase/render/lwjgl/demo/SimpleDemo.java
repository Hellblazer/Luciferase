package com.hellblazer.luciferase.render.lwjgl.demo;

import com.hellblazer.luciferase.render.lwjgl.*;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL41.*;

/**
 * Simple LWJGL demo - pure OpenGL, no WebGPU.
 */
public class SimpleDemo extends LWJGLRenderer {
    
    private Shader shader;
    private Mesh cube;
    private Matrix4f projection;
    private Matrix4f view;
    private Matrix4f model;
    private float rotation = 0;
    
    public SimpleDemo() {
        super(1024, 768, "LWJGL Simple Demo");
    }
    
    @Override
    public void init() {
        super.init();
        
        try {
            // Load shaders from resources
            String vertexShader = loadShaderFromResource("/shaders/demo/simple.vert");
            String fragmentShader = loadShaderFromResource("/shaders/demo/simple.frag");
            shader = new Shader(vertexShader, fragmentShader);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load shaders", e);
        }
        
        // Create cube mesh
        float[] vertices = {
            // Front face
            -0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f, 0.0f, 0.0f,
             0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f, 1.0f, 0.0f,
             0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f, 1.0f, 1.0f,
            -0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f, 0.0f, 1.0f,
            // Back face
            -0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f, 0.0f, 0.0f,
             0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f, 1.0f, 0.0f,
             0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f, 1.0f, 1.0f,
            -0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f, 0.0f, 1.0f,
        };
        
        int[] indices = {
            0, 1, 2, 2, 3, 0,  // Front
            4, 5, 6, 6, 7, 4,  // Back
        };
        
        cube = new Mesh(vertices, indices);
        
        // Setup matrices
        projection = new Matrix4f().perspective((float)Math.toRadians(45.0f), 
                                                1024.0f / 768.0f, 0.1f, 100.0f);
        view = new Matrix4f().lookAt(0, 0, 3, 0, 0, 0, 0, 1, 0);
        model = new Matrix4f();
    }
    
    @Override
    protected void render() {
        // Update rotation
        rotation += 0.01f;
        model.identity().rotateY(rotation).rotateX(rotation * 0.5f);
        
        // Calculate MVP matrix
        Matrix4f mvp = new Matrix4f();
        projection.mul(view, mvp);
        mvp.mul(model);
        
        // Use shader and set uniforms
        shader.use();
        shader.setMat4("uMVP", mvp.get(new float[16]));
        
        // Render cube
        cube.render();
    }
    
    @Override
    public void cleanup() {
        if (shader != null) shader.cleanup();
        if (cube != null) cube.cleanup();
        super.cleanup();
    }
    
    public static void main(String[] args) {
        new SimpleDemo().run();
    }
    
    /**
     * Load shader source code from a resource file.
     * 
     * @param resourcePath Path to the shader resource
     * @return Shader source code as a string
     * @throws IOException if the resource cannot be read
     */
    private String loadShaderFromResource(String resourcePath) throws IOException {
        try (var stream = getClass().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("Shader resource not found: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}