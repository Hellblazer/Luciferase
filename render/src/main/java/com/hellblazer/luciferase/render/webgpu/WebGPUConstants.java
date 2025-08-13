package com.hellblazer.luciferase.render.webgpu;

/**
 * WebGPU constants for the render module.
 * Provides constants that are not yet defined in the webgpu-ffm module.
 */
public class WebGPUConstants {
    
    /**
     * Color write mask constants.
     */
    public static class ColorWriteMask {
        public static final int NONE = 0x0;
        public static final int RED = 0x1;
        public static final int GREEN = 0x2;
        public static final int BLUE = 0x4;
        public static final int ALPHA = 0x8;
        public static final int ALL = 0xF;
    }
    
    
    /**
     * Shader stage constants.
     */
    public static class ShaderStage {
        public static final int NONE = 0;
        public static final int VERTEX = 1;
        public static final int FRAGMENT = 2;
        public static final int COMPUTE = 4;
    }
    
    /**
     * Buffer binding type constants.
     */
    public enum BufferBindingType {
        UNIFORM(0),
        STORAGE(1),
        READ_ONLY_STORAGE(2);
        
        private final int value;
        
        BufferBindingType(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
}