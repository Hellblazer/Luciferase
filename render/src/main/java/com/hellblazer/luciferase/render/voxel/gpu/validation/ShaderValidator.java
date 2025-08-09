package com.hellblazer.luciferase.render.voxel.gpu.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates WGSL shader structure and correctness.
 * Performs static analysis of shader code without compilation.
 */
public class ShaderValidator {
    private static final Logger log = LoggerFactory.getLogger(ShaderValidator.class);
    
    // WGSL patterns for validation
    private static final Pattern STRUCT_PATTERN = Pattern.compile(
        "struct\\s+(\\w+)\\s*\\{([^}]+)\\}", Pattern.DOTALL
    );
    
    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
        "fn\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:->\\s*[^{]+)?\\s*\\{", Pattern.DOTALL
    );
    
    private static final Pattern BINDING_PATTERN = Pattern.compile(
        "@group\\((\\d+)\\)\\s*@binding\\((\\d+)\\)\\s*var(?:<[^>]+>)?\\s+(\\w+)\\s*:\\s*([^;]+);"
    );
    
    private static final Pattern ENTRY_POINT_PATTERN = Pattern.compile(
        "@(compute|vertex|fragment)(?:\\s*@workgroup_size\\([^)]+\\))?\\s*fn\\s+(\\w+)"
    );
    
    private static final Pattern WORKGROUP_SIZE_PATTERN = Pattern.compile(
        "@workgroup_size\\((\\d+)(?:\\s*,\\s*(\\d+))?(?:\\s*,\\s*(\\d+))?\\)"
    );
    
    public static class ValidationResult {
        public final boolean valid;
        public final List<String> errors;
        public final List<String> warnings;
        public final ShaderInfo info;
        
        public ValidationResult(boolean valid, List<String> errors, List<String> warnings, ShaderInfo info) {
            this.valid = valid;
            this.errors = errors != null ? errors : new ArrayList<>();
            this.warnings = warnings != null ? warnings : new ArrayList<>();
            this.info = info;
        }
    }
    
    public static class ShaderInfo {
        public final Map<String, StructInfo> structs = new HashMap<>();
        public final Map<String, FunctionInfo> functions = new HashMap<>();
        public final Map<String, BindingInfo> bindings = new HashMap<>();
        public final Map<String, EntryPointInfo> entryPoints = new HashMap<>();
        
        @Override
        public String toString() {
            return String.format(
                "ShaderInfo{structs=%d, functions=%d, bindings=%d, entryPoints=%s}",
                structs.size(), functions.size(), bindings.size(), entryPoints.keySet()
            );
        }
    }
    
    public static class StructInfo {
        public final String name;
        public final List<String> fields;
        
        public StructInfo(String name, List<String> fields) {
            this.name = name;
            this.fields = fields;
        }
    }
    
    public static class FunctionInfo {
        public final String name;
        public final boolean isRecursive;
        public final boolean isEntryPoint;
        
        public FunctionInfo(String name, boolean isRecursive, boolean isEntryPoint) {
            this.name = name;
            this.isRecursive = isRecursive;
            this.isEntryPoint = isEntryPoint;
        }
    }
    
    public static class BindingInfo {
        public final int group;
        public final int binding;
        public final String name;
        public final String type;
        
        public BindingInfo(int group, int binding, String name, String type) {
            this.group = group;
            this.binding = binding;
            this.name = name;
            this.type = type;
        }
        
        @Override
        public String toString() {
            return String.format("@group(%d) @binding(%d) %s: %s", group, binding, name, type);
        }
    }
    
    public static class EntryPointInfo {
        public final String stage;
        public final String name;
        public final int[] workgroupSize;
        
        public EntryPointInfo(String stage, String name, int[] workgroupSize) {
            this.stage = stage;
            this.name = name;
            this.workgroupSize = workgroupSize;
        }
        
        @Override
        public String toString() {
            if (workgroupSize != null) {
                return String.format("%s:%s[%dx%dx%d]", stage, name,
                    workgroupSize[0], workgroupSize[1], workgroupSize[2]);
            }
            return String.format("%s:%s", stage, name);
        }
    }
    
    /**
     * Validate shader from resource file
     */
    public ValidationResult validateShaderResource(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return new ValidationResult(false, 
                    List.of("Resource not found: " + resourcePath), 
                    null, null);
            }
            
            String shaderCode = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return validateShader(shaderCode, resourcePath);
        } catch (IOException e) {
            return new ValidationResult(false, 
                List.of("Failed to read resource: " + e.getMessage()), 
                null, null);
        }
    }
    
    /**
     * Validate shader code
     */
    public ValidationResult validateShader(String shaderCode, String shaderName) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        ShaderInfo info = new ShaderInfo();
        
        // Parse structures
        parseStructs(shaderCode, info, errors);
        
        // Parse functions
        parseFunctions(shaderCode, info, errors, warnings);
        
        // Parse bindings
        parseBindings(shaderCode, info, errors);
        
        // Parse entry points
        parseEntryPoints(shaderCode, info, errors);
        
        // Validate entry points
        validateEntryPoints(info, errors, warnings);
        
        // Validate bindings
        validateBindings(info, errors, warnings);
        
        // Check for common issues
        checkCommonIssues(shaderCode, errors, warnings);
        
        boolean valid = errors.isEmpty();
        
        if (valid) {
            log.info("Shader {} validated successfully: {}", shaderName, info);
        } else {
            log.error("Shader {} validation failed with {} errors", shaderName, errors.size());
        }
        
        return new ValidationResult(valid, errors, warnings, info);
    }
    
    private void parseStructs(String code, ShaderInfo info, List<String> errors) {
        Matcher matcher = STRUCT_PATTERN.matcher(code);
        while (matcher.find()) {
            String name = matcher.group(1);
            String body = matcher.group(2);
            List<String> fields = Arrays.asList(body.trim().split(","));
            info.structs.put(name, new StructInfo(name, fields));
        }
    }
    
    private void parseFunctions(String code, ShaderInfo info, List<String> errors, List<String> warnings) {
        Matcher matcher = FUNCTION_PATTERN.matcher(code);
        Set<String> functionNames = new HashSet<>();
        
        while (matcher.find()) {
            String name = matcher.group(1);
            functionNames.add(name);
        }
        
        // Check for recursive functions (simple check - looks for self-calls)
        for (String funcName : functionNames) {
            Pattern recursivePattern = Pattern.compile(
                "fn\\s+" + Pattern.quote(funcName) + "\\s*\\([^}]*" + Pattern.quote(funcName) + "\\s*\\(",
                Pattern.DOTALL
            );
            boolean isRecursive = recursivePattern.matcher(code).find();
            
            if (isRecursive) {
                errors.add("Recursive function detected: " + funcName + " (not allowed in WGSL)");
            }
            
            info.functions.put(funcName, new FunctionInfo(funcName, isRecursive, false));
        }
    }
    
    private void parseBindings(String code, ShaderInfo info, List<String> errors) {
        Matcher matcher = BINDING_PATTERN.matcher(code);
        Set<String> bindingKeys = new HashSet<>();
        
        while (matcher.find()) {
            int group = Integer.parseInt(matcher.group(1));
            int binding = Integer.parseInt(matcher.group(2));
            String name = matcher.group(3);
            String type = matcher.group(4).trim();
            
            String key = group + ":" + binding;
            if (bindingKeys.contains(key)) {
                errors.add("Duplicate binding: @group(" + group + ") @binding(" + binding + ")");
            }
            bindingKeys.add(key);
            
            info.bindings.put(name, new BindingInfo(group, binding, name, type));
        }
    }
    
    private void parseEntryPoints(String code, ShaderInfo info, List<String> errors) {
        Matcher matcher = ENTRY_POINT_PATTERN.matcher(code);
        
        while (matcher.find()) {
            String stage = matcher.group(1);
            String name = matcher.group(2);
            
            // Look for workgroup size if compute shader
            int[] workgroupSize = null;
            if ("compute".equals(stage)) {
                int startPos = matcher.start();
                String nearbyCode = code.substring(Math.max(0, startPos - 100), 
                                                   Math.min(code.length(), startPos + 200));
                Matcher sizeMatcher = WORKGROUP_SIZE_PATTERN.matcher(nearbyCode);
                if (sizeMatcher.find()) {
                    int x = Integer.parseInt(sizeMatcher.group(1));
                    int y = sizeMatcher.group(2) != null ? Integer.parseInt(sizeMatcher.group(2)) : 1;
                    int z = sizeMatcher.group(3) != null ? Integer.parseInt(sizeMatcher.group(3)) : 1;
                    workgroupSize = new int[]{x, y, z};
                }
            }
            
            info.entryPoints.put(name, new EntryPointInfo(stage, name, workgroupSize));
            
            // Mark function as entry point
            if (info.functions.containsKey(name)) {
                FunctionInfo func = info.functions.get(name);
                info.functions.put(name, new FunctionInfo(name, func.isRecursive, true));
            }
        }
    }
    
    private void validateEntryPoints(ShaderInfo info, List<String> errors, List<String> warnings) {
        if (info.entryPoints.isEmpty()) {
            errors.add("No entry points found in shader");
        }
        
        for (EntryPointInfo ep : info.entryPoints.values()) {
            if ("compute".equals(ep.stage) && ep.workgroupSize == null) {
                errors.add("Compute shader entry point '" + ep.name + "' missing @workgroup_size");
            }
            
            if (ep.workgroupSize != null) {
                int total = ep.workgroupSize[0] * ep.workgroupSize[1] * ep.workgroupSize[2];
                if (total > 256) {
                    warnings.add("Workgroup size " + total + " exceeds recommended maximum of 256");
                }
            }
        }
    }
    
    private void validateBindings(ShaderInfo info, List<String> errors, List<String> warnings) {
        // Check for gaps in binding indices
        Map<Integer, Set<Integer>> groupBindings = new HashMap<>();
        for (BindingInfo binding : info.bindings.values()) {
            groupBindings.computeIfAbsent(binding.group, k -> new HashSet<>()).add(binding.binding);
        }
        
        for (Map.Entry<Integer, Set<Integer>> entry : groupBindings.entrySet()) {
            int group = entry.getKey();
            Set<Integer> bindings = entry.getValue();
            int maxBinding = bindings.stream().max(Integer::compare).orElse(0);
            
            for (int i = 0; i <= maxBinding; i++) {
                if (!bindings.contains(i)) {
                    warnings.add("Gap in binding indices for group " + group + ": missing binding " + i);
                }
            }
        }
    }
    
    private void checkCommonIssues(String code, List<String> errors, List<String> warnings) {
        // Check for texture sampling in compute shaders
        if (code.contains("@compute") && code.contains("textureSample")) {
            errors.add("textureSample not allowed in compute shaders - use textureLoad instead");
        }
        
        // Check for array indexing with non-constants
        Pattern arrayIndexPattern = Pattern.compile("array<[^>]+>\\([^)]+\\)\\[[^\\]]+\\]");
        Matcher matcher = arrayIndexPattern.matcher(code);
        while (matcher.find()) {
            String match = matcher.group();
            if (match.contains("[i]") || match.contains("[j]") || match.contains("[k]")) {
                warnings.add("Non-constant array indexing detected - may cause compilation errors");
            }
        }
        
        // Check for missing var qualifiers
        if (code.contains("let") && code.contains("storage")) {
            warnings.add("Using 'let' with storage buffers - should be 'var<storage>'");
        }
    }
}