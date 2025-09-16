package com.hellblazer.luciferase.resource;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResourceConfiguration
 */
public class ResourceConfigurationTest {
    
    @Test
    void testDefaultConfiguration() {
        var config = ResourceConfiguration.defaultConfig();
        
        assertNotNull(config);
        assertEquals(512L * 1024 * 1024, config.getMaxPoolSizeBytes());
        assertEquals(0.9, config.getHighWaterMark());
        assertEquals(0.7, config.getLowWaterMark());
        assertEquals(ResourceConfiguration.EvictionPolicy.LRU, config.getEvictionPolicy());
        assertEquals(Duration.ofMinutes(5), config.getMaxIdleTime());
        assertEquals(10000, config.getMaxResourceCount());
    }
    
    @Test
    void testMinimalConfiguration() {
        var config = ResourceConfiguration.minimalConfig();
        
        assertNotNull(config);
        assertEquals(64L * 1024 * 1024, config.getMaxPoolSizeBytes());
        assertEquals(0.95, config.getHighWaterMark());
        assertEquals(0.8, config.getLowWaterMark());
        assertEquals(ResourceConfiguration.EvictionPolicy.LRU, config.getEvictionPolicy());
        assertEquals(Duration.ofMinutes(1), config.getMaxIdleTime());
        assertEquals(1000, config.getMaxResourceCount());
    }
    
    @Test
    void testProductionConfiguration() {
        var config = ResourceConfiguration.productionConfig();
        
        assertNotNull(config);
        assertEquals(2L * 1024 * 1024 * 1024, config.getMaxPoolSizeBytes());
        assertEquals(0.85, config.getHighWaterMark());
        assertEquals(0.6, config.getLowWaterMark());
        assertEquals(ResourceConfiguration.EvictionPolicy.HYBRID, config.getEvictionPolicy());
        assertEquals(Duration.ofMinutes(15), config.getMaxIdleTime());
        assertEquals(50000, config.getMaxResourceCount());
    }
    
    @Test
    void testBuilderConfiguration() {
        var config = new ResourceConfiguration.Builder()
            .withMaxPoolSize(1024 * 1024)
            .withHighWaterMark(0.95)
            .withLowWaterMark(0.5)
            .withEvictionPolicy(ResourceConfiguration.EvictionPolicy.LFU)
            .withMaxIdleTime(Duration.ofSeconds(30))
            .withMaxResourceCount(500)
            .build();
        
        assertNotNull(config);
        assertEquals(1024 * 1024, config.getMaxPoolSizeBytes());
        assertEquals(0.95, config.getHighWaterMark());
        assertEquals(0.5, config.getLowWaterMark());
        assertEquals(ResourceConfiguration.EvictionPolicy.LFU, config.getEvictionPolicy());
        assertEquals(Duration.ofSeconds(30), config.getMaxIdleTime());
        assertEquals(500, config.getMaxResourceCount());
    }
    
    @Test
    void testInvalidWatermarks() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ResourceConfiguration.Builder()
                .withHighWaterMark(0.5)
                .withLowWaterMark(0.8) // Low > High, should fail
                .build();
        });
    }
    
    @Test
    void testNegativePoolSize() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ResourceConfiguration.Builder()
                .withMaxPoolSize(-1)
                .build();
        });
    }
    
    @Test
    void testWatermarkBounds() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ResourceConfiguration.Builder()
                .withHighWaterMark(1.5) // > 1.0, should fail
                .build();
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            new ResourceConfiguration.Builder()
                .withLowWaterMark(-0.1) // < 0.0, should fail
                .build();
        });
    }
}