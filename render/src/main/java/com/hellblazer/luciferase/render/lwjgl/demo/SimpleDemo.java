package com.hellblazer.luciferase.render.lwjgl.demo;

import com.hellblazer.luciferase.render.lwjgl.*;
import org.joml.Matrix4f;

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
        
        // Create shader
        String vertexShader = """
            #version 410 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aTexCoord;
            
            uniform mat4 uMVP;
            
            out vec3 FragPos;
            out vec3 Normal;
            
            void main() {
                FragPos = aPos;
                Normal = aNormal;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
            """;
            
        String fragmentShader = """
            #version 410 core
            in vec3 FragPos;
            in vec3 Normal;
            
            out vec4 FragColor;
            
            void main() {
                vec3 lightDir = normalize(vec3(1, 1, 1));
                float diff = max(dot(normalize(Normal), lightDir), 0.0);
                vec3 color = vec3(0.5, 0.7, 1.0) * (0.3 + 0.7 * diff);
                FragColor = vec4(color, 1.0);
            }
            """;
        
        shader = new Shader(vertexShader, fragmentShader);
        
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
}