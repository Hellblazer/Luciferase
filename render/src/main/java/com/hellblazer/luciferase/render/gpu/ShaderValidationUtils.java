/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.hellblazer.luciferase.render.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for validating shader source code across different backends.
 * Provides comprehensive validation for GLSL compute shaders and their
 * compatibility with various GPU backends including OpenGL and BGFX Metal.
 */
public class ShaderValidationUtils {
    private static final Logger log = LoggerFactory.getLogger(ShaderValidationUtils.class);
    
    private static final Pattern VERSION_PATTERN = Pattern.compile("#version\\s+(\\d+)\\s*(\\w*)");
    private static final Pattern LAYOUT_PATTERN = Pattern.compile("layout\\s*\\(([^)]+)\\)");
    private static final Pattern WORK_GROUP_PATTERN = Pattern.compile("local_size_([xyz])\\s*=\\s*(\\d+)");
    private static final Pattern SSBO_PATTERN = Pattern.compile("layout\\s*\\(std430,\\s*binding\\s*=\\s*(\\d+)\\)\\s*(?:readonly|writeonly)?\\s*buffer");
    private static final Pattern UNIFORM_PATTERN = Pattern.compile("uniform\\s+(\\w+)\\s+(\\w+)");
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("\\b(\\w+)\\s+\\w+\\s*\\([^)]*\\)\\s*\\{");
    
    /**
     * Validation result containing errors, warnings, and metadata.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;
        private final Map<String, Object> metadata;
        
        public ValidationResult(boolean valid, List<String> errors, List<String> warnings, Map<String, Object> metadata) {
            this.valid = valid;
            this.errors = List.copyOf(errors);
            this.warnings = List.copyOf(warnings);
            this.metadata = Map.copyOf(metadata);
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public List<String> getErrors() {
            return errors;
        }
        
        public List<String> getWarnings() {
            return warnings;
        }
        
        public Map<String, Object> getMetadata() {
            return metadata;
        }
        
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
        
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }
    
    /**
     * Validates a GLSL compute shader for general correctness and backend compatibility.
     * 
     * @param source the shader source code to validate
     * @param targetBackend the target backend ("OpenGL", "BGFX_Metal", etc.)
     * @return validation result with errors, warnings, and metadata
     */
    public static ValidationResult validateComputeShader(String source, String targetBackend) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> metadata = new HashMap<>();
        
        // Basic structure validation
        validateBasicStructure(source, errors, warnings, metadata);
        
        // Version validation
        validateVersion(source, errors, warnings, metadata);
        
        // Work group validation
        validateWorkGroup(source, errors, warnings, metadata);
        
        // Buffer binding validation
        validateBufferBindings(source, errors, warnings, metadata);
        
        // Backend-specific validation
        validateBackendCompatibility(source, targetBackend, errors, warnings, metadata);
        
        // Performance analysis
        analyzePerformance(source, warnings, metadata);
        
