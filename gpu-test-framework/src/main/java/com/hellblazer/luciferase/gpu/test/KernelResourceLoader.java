package com.hellblazer.luciferase.gpu.test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for loading GPU kernel and shader code from resources.
 * Provides caching to avoid repeated I/O operations for test kernels.
 */
public class KernelResourceLoader {
    
    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    
    /**
     * Load a kernel/shader from the classpath resources.
     * 
     * @param resourcePath the path to the resource (e.g., "kernels/vector_add.cl")
     * @return the kernel source code as a String
     * @throws RuntimeException if the resource cannot be loaded
     */
    public static String loadKernel(String resourcePath) {
        return cache.computeIfAbsent(resourcePath, KernelResourceLoader::doLoadKernel);
    }
    
    private static String doLoadKernel(String resourcePath) {
        try (InputStream is = KernelResourceLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Kernel resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load kernel resource: " + resourcePath, e);
        }
    }
    
    /**
     * Clear the kernel cache. Useful for testing scenarios.
     */
    public static void clearCache() {
        cache.clear();
    }
}