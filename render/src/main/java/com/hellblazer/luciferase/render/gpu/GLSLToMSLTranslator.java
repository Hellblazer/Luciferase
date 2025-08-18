package com.hellblazer.luciferase.render.gpu;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Arrays;
import java.util.List;

/**
 * Translates GLSL (OpenGL Shading Language) shaders to MSL (Metal Shading Language)
 * for use with the BGFX Metal backend.
 * 
 * This translator handles the key differences between GLSL and MSL:
 * - Header and version directives
 * - Built-in variables and functions
 * - Buffer binding syntax
 * - Data structure declarations
 * - Compute shader dispatch patterns
 */
public class GLSLToMSLTranslator {
    
    // GLSL to MSL built-in variable mappings
    private static final Map<String, String> BUILTIN_VARIABLES = new HashMap<>();
    static {
        // Compute shader built-ins
        BUILTIN_VARIABLES.put("gl_GlobalInvocationID", "thread_position_in_grid");
        BUILTIN_VARIABLES.put("gl_LocalInvocationID", "thread_position_in_threadgroup");
        BUILTIN_VARIABLES.put("gl_LocalInvocationIndex", "thread_index_in_threadgroup");
        BUILTIN_VARIABLES.put("gl_WorkGroupID", "threadgroup_position_in_grid");
        BUILTIN_VARIABLES.put("gl_WorkGroupSize", "threads_per_threadgroup");
        
        // Fragment shader built-ins
        BUILTIN_VARIABLES.put("gl_FragCoord", "position");
        BUILTIN_VARIABLES.put("gl_FragColor", "color_output");
        
        // Vertex shader built-ins
        BUILTIN_VARIABLES.put("gl_Position", "position");
        BUILTIN_VARIABLES.put("gl_VertexID", "vertex_id");
        BUILTIN_VARIABLES.put("gl_InstanceID", "instance_id");
    }
    
    // GLSL to MSL function mappings
    private static final Map<String, String> BUILTIN_FUNCTIONS = new HashMap<>();
    static {
        BUILTIN_FUNCTIONS.put("bitCount", "popcount");
        BUILTIN_FUNCTIONS.put("atomicAdd", "atomic_fetch_add_explicit");
        BUILTIN_FUNCTIONS.put("imageLoad", "texture.read");
        BUILTIN_FUNCTIONS.put("imageStore", "texture.write");
        BUILTIN_FUNCTIONS.put("texelFetch", "texture.read");
        BUILTIN_FUNCTIONS.put("texture", "texture.sample");
        BUILTIN_FUNCTIONS.put("textureSize", "texture.get_width"); // Note: partial mapping
    }
    
    // GLSL to MSL type mappings
    private static final Map<String, String> TYPE_MAPPINGS = new HashMap<>();
    static {
        // Keep most types the same, but handle specific differences
        TYPE_MAPPINGS.put("image2D", "texture2d<float>");
        TYPE_MAPPINGS.put("iimage2D", "texture2d<int>");
        TYPE_MAPPINGS.put("uimage2D", "texture2d<uint>");
        TYPE_MAPPINGS.put("sampler2D", "texture2d<float>");
    }
    
