package com.hellblazer.luciferase.render.webgpu.resources;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.wrapper.Device.BufferDescriptor;
import com.hellblazer.luciferase.webgpu.builder.WebGPUBuilder.BufferUsage;
import org.joml.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages uniform buffers for WebGPU shaders.
 * Provides typed access to uniform data with automatic GPU synchronization.
 */
public class UniformBufferManager {
    private static final Logger log = LoggerFactory.getLogger(UniformBufferManager.class);
    
    /**
     * Represents a managed uniform buffer.
     */
    public class UniformBuffer {
        private final String name;
        private final Buffer gpuBuffer;
        private final ByteBuffer cpuBuffer;
        private final long size;
        private final long alignedSize;
        private boolean dirty = false;
        private final Map<String, UniformField> fields = new HashMap<>();
        
        UniformBuffer(String name, long size) {
            this.name = name;
            this.size = size;
            this.alignedSize = alignTo256(size);
            this.cpuBuffer = ByteBuffer.allocateDirect((int) alignedSize)
                .order(ByteOrder.nativeOrder());
            
            // Create GPU buffer
            BufferDescriptor desc = new BufferDescriptor(alignedSize, 
                BufferUsage.UNIFORM.getValue() | BufferUsage.COPY_DST.getValue())
                .withMappedAtCreation(false)
                .withLabel("Uniform_" + name);
            this.gpuBuffer = device.createBuffer(desc);
        }
        
        /**
         * Define a field in the uniform buffer.
         */
        public UniformBuffer defineField(String fieldName, int offset, UniformType type) {
            fields.put(fieldName, new UniformField(offset, type));
            return this;
        }
        
        /**
         * Set a float value.
         */
        public void setFloat(String fieldName, float value) {
            UniformField field = fields.get(fieldName);
            if (field == null) {
                log.warn("Unknown field: {}", fieldName);
                return;
            }
            setFloat(field.offset, value);
        }
        
        public void setFloat(int offset, float value) {
            cpuBuffer.putFloat(offset, value);
            dirty = true;
        }
        
        /**
         * Set a vec2 value.
         */
        public void setVec2(String fieldName, Vector2f value) {
            UniformField field = fields.get(fieldName);
            if (field == null) {
                log.warn("Unknown field: {}", fieldName);
                return;
            }
            setVec2(field.offset, value);
        }
        
        public void setVec2(int offset, Vector2f value) {
            cpuBuffer.putFloat(offset, value.x);
            cpuBuffer.putFloat(offset + 4, value.y);
            dirty = true;
        }
        
        /**
         * Set a vec3 value.
         */
        public void setVec3(String fieldName, Vector3f value) {
            UniformField field = fields.get(fieldName);
            if (field == null) {
                log.warn("Unknown field: {}", fieldName);
                return;
            }
            setVec3(field.offset, value);
        }
        
        public void setVec3(int offset, Vector3f value) {
            cpuBuffer.putFloat(offset, value.x);
            cpuBuffer.putFloat(offset + 4, value.y);
            cpuBuffer.putFloat(offset + 8, value.z);
            dirty = true;
        }
        
        /**
         * Set a vec4 value.
         */
        public void setVec4(String fieldName, Vector4f value) {
            UniformField field = fields.get(fieldName);
            if (field == null) {
                log.warn("Unknown field: {}", fieldName);
                return;
            }
            setVec4(field.offset, value);
        }
        
        public void setVec4(int offset, Vector4f value) {
            cpuBuffer.putFloat(offset, value.x);
            cpuBuffer.putFloat(offset + 4, value.y);
            cpuBuffer.putFloat(offset + 8, value.z);
            cpuBuffer.putFloat(offset + 12, value.w);
            dirty = true;
        }
        
        /**
         * Set a mat4 value.
         */
        public void setMat4(String fieldName, Matrix4f value) {
            UniformField field = fields.get(fieldName);
            if (field == null) {
                log.warn("Unknown field: {}", fieldName);
                return;
            }
            setMat4(field.offset, value);
        }
        
