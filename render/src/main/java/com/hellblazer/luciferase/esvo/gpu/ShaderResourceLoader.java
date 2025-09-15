package com.hellblazer.luciferase.esvo.gpu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for loading GPU shader and kernel code from resources.
 * Provides caching to avoid repeated I/O operations.
 */
public class ShaderResourceLoader {
    
    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    
    /**
     * Load a shader/kernel from the classpath resources.
     * 
     * @param resourcePath the path to the resource (e.g., "kernels/esvo_ray_traversal.cl")
     * @return the shader source code as a String
     * @throws RuntimeException if the resource cannot be loaded
     */
    public static String loadShader(String resourcePath) {
        return cache.computeIfAbsent(resourcePath, ShaderResourceLoader::doLoadShader);
    }
    
    private static String doLoadShader(String resourcePath) {
        try (InputStream is = ShaderResourceLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Shader resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load shader resource: " + resourcePath, e);
        }
    }
    
    /**
     * Clear the shader cache. Useful for testing or hot-reloading scenarios.
     */
    public static void clearCache() {
        cache.clear();
    }
}