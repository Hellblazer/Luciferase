package com.hellblazer.luciferase.resource;

/**
 * Listener interface for resource lifecycle events.
 */
public interface ResourceLifecycleListener {
    /**
     * Called when a resource is created.
     */
    void onResourceCreated(GPUResource resource);
    
    /**
     * Called when a resource is activated.
     */
    void onResourceActivated(GPUResource resource);
    
    /**
     * Called when a resource is closed.
     */
    void onResourceClosed(GPUResource resource);
}