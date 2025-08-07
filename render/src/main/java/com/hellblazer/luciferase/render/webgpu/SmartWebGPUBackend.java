package com.hellblazer.luciferase.render.webgpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Smart WebGPU backend that automatically handles platform detection and fallback.
 * Clients don't need to know about the underlying implementation.
 */
public class SmartWebGPUBackend implements WebGPUBackend {
    private static final Logger log = LoggerFactory.getLogger(SmartWebGPUBackend.class);
    
    private WebGPUBackend delegate;
    private final Object lock = new Object();
    
    public SmartWebGPUBackend() {
        // Delegate selection happens lazily on first use
    }
    
    private WebGPUBackend getDelegate() {
        if (delegate == null) {
            synchronized (lock) {
                if (delegate == null) {
                    delegate = selectBestBackend();
                }
            }
        }
        return delegate;
    }
    
    private WebGPUBackend selectBestBackend() {
        // Check for CI environment
        if (isRunningInCI()) {
            log.info("CI environment detected, using stub WebGPU backend");
            return new StubWebGPUBackend();
        }
        
        // Check for headless environment
        if (isHeadless()) {
            log.info("Headless environment detected, using stub WebGPU backend");
            return new StubWebGPUBackend();
        }
        
        // Try native WebGPU
        try {
            var ffmBackend = new FFMWebGPUBackend();
            if (ffmBackend.isAvailable()) {
                log.info("Native WebGPU available, using FFM backend");
                return ffmBackend;
            }
        } catch (Throwable t) {
            log.debug("Native WebGPU not available: {}", t.getMessage());
        }
        
        // Default to stub
        log.info("Native WebGPU not available, using stub backend");
        return new StubWebGPUBackend();
    }
    
    private boolean isRunningInCI() {
        // Check common CI environment variables
        return System.getenv("CI") != null ||
               System.getenv("CONTINUOUS_INTEGRATION") != null ||
               System.getenv("GITHUB_ACTIONS") != null ||
               System.getenv("JENKINS_HOME") != null ||
               System.getenv("TRAVIS") != null ||
               System.getenv("CIRCLECI") != null ||
               System.getenv("GITLAB_CI") != null ||
               System.getenv("BUILDKITE") != null ||
               System.getenv("DRONE") != null;
    }
    
    private boolean isHeadless() {
        // Check if running in headless mode
        String headless = System.getProperty("java.awt.headless");
        if ("true".equalsIgnoreCase(headless)) {
            return true;
        }
        
        // Check for display on Unix-like systems
        String display = System.getenv("DISPLAY");
        if (display == null || display.isEmpty()) {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
                return true;
            }
        }
        
        return false;
    }
    
    @Override
    public boolean isAvailable() {
        return true; // Always available - we'll use stub if needed
    }
    
    @Override
    public CompletableFuture<Boolean> initialize() {
        return getDelegate().initialize()
            .exceptionally(throwable -> {
                // If initialization fails, try fallback to stub
                synchronized (lock) {
                    if (!(delegate instanceof StubWebGPUBackend)) {
                        log.warn("Primary backend initialization failed, falling back to stub: {}", 
                                throwable.getMessage());
                        delegate = new StubWebGPUBackend();
                        return delegate.initialize().join();
                    }
                }
                return false;
            });
    }
    
    @Override
    public boolean isInitialized() {
        return delegate != null && delegate.isInitialized();
    }
    
    @Override
    public BufferHandle createBuffer(long size, int usage) {
        return getDelegate().createBuffer(size, usage);
    }
    
    @Override
    public void writeBuffer(BufferHandle buffer, byte[] data, long offset) {
        getDelegate().writeBuffer(buffer, data, offset);
    }
    
    @Override
    public byte[] readBuffer(BufferHandle buffer, long size, long offset) {
        return getDelegate().readBuffer(buffer, size, offset);
    }
    
    @Override
    public ShaderHandle createComputeShader(String wgslSource) {
        return getDelegate().createComputeShader(wgslSource);
    }
    
    @Override
    public void dispatchCompute(ShaderHandle shader, int workGroupCountX, 
                                int workGroupCountY, int workGroupCountZ) {
        getDelegate().dispatchCompute(shader, workGroupCountX, workGroupCountY, workGroupCountZ);
    }
    
    @Override
    public void waitIdle() {
        if (delegate != null) {
            delegate.waitIdle();
        }
    }
    
    @Override
    public void shutdown() {
        synchronized (lock) {
            if (delegate != null) {
                try {
                    delegate.shutdown();
                } catch (Exception e) {
                    log.debug("Error during backend shutdown: {}", e.getMessage());
                }
                delegate = null;
            }
        }
    }
    
    @Override
    public String getBackendName() {
        if (delegate != null) {
            return "Smart(" + delegate.getBackendName() + ")";
        }
        return "Smart(Uninitialized)";
    }
}