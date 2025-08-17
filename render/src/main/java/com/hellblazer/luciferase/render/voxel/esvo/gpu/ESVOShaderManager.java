/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Manages ESVO compute shaders with support for variants, hot-reload, and dynamic compilation.
 * Provides a sophisticated shader system that can adapt to different rendering requirements
 * through preprocessor-based shader variants.
 */
public class ESVOShaderManager {
    private static final Logger log = LoggerFactory.getLogger(ESVOShaderManager.class);
    
    private static final Pattern INCLUDE_PATTERN = Pattern.compile("#include\\s+\"([^\"]+)\"");
    private static final Pattern VERSION_PATTERN = Pattern.compile("#version\\s+(\\d+)\\s*(\\w*)");
    
    private final ConcurrentMap<String, Integer> compiledPrograms = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> shaderSources = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> shaderDependencies = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastModified = new ConcurrentHashMap<>();
    
    private final String shaderDirectory;
    private final boolean hotReloadEnabled;
    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean shutdown = false;
    
    public ESVOShaderManager(String shaderDirectory, boolean enableHotReload) {
        this.shaderDirectory = shaderDirectory;
        this.hotReloadEnabled = enableHotReload;
        
        loadAllShaders();
        
        if (enableHotReload) {
            initializeHotReload();
        }
    }
    
    public ESVOShaderManager() {
        this("shaders/esvo/", false);
    }
    
    /**
     * Creates a shader variant builder for the specified shader.
     */
    public ShaderVariant createVariant(String shaderName) {
        return new ShaderVariant(shaderName);
    }
    
    /**
     * Gets a compiled shader program by name. Returns 0 if not found.
     */
    public int getProgram(String name) {
        return compiledPrograms.getOrDefault(name, 0);
    }
    
    /**
     * Checks if a shader program exists and is valid.
     */
    public boolean isValidProgram(String name) {
        int program = getProgram(name);
        return program != 0 && GL43.glIsProgram(program);
    }
    
    /**
     * Reloads all shaders from disk.
     */
    public void reloadAll() {
        log.info("Reloading all shaders...");
        
        // Clear caches
        shaderSources.clear();
        shaderDependencies.clear();
        lastModified.clear();
        
        // Delete existing programs
        compiledPrograms.values().forEach(program -> {
            if (GL43.glIsProgram(program)) {
                GL43.glDeleteProgram(program);
            }
        });
        compiledPrograms.clear();
        
        // Reload from disk
        loadAllShaders();
        
        log.info("Shader reload complete. {} shaders loaded.", shaderSources.size());
    }
    
