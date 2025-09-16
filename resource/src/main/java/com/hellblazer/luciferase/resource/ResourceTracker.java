package com.hellblazer.luciferase.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Tracks all active resources for leak detection and monitoring.
 * Provides statistics and can detect resources that have been alive too long.
 */
public class ResourceTracker {
    private static final Logger log = LoggerFactory.getLogger(ResourceTracker.class);
    
    // Global tracker instance for convenience
    private static volatile ResourceTracker globalTracker = null;
    
    private final Map<String, ResourceHandle<?>> activeResources = new ConcurrentHashMap<>();
    private final AtomicLong totalAllocated = new AtomicLong(0);
    private final AtomicLong totalFreed = new AtomicLong(0);
    private final AtomicLong totalLeaked = new AtomicLong(0);
    private final long maxAgeMillis;
    private final boolean enablePeriodicCheck;
    
    private volatile ScheduledFuture<?> periodicCheckFuture;
    private volatile boolean shutdown = false;
    
    /**
     * Get or create the global resource tracker instance.
     * 
     * @return The global resource tracker
     */
    public static ResourceTracker getGlobalTracker() {
        if (globalTracker == null) {
            synchronized (ResourceTracker.class) {
                if (globalTracker == null) {
                    globalTracker = new ResourceTracker();
                }
            }
        }
        return globalTracker;
    }
    
    /**
     * Set the global resource tracker instance.
     * 
     * @param tracker The tracker to use globally (null to disable)
     */
    public static void setGlobalTracker(ResourceTracker tracker) {
        globalTracker = tracker;
    }
    
    /**
     * Create a resource tracker with default settings (no periodic checking).
     */
    public ResourceTracker() {
        this(0, false);
    }
    
