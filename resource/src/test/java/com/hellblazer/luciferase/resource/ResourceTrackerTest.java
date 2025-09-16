package com.hellblazer.luciferase.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for ResourceTracker
 */
public class ResourceTrackerTest {
    
    private ResourceTracker tracker;
    
    @BeforeEach
    void setUp() {
        tracker = new ResourceTracker();
    }
    
    @Test
    void testTrackerCreation() {
        assertNotNull(tracker);
        assertEquals(0, tracker.getActiveCount());
    }
    
    @Test
    void testGlobalTracker() {
        // getGlobalTracker() auto-creates if null, so test setting a custom one
        ResourceTracker.setGlobalTracker(tracker);
        assertEquals(tracker, ResourceTracker.getGlobalTracker());
        
        // Setting a different tracker
        var newTracker = new ResourceTracker();
        ResourceTracker.setGlobalTracker(newTracker);
        assertEquals(newTracker, ResourceTracker.getGlobalTracker());
        assertNotEquals(tracker, ResourceTracker.getGlobalTracker());
    }
    
    @Test
    void testResourceTracking() {
        var handle = new TestHandle(42L);
        tracker.track(handle);
        
        assertEquals(1, tracker.getActiveCount());
        assertTrue(tracker.getActiveResourceIds().contains(handle.getId()));
        
        tracker.untrack(handle);
        assertEquals(0, tracker.getActiveCount());
        assertFalse(tracker.getActiveResourceIds().contains(handle.getId()));
    }
    
    @Test
    void testMultipleResources() {
        var handle1 = new TestHandle(1L);
        var handle2 = new TestHandle(2L);
        var handle3 = new TestHandle(3L);
        
        tracker.track(handle1);
        tracker.track(handle2);
        tracker.track(handle3);
        
        assertEquals(3, tracker.getActiveCount());
        
        tracker.untrack(handle2);
        assertEquals(2, tracker.getActiveCount());
        assertTrue(tracker.getActiveResourceIds().contains(handle1.getId()));
        assertFalse(tracker.getActiveResourceIds().contains(handle2.getId()));
        assertTrue(tracker.getActiveResourceIds().contains(handle3.getId()));
    }
    
    @Test
    void testManyResources() {
        // Create many resources
        for (int i = 0; i < 100; i++) {
            tracker.track(new TestHandle((long) i));
        }
        
        assertEquals(100, tracker.getActiveCount());
        assertEquals(100, tracker.getActiveResourceIds().size());
    }
    
    // Simple test handle implementation
    private static class TestHandle extends ResourceHandle<Long> {
        TestHandle(Long resource) {
            super(resource, null);
        }
        
        @Override
        protected void doCleanup(Long resource) {
            // No-op for testing
        }
    }
}