        boolean isValid = errors.isEmpty();
        return new ValidationResult(isValid, errors, warnings, metadata);
    }
    
    /**
     * Validates basic shader structure (main function, includes, etc.).
     */
    private static void validateBasicStructure(String source, List<String> errors, List<String> warnings, Map<String, Object> metadata) {
        // Check for main function
        if (!source.contains("void main()")) {
            errors.add("Missing main() function");
        }
        
        // Check for balanced braces
        int braceCount = 0;
        for (char c : source.toCharArray()) {
            if (c == '{') braceCount++;
            if (c == '}') braceCount--;
        }
        if (braceCount != 0) {
            errors.add("Unbalanced braces in shader source");
        }
        
        // Count lines and estimate complexity
        String[] lines = source.split("\\n");
        metadata.put("line_count", lines.length);
        
        long instructionCount = Arrays.stream(lines)
            .filter(line -> !line.trim().isEmpty() && !line.trim().startsWith("//") && !line.trim().startsWith("/*"))
            .count();
        metadata.put("instruction_count", instructionCount);
        
        if (instructionCount > 1000) {
            warnings.add("Large shader (" + instructionCount + " instructions) may impact compilation time");
        }
    }
    
    /**
     * Validates GLSL version directive.
     */
    private static void validateVersion(String source, List<String> errors, List<String> warnings, Map<String, Object> metadata) {
        Matcher versionMatcher = VERSION_PATTERN.matcher(source);
        
        if (!versionMatcher.find()) {
            errors.add("Missing #version directive");
            return;
        }
        
        int version = Integer.parseInt(versionMatcher.group(1));
        String profile = versionMatcher.group(2);
        
        metadata.put("glsl_version", version);
        metadata.put("glsl_profile", profile != null ? profile : "core");
        
        if (version < 430) {
            errors.add("GLSL version " + version + " does not support compute shaders (requires 430+)");
        } else if (version > 460) {
            warnings.add("GLSL version " + version + " may not be supported on all drivers");
        }
        
        if (profile != null && !profile.equals("core")) {
            warnings.add("Non-core GLSL profile '" + profile + "' may cause compatibility issues");
        }
    }
    
    /**
     * Validates work group size specification.
     */
    private static void validateWorkGroup(String source, List<String> errors, List<String> warnings, Map<String, Object> metadata) {
        Matcher layoutMatcher = LAYOUT_PATTERN.matcher(source);
        boolean foundWorkGroup = false;
        int[] workGroupSize = {1, 1, 1};
        
        while (layoutMatcher.find()) {
            String layoutContent = layoutMatcher.group(1);
            
            if (layoutContent.contains("local_size_")) {
                foundWorkGroup = true;
                
                Matcher workGroupMatcher = WORK_GROUP_PATTERN.matcher(layoutContent);
                while (workGroupMatcher.find()) {
                    String dimension = workGroupMatcher.group(1);
                    int size = Integer.parseInt(workGroupMatcher.group(2));
                    
                    switch (dimension) {
                        case "x" -> workGroupSize[0] = size;
                        case "y" -> workGroupSize[1] = size;
                        case "z" -> workGroupSize[2] = size;
                    }
                }
            }
        }
        
        if (!foundWorkGroup) {
            errors.add("Missing work group size specification (layout(local_size_x = ..., local_size_y = ..., local_size_z = ...))");
            return;
        }
        
        metadata.put("work_group_size", workGroupSize);
        
        int totalThreads = workGroupSize[0] * workGroupSize[1] * workGroupSize[2];
        metadata.put("total_threads_per_group", totalThreads);
        
        // Validate work group sizes
        for (int i = 0; i < 3; i++) {
            if (workGroupSize[i] <= 0) {
                errors.add("Work group size must be positive (dimension " + "xyz".charAt(i) + " = " + workGroupSize[i] + ")");
            } else if (workGroupSize[i] > 1024) {
                warnings.add("Large work group size (" + workGroupSize[i] + ") may reduce occupancy on some hardware");
            }
        }
        
        if (totalThreads > 1024) {
            warnings.add("Total work group size (" + totalThreads + ") exceeds typical hardware limits (1024)");
        }
        
        // Check for power-of-2 sizes (often more efficient)
        for (int i = 0; i < 3; i++) {
            if (workGroupSize[i] > 1 && (workGroupSize[i] & (workGroupSize[i] - 1)) != 0) {
                warnings.add("Non-power-of-2 work group size may be less efficient (dimension " + "xyz".charAt(i) + " = " + workGroupSize[i] + ")");
            }
        }
    }
    
    /**
     * Validates buffer bindings and SSBO usage.
     */
    private static void validateBufferBindings(String source, List<String> errors, List<String> warnings, Map<String, Object> metadata) {
        Matcher ssboMatcher = SSBO_PATTERN.matcher(source);
        Set<Integer> usedBindings = new HashSet<>();
        int ssboCount = 0;
        
        while (ssboMatcher.find()) {
            ssboCount++;
            int binding = Integer.parseInt(ssboMatcher.group(1));
            
            if (usedBindings.contains(binding)) {
                errors.add("Duplicate buffer binding: " + binding);
            }
            usedBindings.add(binding);
            
            if (binding < 0 || binding > 15) {
                warnings.add("Buffer binding " + binding + " may not be supported on all hardware (typical range: 0-15)");
            }
        }
        
        metadata.put("ssbo_count", ssboCount);
        metadata.put("buffer_bindings", new ArrayList<>(usedBindings));
        
        if (ssboCount > 8) {
            warnings.add("High number of SSBOs (" + ssboCount + ") may impact performance");
        }
    }
    
    /**
     * Validates backend-specific compatibility.
     */
    private static void validateBackendCompatibility(String source, String targetBackend, List<String> errors, List<String> warnings, Map<String, Object> metadata) {
        metadata.put("target_backend", targetBackend);
        
        switch (targetBackend.toLowerCase()) {
            case "opengl" -> validateOpenGLCompatibility(source, errors, warnings, metadata);
            case "bgfx_metal" -> validateMetalCompatibility(source, errors, warnings, metadata);
            case "bgfx_vulkan" -> validateVulkanCompatibility(source, errors, warnings, metadata);
            default -> warnings.add("Unknown target backend: " + targetBackend);
        }
    }
    
    /**
     * Validates OpenGL-specific features and limitations.
     */
    private static void validateOpenGLCompatibility(String source, List<String> errors, List<String> warnings, Map<String, Object> metadata) {
        // Check for OpenGL-specific built-ins
        String[] glBuiltins = {
            "gl_GlobalInvocationID", "gl_LocalInvocationID", "gl_WorkGroupID",
            "gl_LocalInvocationIndex", "gl_WorkGroupSize", "gl_NumWorkGroups"
        };
        
        List<String> foundBuiltins = new ArrayList<>();
        for (String builtin : glBuiltins) {
            if (source.contains(builtin)) {
                foundBuiltins.add(builtin);
            }
        }
        metadata.put("gl_builtins_used", foundBuiltins);
        
        // Check for compute shader barriers
        if (source.contains("barrier()") || source.contains("memoryBarrier")) {
            metadata.put("uses_barriers", true);
        }
        
        // Check for atomic operations
        if (source.contains("atomic")) {
            metadata.put("uses_atomics", true);
        }
    }
    
    /**
     * Validates Metal backend compatibility (via BGFX).
     */
    private static void validateMetalCompatibility(String source, List<String> errors, List<String> warnings, Map<String, Object> metadata) {
        // Check for problematic OpenGL constructs
        String[] problematicConstructs = {
            "gl_GlobalInvocationID.w",  // Metal only has 3D work groups
            "imageAtomicExchange",      // Different atomic operations
            "memoryBarrierShared()",    // Different barrier semantics
            "gl_LocalInvocationIndex",  // No direct equivalent in Metal
        };
        
        List<String> foundProblematic = new ArrayList<>();
        for (String construct : problematicConstructs) {
            if (source.contains(construct)) {
                foundProblematic.add(construct);
                warnings.add("Construct '" + construct + "' may not translate well to Metal");
            }
        }
        metadata.put("metal_problematic_constructs", foundProblematic);
        
        // Check for supported Metal features
        if (source.contains("imageLoad") || source.contains("imageStore")) {
            metadata.put("uses_image_operations", true);
        }
        
        if (source.contains("shared ")) {
            warnings.add("Shared memory usage requires careful translation to Metal threadgroup memory");
        }
    }
    
    /**
     * Validates Vulkan backend compatibility (via BGFX).
     */
    private static void validateVulkanCompatibility(String source, List<String> errors, List<String> warnings, Map<String, Object> metadata) {
        // Vulkan has good GLSL compatibility, fewer warnings needed
        if (source.contains("gl_")) {
            metadata.put("uses_gl_builtins", true);
        }
        
        // Check for descriptor set usage
        if (source.contains("binding")) {
            metadata.put("uses_descriptor_sets", true);
        }
    }
    
    /**
     * Analyzes shader for performance characteristics.
     */
    private static void analyzePerformance(String source, List<String> warnings, Map<String, Object> metadata) {
        // Count expensive operations
        int loopCount = countOccurrences(source, "for\\s*\\(") + countOccurrences(source, "while\\s*\\(");
        int branchCount = countOccurrences(source, "if\\s*\\(") + countOccurrences(source, "else");
        int functionCallCount = countOccurrences(source, "\\w+\\s*\\(");
        
        metadata.put("loop_count", loopCount);
        metadata.put("branch_count", branchCount);
        metadata.put("function_call_count", functionCallCount);
        
        if (loopCount > 10) {
            warnings.add("High number of loops (" + loopCount + ") may impact GPU performance");
        }
        
        if (branchCount > 20) {
            warnings.add("High number of branches (" + branchCount + ") may cause warp divergence");
        }
        
        // Check for divergent operations
        if (source.contains("discard") || source.contains("return") && !source.contains("void main()")) {
            warnings.add("Early returns or discard operations can cause warp divergence");
        }
        
        // Check for expensive math operations
        String[] expensiveOps = {"sin", "cos", "tan", "exp", "log", "pow", "sqrt"};
        int expensiveOpCount = 0;
        for (String op : expensiveOps) {
            expensiveOpCount += countOccurrences(source, "\\b" + op + "\\s*\\(");
        }
        
        metadata.put("expensive_op_count", expensiveOpCount);
        if (expensiveOpCount > 5) {
            warnings.add("High number of expensive math operations (" + expensiveOpCount + ") may impact performance");
        }
    }
    
    /**
     * Counts occurrences of a regex pattern in the source.
     */
    private static int countOccurrences(String source, String pattern) {
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(source);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }
    
    /**
     * Validates a set of preprocessor defines for correctness.
     */
    public static ValidationResult validateDefines(Map<String, String> defines) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> metadata = new HashMap<>();
        
        metadata.put("define_count", defines.size());
        
        for (Map.Entry<String, String> entry : defines.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            
            // Validate define name
            if (!name.matches("[A-Z_][A-Z0-9_]*")) {
                warnings.add("Define name '" + name + "' does not follow convention (should be UPPER_CASE)");
            }
            
            // Check for reserved prefixes
            if (name.startsWith("GL_") || name.startsWith("gl_")) {
                errors.add("Define name '" + name + "' uses reserved OpenGL prefix");
            }
            
            // Validate value if present
            if (value != null && !value.isEmpty()) {
                try {
                    // Try to parse as number
                    Double.parseDouble(value);
                    metadata.put("numeric_defines", (Integer) metadata.getOrDefault("numeric_defines", 0) + 1);
                } catch (NumberFormatException e) {
                    // Not a number, that's fine
                }
            }
        }
        
        boolean isValid = errors.isEmpty();
        return new ValidationResult(isValid, errors, warnings, metadata);
    }
}