        public void setMat4(int offset, Matrix4f value) {
            FloatBuffer fb = cpuBuffer.asFloatBuffer();
            fb.position(offset / 4);
            value.get(fb);
            dirty = true;
        }
        
        /**
         * Set a mat3 value.
         */
        public void setMat3(String fieldName, Matrix3f value) {
            UniformField field = fields.get(fieldName);
            if (field == null) {
                log.warn("Unknown field: {}", fieldName);
                return;
            }
            setMat3(field.offset, value);
        }
        
        public void setMat3(int offset, Matrix3f value) {
            FloatBuffer fb = cpuBuffer.asFloatBuffer();
            fb.position(offset / 4);
            value.get(fb);
            dirty = true;
        }
        
        /**
         * Set raw bytes.
         */
        public void setBytes(int offset, byte[] data) {
            cpuBuffer.position(offset);
            cpuBuffer.put(data);
            cpuBuffer.position(0);
            dirty = true;
        }
        
        /**
         * Update the GPU buffer if dirty.
         */
        public void update() {
            if (!dirty) {
                return;
            }
            
            cpuBuffer.rewind();
            queue.writeBuffer(gpuBuffer, 0, cpuBuffer);
            dirty = false;
            log.debug("Updated uniform buffer '{}'", name);
        }
        
        /**
         * Force update even if not dirty.
         */
        public void forceUpdate() {
            cpuBuffer.rewind();
            queue.writeBuffer(gpuBuffer, 0, cpuBuffer);
            dirty = false;
        }
        
        /**
         * Clear the buffer to zeros.
         */
        public void clear() {
            cpuBuffer.clear();
            for (int i = 0; i < cpuBuffer.capacity(); i++) {
                cpuBuffer.put(i, (byte) 0);
            }
            dirty = true;
        }
        
        public Buffer getGpuBuffer() { return gpuBuffer; }
        public long getSize() { return size; }
        public long getAlignedSize() { return alignedSize; }
        public boolean isDirty() { return dirty; }
    }
    
    /**
     * Represents a field in a uniform buffer.
     */
    private static class UniformField {
        final int offset;
        final UniformType type;
        
        UniformField(int offset, UniformType type) {
            this.offset = offset;
            this.type = type;
        }
    }
    
    /**
     * Uniform data types.
     */
    public enum UniformType {
        FLOAT(4),
        VEC2(8),
        VEC3(12),
        VEC4(16),
        MAT3(36),
        MAT4(64),
        INT(4),
        IVEC2(8),
        IVEC3(12),
        IVEC4(16);
        
        public final int size;
        
        UniformType(int size) {
            this.size = size;
        }
    }
    
    private final Device device;
    private final Queue queue;
    private final Map<String, UniformBuffer> uniforms = new HashMap<>();
    
    public UniformBufferManager(Device device, Queue queue) {
        this.device = device;
        this.queue = queue;
    }
    
    /**
     * Create a new uniform buffer.
     */
    public UniformBuffer createUniform(String name, long size) {
        if (uniforms.containsKey(name)) {
            log.warn("Uniform buffer '{}' already exists", name);
            return uniforms.get(name);
        }
        
        UniformBuffer buffer = new UniformBuffer(name, size);
        uniforms.put(name, buffer);
        log.info("Created uniform buffer '{}' with size {}", name, size);
        return buffer;
    }
    
    /**
     * Create a uniform buffer with field definitions.
     */
    public UniformBuffer createStructuredUniform(String name, UniformStructure structure) {
        UniformBuffer buffer = createUniform(name, structure.getTotalSize());
        
        // Define all fields
        for (UniformStructure.Field field : structure.getFields()) {
            buffer.defineField(field.name, field.offset, field.type);
        }
        
        return buffer;
    }
    
    /**
     * Get an existing uniform buffer.
     */
    public UniformBuffer getUniform(String name) {
        return uniforms.get(name);
    }
    