    // Regex patterns for various GLSL constructs
    private static final Pattern VERSION_PATTERN = Pattern.compile("#version\\s+\\d+\\s+core");
    private static final Pattern LAYOUT_LOCAL_SIZE_PATTERN = Pattern.compile(
        "layout\\s*\\(\\s*local_size_x\\s*=\\s*(\\d+)\\s*,\\s*local_size_y\\s*=\\s*(\\d+)\\s*,\\s*local_size_z\\s*=\\s*(\\d+)\\s*\\)\\s+in\\s*;");
    private static final Pattern BUFFER_BINDING_PATTERN = Pattern.compile(
        "layout\\s*\\(\\s*std430\\s*,\\s*binding\\s*=\\s*(\\d+)\\s*\\)\\s+(readonly|writeonly|coherent)?\\s*buffer\\s+(\\w+)\\s*\\{([^}]+)\\}\\s*(\\w+)\\s*;", Pattern.DOTALL);
    private static final Pattern UNIFORM_PATTERN = Pattern.compile("uniform\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static final Pattern SHARED_PATTERN = Pattern.compile("shared\\s+(\\w+)\\s+(\\w+(?:\\[\\d+\\])?);");
    
    // Container for buffer binding information
    private static class BufferBinding {
        final int index;
        final String access;
        final String structName;
        final String instanceName;
        
        BufferBinding(int index, String access, String structName, String instanceName) {
            this.index = index;
            this.access = access;
            this.structName = structName;
            this.instanceName = instanceName;
        }
    }
    
    /**
     * Translates a complete GLSL shader to MSL
     */
    public String translateShader(String glslSource, ShaderType shaderType) {
        var result = new StringBuilder();
        var bufferBindings = new ArrayList<BufferBinding>();
        
        // Add MSL header
        result.append(generateMSLHeader(glslSource, shaderType));
        result.append("\n\n");
        
        // First, handle multi-line constructs like buffer bindings
        var processedSource = preprocessMultiLineConstructs(glslSource, bufferBindings);
        
        // Process the shader line by line
        var lines = processedSource.split("\n");
        var inMainFunction = false;
        var braceDepth = 0;
        
        for (var line : lines) {
            var trimmedLine = line.trim();
            
            // Skip empty lines and processed constructs
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("//PROCESSED:")) {
                continue;
            }
            
            // Skip GLSL version directive
            if (VERSION_PATTERN.matcher(trimmedLine).matches()) {
                continue;
            }
            
            // Handle layout directives
            if (trimmedLine.startsWith("layout")) {
                var translated = translateLayoutDirective(line, shaderType, bufferBindings);
                if (translated != null) {
                    result.append(translated).append("\n");
                }
                continue;
            }
            
            // Handle uniform declarations
            if (trimmedLine.startsWith("uniform")) {
                var translated = translateUniform(line);
                if (translated != null) {
                    result.append(translated).append("\n");
                }
                continue;
            }
            
            // Handle shared memory declarations
            if (trimmedLine.startsWith("shared")) {
                var translated = translateSharedMemory(line);
                if (translated != null) {
                    result.append(translated).append("\n");
                }
                continue;
            }
            
            // Track main function
            if (trimmedLine.contains("void main()")) {
                inMainFunction = true;
                result.append(generateMainFunctionSignature(shaderType, bufferBindings)).append("\n");
                continue;
            }
            
            // Track brace depth in main function
            if (inMainFunction) {
                braceDepth += countBraces(trimmedLine);
                if (braceDepth == 0 && trimmedLine.equals("}")) {
                    result.append("}\n");
                    inMainFunction = false;
                    continue;
                }
            }
            
            // Translate the line content
            var translatedLine = translateLineContent(line);
            result.append(translatedLine).append("\n");
        }
        
        return result.toString();
    }
    
    private String preprocessMultiLineConstructs(String source, List<BufferBinding> bufferBindings) {
        var result = source;
        
        // Handle buffer bindings that span multiple lines
        var bufferMatcher = BUFFER_BINDING_PATTERN.matcher(result);
        var sb = new StringBuilder();
        var lastEnd = 0;
        
        while (bufferMatcher.find()) {
            var bindingIndex = Integer.parseInt(bufferMatcher.group(1));
            var access = bufferMatcher.group(2);
            var bufferName = bufferMatcher.group(3);
            var content = bufferMatcher.group(4);
            var instanceName = bufferMatcher.group(5);
            
            // Store buffer binding for use in function signature
            bufferBindings.add(new BufferBinding(bindingIndex, access, bufferName, instanceName));
            
            // Add content before this match
            sb.append(result.substring(lastEnd, bufferMatcher.start()));
            
            // Add translated buffer structure
            sb.append("// Buffer binding ").append(bindingIndex).append("\n");
            sb.append("struct ").append(bufferName).append(" {\n");
            sb.append(content);
            sb.append("\n};\n");
            sb.append("//PROCESSED:").append(instanceName).append("\n");
            
            lastEnd = bufferMatcher.end();
        }
        
        // Add remaining content
        sb.append(result.substring(lastEnd));
        
        return sb.toString();
    }
    
