package com.hellblazer.luciferase.render.gpu.bgfx;

import com.hellblazer.luciferase.render.gpu.IGPUShader;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.system.MemoryUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BGFX implementation of IGPUShader for compute shader operations.
 * Handles GLSL to Metal shader compilation and uniform management.
 */
public class BGFXGPUShader implements IGPUShader {
    
    private final int id;
    private final AtomicBoolean valid = new AtomicBoolean(false);
    private final Map<String, Object> uniforms = new HashMap<>();
    
    private short bgfxHandle = BGFX.BGFX_INVALID_HANDLE;
    private String compilationLog = "";
    private int[] workGroupSize = {1, 1, 1};
    
    public BGFXGPUShader(int id) {
        this.id = id;
    }
    
    @Override
    public boolean compile(String source, Map<String, String> defines) {
        if (valid.get()) {
            return true; // Already compiled
        }
        
        try {
            // Preprocess shader source with defines
            String processedSource = preprocessShader(source, defines);
            
            // Convert GLSL compute shader to Metal (simplified for now)
            // In a real implementation, this would use shaderc or similar
            byte[] shaderBytecode = compileToMetal(processedSource);
            
            if (shaderBytecode == null) {
                compilationLog = "Failed to compile shader to Metal bytecode";
                return false;
            }
            
            // Create BGFX shader from bytecode
            var shaderData = MemoryUtil.memAlloc(shaderBytecode.length);
            try {
                shaderData.put(shaderBytecode).flip();
                bgfxHandle = BGFX.bgfx_create_shader(BGFX.bgfx_make_ref(shaderData));
                
                if (bgfxHandle == BGFX.BGFX_INVALID_HANDLE) {
                    compilationLog = "Failed to create BGFX shader handle";
                    return false;
                }
                
                // Extract work group size from source (basic parsing)
                extractWorkGroupSize(processedSource);
                
                valid.set(true);
                compilationLog = "Shader compiled successfully";
                return true;
                
            } finally {
                MemoryUtil.memFree(shaderData);
            }
            
        } catch (Exception e) {
            compilationLog = "Compilation error: " + e.getMessage();
            return false;
        }
    }
    
    @Override
    public void setUniform(String name, Object value) {
        uniforms.put(name, value);
        
        // In a real implementation, we would bind the uniform to the shader
        // BGFX uses bgfx_set_uniform for this purpose
        // For now, we just store the value
    }
    
    @Override
    public void setUniformVector(String name, float... values) {
        setUniform(name, values);
    }
    
    @Override
    public void setUniformMatrix(String name, float[] matrix) {
        setUniform(name, matrix);
    }
    
    @Override
    public void setUniformInt(String name, int value) {
        setUniform(name, value);
    }
    
    @Override
    public void setUniformFloat(String name, float value) {
        setUniform(name, value);
    }
    
    @Override
    public boolean isValid() {
        return valid.get();
    }
    
    @Override
    public String getCompilationLog() {
        return compilationLog;
    }
    
    @Override
    public int[] getWorkGroupSize() {
        return workGroupSize.clone();
    }
    
    @Override
    public Object getNativeHandle() {
        return bgfxHandle;
    }
    
    @Override
    public boolean hasUniform(String name) {
        return uniforms.containsKey(name);
    }
    
    @Override
    public Map<String, String> getUniforms() {
        var result = new HashMap<String, String>();
        for (var entry : uniforms.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getClass().getSimpleName());
        }
        return result;
    }
    
    @Override
    public void destroy() {
        if (!valid.compareAndSet(true, false)) {
            return; // Already destroyed or never compiled
        }
        
        // Destroy BGFX shader
        if (bgfxHandle != BGFX.BGFX_INVALID_HANDLE) {
            BGFX.bgfx_destroy_shader(bgfxHandle);
            bgfxHandle = BGFX.BGFX_INVALID_HANDLE;
        }
        
        // Clear uniforms
        uniforms.clear();
    }
    
    /**
     * Get the internal shader ID.
     */
    public int getId() {
        return id;
    }
    
    /**
     * Get the BGFX shader handle.
     */
    public short getHandle() {
        return bgfxHandle;
    }
    
    /**
     * Preprocess shader source with defines.
     */
    private String preprocessShader(String source, Map<String, String> defines) {
        var result = new StringBuilder(source);
        
        // Insert defines at the beginning (after #version if present)
        var insertPos = 0;
        if (source.startsWith("#version")) {
            insertPos = source.indexOf('\n') + 1;
        }
        
        if (defines != null && !defines.isEmpty()) {
            var definesText = new StringBuilder();
            for (var entry : defines.entrySet()) {
                definesText.append("#define ").append(entry.getKey());
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    definesText.append(" ").append(entry.getValue());
                }
                definesText.append("\n");
            }
            result.insert(insertPos, definesText);
        }
        
        return result.toString();
    }
    
    /**
     * Compile GLSL compute shader to Metal bytecode.
     * This is a placeholder - real implementation would use shaderc.
     */
    private byte[] compileToMetal(String glslSource) {
        // In a real implementation, this would:
        // 1. Use shaderc to compile GLSL to SPIR-V
        // 2. Use spirv-cross to convert SPIR-V to Metal
        // 3. Use Metal compiler to create bytecode
        
        // For now, return a dummy bytecode that BGFX will reject
        // This allows us to test the infrastructure without actual compilation
        return null; // Deliberately fail compilation for now
    }
    
    /**
     * Extract work group size from shader source.
     */
    private void extractWorkGroupSize(String source) {
        // Look for layout(local_size_x = X, local_size_y = Y, local_size_z = Z) in
        var layoutStart = source.indexOf("layout(");
        if (layoutStart == -1) {
            return;
        }
        
        var layoutEnd = source.indexOf(")", layoutStart);
        if (layoutEnd == -1) {
            return;
        }
        
        var layoutDecl = source.substring(layoutStart + 7, layoutEnd);
        
        // Parse local_size_x, local_size_y, local_size_z
        parseWorkGroupDimension(layoutDecl, "local_size_x", 0);
        parseWorkGroupDimension(layoutDecl, "local_size_y", 1);
        parseWorkGroupDimension(layoutDecl, "local_size_z", 2);
    }
    
    /**
     * Parse a single work group dimension from layout declaration.
     */
    private void parseWorkGroupDimension(String layoutDecl, String dimension, int index) {
        var dimStart = layoutDecl.indexOf(dimension + " = ");
        if (dimStart == -1) {
            return;
        }
        
        dimStart += dimension.length() + 3; // Skip "dimension = "
        var dimEnd = dimStart;
        while (dimEnd < layoutDecl.length() && Character.isDigit(layoutDecl.charAt(dimEnd))) {
            dimEnd++;
        }
        
        if (dimEnd > dimStart) {
            try {
                workGroupSize[index] = Integer.parseInt(layoutDecl.substring(dimStart, dimEnd));
            } catch (NumberFormatException e) {
                // Keep default value
            }
        }
    }
}