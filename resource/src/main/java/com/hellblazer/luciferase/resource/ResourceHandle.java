package com.hellblazer.luciferase.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Base class for all managed resources implementing RAII pattern.
 * Provides automatic cleanup, leak detection, and lifecycle management.
 * 
 * @param <T> The type of the underlying native resource (e.g., Long for handles, ByteBuffer for memory)
 */
public abstract class ResourceHandle<T> implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ResourceHandle.class);
    
    public enum State {
        ALLOCATED,
        CLOSING,
        CLOSED,
        LEAKED
    }
    
    private final String id;
    private final long allocationTime;
    private final String allocationStack;
    private final AtomicReference<State> state;
    private final AtomicBoolean cleanupExecuted;
    private volatile T resource;
    private volatile Consumer<ResourceHandle<T>> cleanupCallback;
    private volatile ResourceTracker tracker;
    
    /**
     * Create a new resource handle.
     * 
     * @param resource The underlying native resource
     * @param tracker Optional tracker for leak detection
     */
    protected ResourceHandle(T resource, ResourceTracker tracker) {
        this.id = UUID.randomUUID().toString();
        this.resource = resource;
        this.tracker = tracker;
        this.allocationTime = System.nanoTime();
        this.state = new AtomicReference<>(State.ALLOCATED);
        this.cleanupExecuted = new AtomicBoolean(false);
        
        // Capture allocation stack for debugging
        if (log.isDebugEnabled()) {
            var stackTrace = Thread.currentThread().getStackTrace();
            var sb = new StringBuilder();
            for (int i = 2; i < Math.min(stackTrace.length, 10); i++) {
                sb.append("\n\tat ").append(stackTrace[i]);
            }
            this.allocationStack = sb.toString();
        } else {
            this.allocationStack = null;
        }
        
        // Register with tracker if provided
        if (tracker != null) {
            tracker.register(this);
        }
        
        log.trace("Allocated resource {} of type {}", id, getClass().getSimpleName());
    }
    
    /**
     * Get the underlying resource.
     * 
     * @return The resource, or null if closed
     * @throws IllegalStateException if the resource has been closed
     */
    public T get() {
        var currentState = state.get();
        if (currentState != State.ALLOCATED) {
            throw new IllegalStateException(
                "Cannot access resource " + id + " in state " + currentState
            );
        }
        return resource;
    }
    
    /**
     * Check if the resource is still valid (not closed).
     */
    public boolean isValid() {
        return state.get() == State.ALLOCATED;
    }
    
    /**
     * Set a cleanup callback to be executed when the resource is closed.
     * 
     * @param callback The cleanup callback
     */
    public void setCleanupCallback(Consumer<ResourceHandle<T>> callback) {
        this.cleanupCallback = callback;
    }
    
    /**
     * Close the resource and release native memory/handles.
     * This method is idempotent and thread-safe.
     */
    @Override
    public void close() {
        // Transition to CLOSING state
        if (!state.compareAndSet(State.ALLOCATED, State.CLOSING)) {
            // Already closed or closing
            return;
        }
        
        try {
            // Execute cleanup callback if set
            if (cleanupCallback != null && cleanupExecuted.compareAndSet(false, true)) {
                try {
                    cleanupCallback.accept(this);
                } catch (Exception e) {
                    log.error("Error in cleanup callback for resource {}", id, e);
                }
            }
            
            // Perform actual resource cleanup
            if (resource != null) {
                doCleanup(resource);
                resource = null;
            }
            
            // Unregister from tracker
            if (tracker != null) {
                tracker.unregister(this);
            }
            
            // Transition to CLOSED state
            state.set(State.CLOSED);
            
            log.trace("Closed resource {} of type {}", id, getClass().getSimpleName());
            
        } catch (Exception e) {
            log.error("Error closing resource {}", id, e);
            state.set(State.LEAKED);
            throw new RuntimeException("Failed to close resource " + id, e);
        }
    }
    
    /**
     * Perform the actual cleanup of the native resource.
     * Subclasses must implement this to release their specific resource type.
     * 
     * @param resource The resource to cleanup
     */
    protected abstract void doCleanup(T resource);
    
    /**
     * Get the unique identifier for this resource.
     */
    public String getId() {
        return id;
    }
    
    /**
     * Get the allocation time in nanoseconds.
     */
    public long getAllocationTime() {
        return allocationTime;
    }
    
    /**
     * Get the current state of the resource.
     */
    public State getState() {
        return state.get();
    }
    
    /**
     * Get the allocation stack trace (if debug logging is enabled).
     */
    public String getAllocationStack() {
        return allocationStack;
    }
    
    /**
     * Get the age of this resource in milliseconds.
     */
    public long getAgeMillis() {
        return (System.nanoTime() - allocationTime) / 1_000_000;
    }
    
    /**
     * Mark this resource as leaked (called by tracker on shutdown).
     */
    void markLeaked() {
        state.set(State.LEAKED);
        if (allocationStack != null) {
            log.error("Resource {} leaked! Allocated at:{}", id, allocationStack);
        } else {
            log.error("Resource {} leaked! Enable debug logging to see allocation stack", id);
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        // Last-resort cleanup if not properly closed
        if (state.get() == State.ALLOCATED) {
            log.error("Resource {} was not properly closed before finalization", id);
            markLeaked();
            close();
        }
        super.finalize();
    }
    
    @Override
    public String toString() {
        return String.format("%s[id=%s, state=%s, age=%dms]",
            getClass().getSimpleName(), id, state.get(), getAgeMillis());
    }
}