    private String generateMSLHeader(String glslSource, ShaderType shaderType) {
        var header = new StringBuilder();
        header.append("#include <metal_stdlib>\n");
        header.append("#include <metal_compute>\n");
        header.append("using namespace metal;\n");
        
        // Extract work group size for compute shaders
        if (shaderType == ShaderType.COMPUTE) {
            var matcher = LAYOUT_LOCAL_SIZE_PATTERN.matcher(glslSource);
            if (matcher.find()) {
                var x = matcher.group(1);
                var y = matcher.group(2);  
                var z = matcher.group(3);
                header.append("\n// Compute kernel configuration\n");
                header.append("// local_size_x = ").append(x).append(", ");
                header.append("local_size_y = ").append(y).append(", ");
                header.append("local_size_z = ").append(z).append("\n");
            }
        }
        
        return header.toString();
    }
    
    private String translateLayoutDirective(String line, ShaderType shaderType, List<BufferBinding> bufferBindings) {
        // Handle compute shader local size
        var localSizeMatcher = LAYOUT_LOCAL_SIZE_PATTERN.matcher(line);
        if (localSizeMatcher.matches()) {
            // In MSL, work group size is specified in kernel attribute
            return null; // Skip, handled in function signature
        }
        
        // Handle buffer bindings
        var bufferMatcher = BUFFER_BINDING_PATTERN.matcher(line);
        if (bufferMatcher.matches()) {
            var bindingIndex = Integer.parseInt(bufferMatcher.group(1));
            var access = bufferMatcher.group(2);
            var bufferName = bufferMatcher.group(3);
            var content = bufferMatcher.group(4);
            var instanceName = bufferMatcher.group(5);
            
            // Store buffer binding for use in function signature
            bufferBindings.add(new BufferBinding(bindingIndex, access, bufferName, instanceName));
            
            var result = new StringBuilder();
            result.append("// Buffer binding ").append(bindingIndex).append("\n");
            result.append("struct ").append(bufferName).append(" {\n");
            result.append(content);
            result.append("\n};");
            
            return result.toString();
        }
        
        return null;
    }
    
    private String translateUniform(String line) {
        var matcher = UNIFORM_PATTERN.matcher(line.trim());
        if (matcher.matches()) {
            var type = matcher.group(1);
            var name = matcher.group(2);
            
            // In MSL, uniforms are typically passed as constant buffer parameters
            return "// uniform " + type + " " + name + " (passed as parameter)";
        }
        return line;
    }
    
    private String translateSharedMemory(String line) {
        var matcher = SHARED_PATTERN.matcher(line.trim());
        if (matcher.matches()) {
            var type = matcher.group(1);
            var name = matcher.group(2);
            
            return "threadgroup " + type + " " + name + ";";
        }
        return line;
    }
    
    private String generateMainFunctionSignature(ShaderType shaderType, List<BufferBinding> bufferBindings) {
        var signature = new StringBuilder();
        
        switch (shaderType) {
            case COMPUTE:
                signature.append("kernel void main_compute(\n");
                signature.append("    uint3 thread_position_in_grid [[thread_position_in_grid]],\n");
                signature.append("    uint3 thread_position_in_threadgroup [[thread_position_in_threadgroup]],\n");
                signature.append("    uint thread_index_in_threadgroup [[thread_index_in_threadgroup]]");
                
                // Add buffer parameters
                for (var binding : bufferBindings) {
                    signature.append(",\n    ");
                    if ("readonly".equals(binding.access)) {
                        signature.append("const device ");
                    } else if ("writeonly".equals(binding.access)) {
                        signature.append("device ");
                    } else {
                        signature.append("device ");
                    }
                    signature.append(binding.structName).append("* ").append(binding.instanceName);
                    signature.append(" [[buffer(").append(binding.index).append(")]]");
                }
                signature.append("\n) {");
                break;
                
            case FRAGMENT:
                signature.append("fragment float4 main_fragment(\n");
                signature.append("    VertexOut in [[stage_in]]");
                // Add texture/sampler parameters for buffers (simplified)
                for (var binding : bufferBindings) {
                    signature.append(",\n    ");
                    signature.append("texture2d<float> ").append(binding.instanceName);
                    signature.append(" [[texture(").append(binding.index).append(")]]");
                }
                signature.append("\n) {");
                break;
                
            case VERTEX:
                signature.append("vertex VertexOut main_vertex(\n");
                signature.append("    uint vertex_id [[vertex_id]],\n");
                signature.append("    uint instance_id [[instance_id]]");
                // Add buffer parameters
                for (var binding : bufferBindings) {
                    signature.append(",\n    ");
                    signature.append("const device ").append(binding.structName).append("* ").append(binding.instanceName);
                    signature.append(" [[buffer(").append(binding.index).append(")]]");
                }
                signature.append("\n) {");
                break;
                
            default:
                signature.append("void main() {");
                break;
        }
        
        return signature.toString();
    }
    
