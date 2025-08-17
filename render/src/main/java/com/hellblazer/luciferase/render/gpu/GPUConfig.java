package com.hellblazer.luciferase.render.gpu;

/**
 * Configuration parameters for GPU context initialization.
 * Used by both OpenGL and BGFX backends.
 */
public class GPUConfig {
    
    /**
     * GPU backend types supported by the system.
     */
    public enum Backend {
        OPENGL,
        BGFX_METAL,
        BGFX_VULKAN,
        BGFX_OPENGL,
        AUTO
    }
    
    private final Backend backend;
    private final boolean debugEnabled;
    private final boolean validationEnabled;
    private final int width;
    private final int height;
    private final boolean headless;
    
    public static class Builder {
        private Backend backend = Backend.AUTO;
        private boolean debugEnabled = false;
        private boolean validationEnabled = false;
        private int width = 800;
        private int height = 600;
        private boolean headless = false;
        
        public Builder withBackend(Backend backend) {
            this.backend = backend;
            return this;
        }
        
        public Builder withDebugEnabled(boolean debugEnabled) {
            this.debugEnabled = debugEnabled;
            return this;
        }
        
        public Builder withValidationEnabled(boolean validationEnabled) {
            this.validationEnabled = validationEnabled;
            return this;
        }
        
        public Builder withWidth(int width) {
            this.width = width;
            return this;
        }
        
        public Builder withHeight(int height) {
            this.height = height;
            return this;
        }
        
        public Builder withHeadless(boolean headless) {
            this.headless = headless;
            return this;
        }
        
        public GPUConfig build() {
            return new GPUConfig(backend, debugEnabled, validationEnabled, width, height, headless);
        }
    }
    
    private GPUConfig(Backend backend, boolean debugEnabled, boolean validationEnabled, 
                     int width, int height, boolean headless) {
        this.backend = backend;
        this.debugEnabled = debugEnabled;
        this.validationEnabled = validationEnabled;
        this.width = width;
        this.height = height;
        this.headless = headless;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static GPUConfig defaultConfig() {
        return new Builder().build();
    }
    
    public static GPUConfig headlessConfig() {
        return new Builder().withHeadless(true).build();
    }
    
    // Getters
    public Backend getBackend() { return backend; }
    public boolean isDebugEnabled() { return debugEnabled; }
    public boolean isValidationEnabled() { return validationEnabled; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isHeadless() { return headless; }
    
    @Override
    public String toString() {
        return String.format("GPUConfig{%dx%d, headless=%s, debug=%s, validation=%s, backend=%s}",
                width, height, headless, debugEnabled, validationEnabled, backend);
    }
}