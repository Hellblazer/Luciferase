/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FrameManager
 *
 * @author hal.hildebrand
 */
public class FrameManagerTest {

    @Test
    public void testBasicOperations() {
        var frameManager = new FrameManager();
        
        // Test initial state
        assertEquals(0L, frameManager.getCurrentFrame());
        
        // Test increment
        assertEquals(1L, frameManager.incrementFrame());
        assertEquals(1L, frameManager.getCurrentFrame());
        
        // Test getAndIncrement
        assertEquals(1L, frameManager.getAndIncrementFrame());
        assertEquals(2L, frameManager.getCurrentFrame());
        
        // Test reset
        frameManager.reset();
        assertEquals(0L, frameManager.getCurrentFrame());
        
        // Test setFrame
        frameManager.setFrame(100L);
        assertEquals(100L, frameManager.getCurrentFrame());
    }

    @Test
    public void testConstructorWithInitialFrame() {
        var frameManager = new FrameManager(50L);
        assertEquals(50L, frameManager.getCurrentFrame());
    }

    @Test
    public void testTimeCalculations() throws InterruptedException {
        var frameManager = new FrameManager();
        
        // Initial time calculations
        assertTrue(frameManager.getElapsedTimeSeconds() >= 0.0);
        assertEquals(0.0, frameManager.getFrameTimeSeconds());
        assertEquals(0.0, frameManager.getAverageFPS());
        
        // Simulate some frames with a small delay
        for (int i = 0; i < 10; i++) {
            frameManager.incrementFrame();
            Thread.sleep(10); // 10ms delay
        }
        
        // After processing frames
        assertEquals(10L, frameManager.getCurrentFrame());
        assertTrue(frameManager.getElapsedTimeSeconds() > 0.0);
        assertTrue(frameManager.getFrameTimeSeconds() > 0.0);
        assertTrue(frameManager.getAverageFPS() > 0.0);
        
        // Frame time should be approximately elapsed time / frame count
        double frameTime = frameManager.getFrameTimeSeconds();
        double expectedFrameTime = frameManager.getElapsedTimeSeconds() / frameManager.getCurrentFrame();
        assertEquals(expectedFrameTime, frameTime, 0.001);
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        var frameManager = new FrameManager();
        int numThreads = 10;
        int incrementsPerThread = 1000;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        frameManager.incrementFrame();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Verify all increments were counted
        assertEquals(numThreads * incrementsPerThread, frameManager.getCurrentFrame());
    }

    @Test
    public void testToString() {
        var frameManager = new FrameManager(42L);
        String str = frameManager.toString();
        
        assertTrue(str.contains("FrameManager"));
        assertTrue(str.contains("frame=42"));
        assertTrue(str.contains("elapsed="));
        assertTrue(str.contains("avgFPS="));
    }
}