    /**
     * Update all dirty uniform buffers.
     */
    public void updateAll() {
        int updated = 0;
        for (UniformBuffer buffer : uniforms.values()) {
            if (buffer.isDirty()) {
                buffer.update();
                updated++;
            }
        }
        if (updated > 0) {
            log.debug("Updated {} uniform buffers", updated);
        }
    }
    
    /**
     * Force update all uniform buffers.
     */
    public void forceUpdateAll() {
        for (UniformBuffer buffer : uniforms.values()) {
            buffer.forceUpdate();
        }
        log.debug("Force updated all {} uniform buffers", uniforms.size());
    }
    
    /**
     * Create standard camera uniforms.
     */
    public UniformBuffer createCameraUniforms() {
        return createUniform("camera", 256)
            .defineField("viewProjection", 0, UniformType.MAT4)
            .defineField("view", 64, UniformType.MAT4)
            .defineField("projection", 128, UniformType.MAT4)
            .defineField("cameraPosition", 192, UniformType.VEC3)
            .defineField("time", 204, UniformType.FLOAT);
    }
    
    /**
     * Create standard lighting uniforms.
     */
    public UniformBuffer createLightingUniforms() {
        return createUniform("lighting", 128)
            .defineField("lightDirection", 0, UniformType.VEC3)
            .defineField("lightColor", 16, UniformType.VEC3)
            .defineField("ambientColor", 32, UniformType.VEC3)
            .defineField("ambientStrength", 44, UniformType.FLOAT)
            .defineField("diffuseStrength", 48, UniformType.FLOAT)
            .defineField("specularStrength", 52, UniformType.FLOAT)
            .defineField("shininess", 56, UniformType.FLOAT);
    }
    
    /**
     * Create standard material uniforms.
     */
    public UniformBuffer createMaterialUniforms() {
        return createUniform("material", 64)
            .defineField("baseColor", 0, UniformType.VEC4)
            .defineField("metallic", 16, UniformType.FLOAT)
            .defineField("roughness", 20, UniformType.FLOAT)
            .defineField("emissive", 24, UniformType.VEC3)
            .defineField("opacity", 36, UniformType.FLOAT);
    }
    
    /**
     * Helper to align size to 256 bytes (common GPU alignment).
     */
    private long alignTo256(long size) {
        return ((size + 255) / 256) * 256;
    }
    
    /**
     * Clean up all uniform buffers.
     */
    public void cleanup() {
        log.info("Cleaning up {} uniform buffers", uniforms.size());
        // Buffer cleanup - destroy() not available in current wrapper
        uniforms.clear();
    }
    
    /**
     * Structure definition for uniforms.
     */
    public static class UniformStructure {
        public static class Field {
            public final String name;
            public final int offset;
            public final UniformType type;
            
            public Field(String name, int offset, UniformType type) {
                this.name = name;
                this.offset = offset;
                this.type = type;
            }
        }
        
        private final List<Field> fields = new ArrayList<>();
        private int currentOffset = 0;
        
        public UniformStructure addField(String name, UniformType type) {
            // Align offset based on type
            int alignment = getAlignment(type);
            currentOffset = ((currentOffset + alignment - 1) / alignment) * alignment;
            
            fields.add(new Field(name, currentOffset, type));
            currentOffset += type.size;
            
            return this;
        }
        
        public List<Field> getFields() {
            return fields;
        }
        
        public int getTotalSize() {
            // Align to 16 bytes (vec4 alignment)
            return ((currentOffset + 15) / 16) * 16;
        }
        
        private int getAlignment(UniformType type) {
            switch (type) {
                case FLOAT:
                case INT:
                    return 4;
                case VEC2:
                case IVEC2:
                    return 8;
                case VEC3:
                case VEC4:
                case IVEC3:
                case IVEC4:
                    return 16;
                case MAT3:
                case MAT4:
                    return 16;
                default:
                    return 4;
            }
        }
    }
}