    /**
     * Create a resource tracker with configurable periodic checking.
     * 
     * @param maxAgeMillis Maximum age before a resource is considered potentially leaked (0 = disabled)
     * @param enablePeriodicCheck Enable periodic leak checking
     */
    public ResourceTracker(long maxAgeMillis, boolean enablePeriodicCheck) {
        this.maxAgeMillis = maxAgeMillis;
        this.enablePeriodicCheck = enablePeriodicCheck;
        
        // Register shutdown hook for leak detection
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "ResourceTracker-Shutdown"));
    }
    
    /**
     * Register a resource for tracking.
     * 
     * @param resource The resource to track
     */
    public void register(ResourceHandle<?> resource) {
        if (shutdown) {
            log.warn("Attempting to register resource after tracker shutdown: {}", resource.getId());
            return;
        }
        
        activeResources.put(resource.getId(), resource);
        totalAllocated.incrementAndGet();
        
        log.trace("Registered resource: {}", resource);
    }
    
    /**
     * Convenience method alias for register.
     */
    public void track(ResourceHandle<?> resource) {
        register(resource);
    }
    
    /**
     * Unregister a resource (called when properly closed).
     * 
     * @param resource The resource to unregister
     */
    public void unregister(ResourceHandle<?> resource) {
        if (activeResources.remove(resource.getId()) != null) {
            totalFreed.incrementAndGet();
            log.trace("Unregistered resource: {}", resource);
        }
    }
    
    /**
     * Convenience method alias for unregister.
     */
    public void untrack(ResourceHandle<?> resource) {
        unregister(resource);
    }
    
    /**
     * Start periodic checking for old resources.
     * 
     * @param executor The executor to use for periodic checks
     * @param periodSeconds Period between checks in seconds
     */
    public void startPeriodicCheck(ScheduledExecutorService executor, long periodSeconds) {
        if (!enablePeriodicCheck || maxAgeMillis <= 0) {
            log.debug("Periodic checking disabled");
            return;
        }
        
        if (periodicCheckFuture != null) {
            periodicCheckFuture.cancel(false);
        }
        
        periodicCheckFuture = executor.scheduleWithFixedDelay(
            this::checkForOldResources,
            periodSeconds,
            periodSeconds,
            TimeUnit.SECONDS
        );
        
        log.info("Started periodic resource checking every {} seconds", periodSeconds);
    }
    
    /**
     * Stop periodic checking.
     */
    public void stopPeriodicCheck() {
        if (periodicCheckFuture != null) {
            periodicCheckFuture.cancel(false);
            periodicCheckFuture = null;
            log.info("Stopped periodic resource checking");
        }
    }
    
    /**
     * Check for resources that have been alive too long.
     */
    private void checkForOldResources() {
        if (maxAgeMillis <= 0) {
            return;
        }
        
        var oldResources = activeResources.values().stream()
            .filter(r -> r.getAgeMillis() > maxAgeMillis)
            .collect(Collectors.toList());
        
        if (!oldResources.isEmpty()) {
            log.warn("Found {} resources older than {} ms:", oldResources.size(), maxAgeMillis);
            for (var resource : oldResources) {
                log.warn("  - {} (age: {} ms)", resource, resource.getAgeMillis());
                if (resource.getAllocationStack() != null) {
                    log.debug("    Allocated at:{}", resource.getAllocationStack());
                }
            }
        }
    }
    
    /**
     * Get the current number of active resources.
     */
    public int getActiveCount() {
        return activeResources.size();
    }
    
    /**
     * Get the total number of resources allocated.
     */
    public long getTotalAllocated() {
        return totalAllocated.get();
    }
    
    /**
     * Get the total number of resources freed.
     */
    public long getTotalFreed() {
        return totalFreed.get();
    }
    
    /**
     * Get the total number of leaked resources detected.
     */
    public long getTotalLeaked() {
        return totalLeaked.get();
    }
    
    /**
     * Get a snapshot of all active resources.
     * 
     * @return Set of active resource IDs
     */
    public Set<String> getActiveResourceIds() {
        return Set.copyOf(activeResources.keySet());
    }
    
    /**
     * Get detailed information about an active resource.
     * 
     * @param resourceId The resource ID
     * @return The resource handle, or null if not found
     */
    public ResourceHandle<?> getResource(String resourceId) {
        return activeResources.get(resourceId);
    }
    
    /**
     * Force close all active resources (emergency cleanup).
     * 
     * @return Number of resources closed
     */
    public int forceCloseAll() {
        log.warn("Force closing {} active resources", activeResources.size());
        
        int closed = 0;
        for (var resource : activeResources.values()) {
            try {
                resource.close();
                closed++;
            } catch (Exception e) {
                log.error("Failed to force close resource {}", resource.getId(), e);
            }
        }
        
        return closed;
    }
    
    /**
     * Generate a report of current resource usage.
     * 
     * @return A formatted report string
     */
    public String generateReport() {
        var report = new StringBuilder();
        report.append("Resource Tracker Report\n");
        report.append("=======================\n");
        report.append(String.format("Active Resources: %d\n", getActiveCount()));
        report.append(String.format("Total Allocated: %d\n", getTotalAllocated()));
        report.append(String.format("Total Freed: %d\n", getTotalFreed()));
        report.append(String.format("Total Leaked: %d\n", getTotalLeaked()));
        
        if (!activeResources.isEmpty()) {
            report.append("\nActive Resources:\n");
            
            // Group by type
            var byType = activeResources.values().stream()
                .collect(Collectors.groupingBy(r -> r.getClass().getSimpleName()));
            
            for (var entry : byType.entrySet()) {
                report.append(String.format("  %s: %d\n", entry.getKey(), entry.getValue().size()));
                
                // Show oldest resources of this type
                var oldest = entry.getValue().stream()
                    .sorted((a, b) -> Long.compare(a.getAllocationTime(), b.getAllocationTime()))
                    .limit(3)
                    .collect(Collectors.toList());
                
                for (var resource : oldest) {
                    report.append(String.format("    - %s (age: %d ms)\n",
                        resource.getId(), resource.getAgeMillis()));
                }
            }
        }
        
        return report.toString();
    }
    
    /**
     * Shutdown the tracker and check for leaks.
     */
    public void shutdown() {
        if (shutdown) {
            return;
        }
        
        shutdown = true;
        stopPeriodicCheck();
        
        if (!activeResources.isEmpty()) {
            log.error("RESOURCE LEAK DETECTED: {} resources were not properly closed", activeResources.size());
            
            // Mark all remaining resources as leaked
            for (var resource : activeResources.values()) {
                resource.markLeaked();
                totalLeaked.incrementAndGet();
            }
            
            // Log detailed leak report
            log.error("\n{}", generateReport());
            
            // Optionally force close (configurable)
            if (Boolean.getBoolean("resource.tracker.forceCloseOnShutdown")) {
                forceCloseAll();
            }
        } else {
            log.info("Resource tracker shutdown cleanly - no leaks detected");
        }
    }
    
    /**
     * Reset all statistics (for testing).
     */
    public void reset() {
        activeResources.clear();
        totalAllocated.set(0);
        totalFreed.set(0);
        totalLeaked.set(0);
    }
}