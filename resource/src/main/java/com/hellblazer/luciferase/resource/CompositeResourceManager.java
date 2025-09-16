package com.hellblazer.luciferase.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Manages multiple resources as a transactional unit.
 * Provides all-or-nothing allocation with automatic rollback on failure.
 * Resources are cleaned up in reverse order of allocation (LIFO).
 */
public class CompositeResourceManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CompositeResourceManager.class);
    
    public enum State {
        INITIALIZING,
        ACTIVE,
        ROLLING_BACK,
        CLOSED,
        FAILED
    }
    
    private final String id;
    private final List<ResourceHandle<?>> resources;
    private final Map<String, ResourceHandle<?>> namedResources;
    private final ResourceTracker tracker;
    private final ReentrantLock lock;
    private final AtomicBoolean closed;
    private volatile State state;
    private volatile Exception failureCause;
    
    /**
     * Create a new composite resource manager.
     * 
     * @param tracker Optional resource tracker
     */
    public CompositeResourceManager(ResourceTracker tracker) {
        this.id = UUID.randomUUID().toString();
        this.resources = new ArrayList<>();
        this.namedResources = new ConcurrentHashMap<>();
        this.tracker = tracker;
        this.lock = new ReentrantLock();
        this.closed = new AtomicBoolean(false);
        this.state = State.INITIALIZING;
    }
    
    /**
     * Create a composite resource manager without tracking.
     */
    public CompositeResourceManager() {
        this(null);
    }
    
    /**
     * Add a resource to be managed.
     * 
     * @param resource The resource to manage
     * @param <T> The resource type
     * @return The resource for chaining
     */
    public <T extends ResourceHandle<?>> T add(T resource) {
        return add(null, resource);
    }
    
    /**
     * Add a named resource to be managed.
     * 
     * @param name The name for the resource (for lookup)
     * @param resource The resource to manage
     * @param <T> The resource type
     * @return The resource for chaining
     */
    public <T extends ResourceHandle<?>> T add(String name, T resource) {
        lock.lock();
        try {
            if (state != State.INITIALIZING && state != State.ACTIVE) {
                throw new IllegalStateException("Cannot add resources in state: " + state);
            }
            
            resources.add(resource);
            if (name != null) {
                namedResources.put(name, resource);
            }
            
            // Set cleanup callback to remove from our lists
            resource.setCleanupCallback(r -> {
                resources.remove(r);
                if (name != null) {
                    namedResources.remove(name);
                }
            });
            
            if (state == State.INITIALIZING) {
                state = State.ACTIVE;
            }
            
            log.trace("Added resource {} to composite manager {}", resource.getId(), id);
            return resource;
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Allocate a resource and add it to the manager.
     * If allocation fails, all previously allocated resources are rolled back.
     * 
     * @param allocator The resource allocator
     * @param <T> The resource type
     * @return The allocated resource
     * @throws ResourceAllocationException if allocation fails
     */
    public <T extends ResourceHandle<?>> T allocate(Supplier<T> allocator) throws ResourceAllocationException {
        return allocate(null, allocator);
    }
    
    /**
     * Allocate a named resource and add it to the manager.
     * If allocation fails, all previously allocated resources are rolled back.
     * 
     * @param name The name for the resource
     * @param allocator The resource allocator
     * @param <T> The resource type
     * @return The allocated resource
     * @throws ResourceAllocationException if allocation fails
     */
    public <T extends ResourceHandle<?>> T allocate(String name, Supplier<T> allocator) 
            throws ResourceAllocationException {
        lock.lock();
        try {
            if (state != State.INITIALIZING && state != State.ACTIVE) {
                throw new IllegalStateException("Cannot allocate resources in state: " + state);
            }
            
            try {
                T resource = allocator.get();
                if (resource == null) {
                    throw new ResourceAllocationException("Allocator returned null");
                }
                
                return add(name, resource);
                
            } catch (Exception e) {
                log.error("Failed to allocate resource in composite manager {}", id, e);
                rollback(e);
                throw new ResourceAllocationException("Resource allocation failed", e);
            }
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Execute a transactional resource allocation.
     * All resources are allocated, and if any fail, all are rolled back.
     * 
     * @param transaction The transaction to execute
     * @throws ResourceAllocationException if any allocation fails
     */
    public void transaction(ResourceTransaction transaction) throws ResourceAllocationException {
        lock.lock();
        try {
            if (state != State.INITIALIZING && state != State.ACTIVE) {
                throw new IllegalStateException("Cannot execute transaction in state: " + state);
            }
            
            int startSize = resources.size();
            
            try {
                transaction.execute(this);
                
                if (state == State.INITIALIZING) {
                    state = State.ACTIVE;
                }
                
            } catch (Exception e) {
                log.error("Transaction failed in composite manager {}", id, e);
                
                // Rollback only resources allocated during this transaction
                rollbackFrom(startSize, e);
                throw new ResourceAllocationException("Transaction failed", e);
            }
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Get a named resource.
     * 
     * @param name The resource name
     * @param <T> The expected resource type
     * @return The resource, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T extends ResourceHandle<?>> T get(String name) {
        return (T) namedResources.get(name);
    }
    
    /**
     * Get a named resource, throwing if not found.
     * 
     * @param name The resource name
     * @param type The expected resource type
     * @param <T> The expected resource type
     * @return The resource
     * @throws IllegalArgumentException if not found or wrong type
     */
    public <T extends ResourceHandle<?>> T require(String name, Class<T> type) {
        var resource = namedResources.get(name);
        if (resource == null) {
            throw new IllegalArgumentException("Resource not found: " + name);
        }
        if (!type.isInstance(resource)) {
            throw new IllegalArgumentException(
                "Resource " + name + " is not of type " + type.getSimpleName()
            );
        }
        return type.cast(resource);
    }
    
    /**
     * Check if all managed resources are still valid.
     * 
     * @return true if all resources are valid
     */
    public boolean areAllValid() {
        return resources.stream().allMatch(ResourceHandle::isValid);
    }
    
    /**
     * Get the number of managed resources.
     */
    public int size() {
        return resources.size();
    }
    
    /**
     * Get the current state.
     */
    public State getState() {
        return state;
    }
    
    /**
     * Get the failure cause if in FAILED state.
     */
    public Exception getFailureCause() {
        return failureCause;
    }
    
    /**
     * Rollback all resources due to failure.
     */
    private void rollback(Exception cause) {
        rollbackFrom(0, cause);
    }
    
    /**
     * Rollback resources starting from a specific index.
     */
    private void rollbackFrom(int startIndex, Exception cause) {
        state = State.ROLLING_BACK;
        failureCause = cause;
        
        log.warn("Rolling back {} resources in composite manager {} due to: {}", 
            resources.size() - startIndex, id, cause.getMessage());
        
        // Close resources in reverse order (LIFO)
        for (int i = resources.size() - 1; i >= startIndex; i--) {
            var resource = resources.get(i);
            try {
                resource.close();
            } catch (Exception e) {
                log.error("Failed to rollback resource {} in composite manager {}", 
                    resource.getId(), id, e);
            }
        }
        
        // Remove rolled back resources
        if (startIndex > 0) {
            resources.subList(startIndex, resources.size()).clear();
        } else {
            resources.clear();
            namedResources.clear();
        }
        
        state = State.FAILED;
    }
    
    /**
     * Close all managed resources in reverse order of allocation.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        
        lock.lock();
        try {
            if (state == State.CLOSED) {
                return;
            }
            
            log.debug("Closing {} resources in composite manager {}", resources.size(), id);
            
            // Close in reverse order (LIFO)
            var exceptions = new ArrayList<Exception>();
            for (int i = resources.size() - 1; i >= 0; i--) {
                var resource = resources.get(i);
                try {
                    resource.close();
                } catch (Exception e) {
                    log.error("Failed to close resource {} in composite manager {}", 
                        resource.getId(), id, e);
                    exceptions.add(e);
                }
            }
            
            resources.clear();
            namedResources.clear();
            state = State.CLOSED;
            
            if (!exceptions.isEmpty()) {
                var combined = new RuntimeException(
                    "Failed to close " + exceptions.size() + " resources"
                );
                exceptions.forEach(combined::addSuppressed);
                throw combined;
            }
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Interface for transactional resource allocation.
     */
    @FunctionalInterface
    public interface ResourceTransaction {
        void execute(CompositeResourceManager manager) throws Exception;
    }
    
    /**
     * Exception thrown when resource allocation fails.
     */
    public static class ResourceAllocationException extends Exception {
        public ResourceAllocationException(String message) {
            super(message);
        }
        
        public ResourceAllocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    @Override
    public String toString() {
        return String.format("CompositeResourceManager[id=%s, state=%s, resources=%d]",
            id, state, resources.size());
    }
}