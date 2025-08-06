package com.hellblazer.luciferase.webgpu.platform;

/**
 * Exception thrown when the current platform is not supported for WebGPU.
 */
public class UnsupportedPlatformException extends RuntimeException {
    
    public UnsupportedPlatformException(String message) {
        super(message);
    }
    
    public UnsupportedPlatformException(String message, Throwable cause) {
        super(message, cause);
    }
}