    /**
     * Shuts down the shader manager and releases resources.
     */
    public void shutdown() {
        shutdown = true;
        
        if (watchThread != null) {
            watchThread.interrupt();
        }
        
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.warn("Error closing watch service", e);
            }
        }
        
        // Clean up OpenGL resources
        compiledPrograms.values().forEach(program -> {
            if (GL43.glIsProgram(program)) {
                GL43.glDeleteProgram(program);
            }
        });
        compiledPrograms.clear();
    }
    
    private void loadAllShaders() {
        try {
            Path shaderPath = Paths.get(getClass().getClassLoader().getResource(shaderDirectory).toURI());
            
            Files.walk(shaderPath)
                 .filter(path -> path.toString().endsWith(".comp"))
                 .forEach(this::loadShader);
                 
        } catch (Exception e) {
            log.error("Failed to load shaders from directory: {}", shaderDirectory, e);
        }
    }
    
    private void loadShader(Path shaderFile) {
        try {
            String fileName = shaderFile.getFileName().toString();
            String source = Files.readString(shaderFile);
            
            shaderSources.put(fileName, source);
            lastModified.put(fileName, Files.getLastModifiedTime(shaderFile).toMillis());
            
            // Parse dependencies
            Set<String> dependencies = parseDependencies(source);
            shaderDependencies.put(fileName, dependencies);
            
            log.debug("Loaded shader: {} with {} dependencies", fileName, dependencies.size());
            
        } catch (IOException e) {
            log.error("Failed to load shader: {}", shaderFile, e);
        }
    }
    
    private Set<String> parseDependencies(String source) {
        Set<String> dependencies = new HashSet<>();
        Matcher matcher = INCLUDE_PATTERN.matcher(source);
        
        while (matcher.find()) {
            dependencies.add(matcher.group(1));
        }
        
        return dependencies;
    }
    
    private void initializeHotReload() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path shaderPath = Paths.get(getClass().getClassLoader().getResource(shaderDirectory).toURI());
            
            shaderPath.register(watchService, 
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE);
            
            watchThread = new Thread(this::watchForChanges, "ShaderWatcher");
            watchThread.setDaemon(true);
            watchThread.start();
            
            log.info("Hot-reload enabled for shader directory: {}", shaderDirectory);
            
        } catch (Exception e) {
            log.error("Failed to initialize hot-reload", e);
        }
    }
    
    private void watchForChanges() {
        while (!shutdown) {
            try {
                WatchKey key = watchService.take();
                
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    
                    Path changed = (Path) event.context();
                    String fileName = changed.toString();
                    
                    if (fileName.endsWith(".comp")) {
                        log.info("Shader file changed: {}", fileName);
                        reloadShader(fileName);
                    }
                }
                
                key.reset();
                
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.error("Error in shader watch thread", e);
            }
        }
    }
    
    private void reloadShader(String fileName) {
        try {
            Path shaderPath = Paths.get(getClass().getClassLoader().getResource(shaderDirectory + fileName).toURI());
            loadShader(shaderPath);
            
            // Invalidate compiled programs that use this shader
            compiledPrograms.entrySet().removeIf(entry -> {
                if (entry.getKey().startsWith(fileName)) {
                    GL43.glDeleteProgram(entry.getValue());
                    log.debug("Invalidated program: {}", entry.getKey());
                    return true;
                }
                return false;
            });
            
        } catch (Exception e) {
            log.error("Failed to reload shader: {}", fileName, e);
        }
    }
    
    /**
     * Shader variant builder that supports preprocessor defines and compilation.
     */
    public class ShaderVariant {
        private final String shaderName;
        private final Map<String, String> defines = new LinkedHashMap<>();
        private final Set<String> flags = new HashSet<>();
        
        public ShaderVariant(String shaderName) {
            this.shaderName = shaderName;
        }
        
        /**
         * Adds a preprocessor define flag.
         */
        public ShaderVariant define(String flag) {
            flags.add(flag);
            return this;
        }
        
        /**
         * Adds a preprocessor define with value.
         */
        public ShaderVariant define(String name, String value) {
            defines.put(name, value);
            return this;
        }
        
        /**
         * Adds a preprocessor define with integer value.
         */
        public ShaderVariant define(String name, int value) {
            defines.put(name, String.valueOf(value));
            return this;
        }
        
        /**
         * Adds a preprocessor define with float value.
         */
        public ShaderVariant define(String name, float value) {
            defines.put(name, String.valueOf(value));
            return this;
        }
        
        /**
         * Compiles the shader variant and returns the program ID.
         */
        public int compile() {
            String variantKey = generateVariantKey();
            
            // Check if already compiled
            Integer existing = compiledPrograms.get(variantKey);
            if (existing != null && GL43.glIsProgram(existing)) {
                return existing;
            }
            
            try {
                String source = preprocessShader();
                int program = compileShaderProgram(source);
                
                compiledPrograms.put(variantKey, program);
                log.debug("Compiled shader variant: {}", variantKey);
                
                return program;
                
            } catch (Exception e) {
                log.error("Failed to compile shader variant: {}", variantKey, e);
                return 0;
            }
        }
        
        private String generateVariantKey() {
            StringBuilder key = new StringBuilder(shaderName);
            
            flags.stream().sorted().forEach(flag -> key.append("_").append(flag));
            
            defines.entrySet().stream()
                   .sorted(Map.Entry.comparingByKey())
                   .forEach(entry -> key.append("_").append(entry.getKey()).append("=").append(entry.getValue()));
            
            return key.toString();
        }
        
        private String preprocessShader() {
            String source = shaderSources.get(shaderName);
            if (source == null) {
                throw new IllegalArgumentException("Shader not found: " + shaderName);
            }
            
            StringBuilder processed = new StringBuilder();
            String[] lines = source.split("\\n");
            
            // Find version directive
            boolean versionFound = false;
            for (String line : lines) {
                if (VERSION_PATTERN.matcher(line).matches()) {
                    processed.append(line).append("\\n");
                    
                    // Add defines after version
                    flags.forEach(flag -> processed.append("#define ").append(flag).append("\\n"));
                    defines.forEach((name, value) -> 
                        processed.append("#define ").append(name).append(" ").append(value).append("\\n"));
                    
                    versionFound = true;
                } else if (versionFound) {
                    // Process includes
                    Matcher includeMatcher = INCLUDE_PATTERN.matcher(line);
                    if (includeMatcher.matches()) {
                        String includePath = includeMatcher.group(1);
                        String includeSource = shaderSources.get(includePath);
                        if (includeSource != null) {
                            processed.append(includeSource).append("\\n");
                        } else {
                            log.warn("Include file not found: {}", includePath);
                            processed.append(line).append("\\n");
                        }
                    } else {
                        processed.append(line).append("\\n");
                    }
                }
            }
            
            return processed.toString();
        }
        
        private int compileShaderProgram(String source) {
            int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
            GL43.glShaderSource(shader, source);
            GL43.glCompileShader(shader);
            
            // Check compilation status
            int status = GL43.glGetShaderi(shader, GL43.GL_COMPILE_STATUS);
            if (status == GL43.GL_FALSE) {
                String log = GL43.glGetShaderInfoLog(shader);
                GL43.glDeleteShader(shader);
                throw new RuntimeException("Shader compilation failed:\\n" + log);
            }
            
            // Create program
            int program = GL43.glCreateProgram();
            GL43.glAttachShader(program, shader);
            GL43.glLinkProgram(program);
            
            // Check linking status
            status = GL43.glGetProgrami(program, GL43.GL_LINK_STATUS);
            if (status == GL43.GL_FALSE) {
                String log = GL43.glGetProgramInfoLog(program);
                GL43.glDeleteShader(shader);
                GL43.glDeleteProgram(program);
                throw new RuntimeException("Program linking failed:\\n" + log);
            }
            
            GL43.glDeleteShader(shader);
            return program;
        }
    }
    
    /**
     * Utility class for common shader configurations.
     */
    public static class Presets {
        
        public static ShaderVariant basicTraversal(ESVOShaderManager manager) {
            return manager.createVariant("traverse.comp");
        }
        
        public static ShaderVariant shadowTraversal(ESVOShaderManager manager) {
            return manager.createVariant("traverse.comp")
                          .define("ENABLE_SHADOWS")
                          .define("EARLY_TERMINATION_THRESHOLD", "0.99");
        }
        
        public static ShaderVariant lodTraversal(ESVOShaderManager manager, float bias, float distance) {
            return manager.createVariant("traverse.comp")
                          .define("ENABLE_LOD")
                          .define("LOD_BIAS", bias)
                          .define("LOD_DISTANCE", distance);
        }
        
        public static ShaderVariant statisticsTraversal(ESVOShaderManager manager) {
            return manager.createVariant("traverse.comp")
                          .define("ENABLE_STATISTICS");
        }
        
        public static ShaderVariant coherentBeam(ESVOShaderManager manager) {
            return manager.createVariant("beam.comp")
                          .define("COHERENCE_THRESHOLD", "0.7")
                          .define("SPLIT_THRESHOLD", "0.3");
        }
        
        public static ShaderVariant pbrShading(ESVOShaderManager manager) {
            return manager.createVariant("shade.comp")
                          .define("ENABLE_PBR");
        }
        
        public static ShaderVariant shadowedShading(ESVOShaderManager manager) {
            return manager.createVariant("shade.comp")
                          .define("ENABLE_SHADOWS")
                          .define("ENABLE_AO");
        }
        
        public static ShaderVariant softShadows(ESVOShaderManager manager, int samples) {
            return manager.createVariant("shadow.comp")
                          .define("ENABLE_SOFT_SHADOWS")
                          .define("SOFT_SHADOW_SAMPLES", samples);
        }
        
        public static ShaderVariant volumetricShadows(ESVOShaderManager manager, int samples, float density) {
            return manager.createVariant("shadow.comp")
                          .define("ENABLE_VOLUMETRIC_SHADOWS")
                          .define("VOLUMETRIC_SAMPLES", samples)
                          .define("VOLUMETRIC_DENSITY", density);
        }
    }
}