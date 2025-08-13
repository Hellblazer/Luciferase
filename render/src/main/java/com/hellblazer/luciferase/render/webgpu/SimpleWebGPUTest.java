package com.hellblazer.luciferase.render.webgpu;

/**
 * Simplified test to debug WebGPU initialization
 */
public class SimpleWebGPUTest {
    
    public static void main(String[] args) {
        System.out.println("=== Simple WebGPU Test ===");
        System.out.println("1. Creating WebGPUContext...");
        
        try {
            WebGPUContext context = new WebGPUContext(800, 600, "Test Window");
            System.out.println("2. Context created. Initializing...");
            
            context.initialize();
            System.out.println("3. Context initialized successfully!");
            
            System.out.println("4. Device: " + context.getDevice());
            System.out.println("5. Queue: " + context.getQueue());
            
            System.out.println("6. Cleaning up...");
            context.cleanup();
            System.out.println("7. Done!");
            
        } catch (Exception e) {
            System.err.println("Error: " + e);
            e.printStackTrace();
        }
        
        System.exit(0);
    }
}