    private String translateLineContent(String line) {
        var result = line;
        
        // Replace built-in variables
        for (var entry : BUILTIN_VARIABLES.entrySet()) {
            result = result.replaceAll("\\b" + Pattern.quote(entry.getKey()) + "\\b", entry.getValue());
        }
        
        // Replace built-in functions - special handling for texture functions
        if (result.contains("texture(")) {
            // Handle texture() function calls specially for MSL
            result = result.replaceAll("\\btexture\\s*\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)", "$1.sample(sampler, $2)");
        }
        
        // Replace other built-in functions
        for (var entry : BUILTIN_FUNCTIONS.entrySet()) {
            if (!entry.getKey().equals("texture")) { // Skip texture, handled above
                String pattern = "\\b" + Pattern.quote(entry.getKey()) + "\\s*\\(";
                String replacement = entry.getValue() + "(";
                result = result.replaceAll(pattern, replacement);
            }
        }
        
        // Handle specific MSL syntax differences
        result = handleSpecialCases(result);
        
        return result;
    }
    
    private String handleSpecialCases(String line) {
        var result = line;
        
        // Handle atomic operations - need explicit memory ordering
        if (result.contains("atomic_fetch_add_explicit")) {
            result = result.replaceAll("atomic_fetch_add_explicit\\(([^,]+),\\s*([^)]+)\\)", 
                                     "atomic_fetch_add_explicit($1, $2, memory_order_relaxed)");
        }
        
        // Handle barrier() function
        result = result.replaceAll("\\bbarrier\\(\\)", "threadgroup_barrier(mem_flags::mem_threadgroup)");
        
        // Handle return statements in main function
        if (result.contains("return;") && !result.contains("//")) {
            result = result.replace("return;", "return;");
        }
        
        return result;
    }
    
    private int countBraces(String line) {
        var count = 0;
        for (var c : line.toCharArray()) {
            if (c == '{') count++;
            if (c == '}') count--;
        }
        return count;
    }
    
    /**
     * Shader type enumeration
     */
    public enum ShaderType {
        VERTEX, FRAGMENT, COMPUTE
    }
    
    /**
     * Gets shader type from file extension or content analysis
     */
    public static ShaderType detectShaderType(String filename, String content) {
        if (filename.endsWith(".vert")) return ShaderType.VERTEX;
        if (filename.endsWith(".frag")) return ShaderType.FRAGMENT; 
        if (filename.endsWith(".comp")) return ShaderType.COMPUTE;
        
        // Analyze content
        if (content.contains("layout(local_size_x")) return ShaderType.COMPUTE;
        if (content.contains("gl_FragCoord") || content.contains("gl_FragColor")) return ShaderType.FRAGMENT;
        if (content.contains("gl_Position") || content.contains("gl_VertexID")) return ShaderType.VERTEX;
        
        return ShaderType.COMPUTE; // Default
    }
    
    /**
     * Convenience method to translate a shader file
     */
    public String translateShaderFile(String filename, String glslContent) {
        var shaderType = detectShaderType(filename, glslContent);
        return translateShader(glslContent, shaderType);
    }
}