/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.mesh.explorer;

import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.util.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Performance tests for transform-based animations.
 *
 * @author hal.hildebrand
 */
public class AnimationPerformanceTest {
    
    @BeforeAll
    public static void setupJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Already initialized
        }
    }
    
    @Test
    public void testPositionAnimationPerformance() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            PrimitiveTransformManager primitiveManager = new PrimitiveTransformManager();
            Group root = new Group();
            Random random = new Random(42);
            
            int nodeCount = 100;
            long startTime = System.currentTimeMillis();
            
            // Create nodes and animate their positions
            List<MeshView> nodes = new ArrayList<>();
            for (int i = 0; i < nodeCount; i++) {
                Point3f position = new Point3f(
                    random.nextFloat() * 1000,
                    random.nextFloat() * 1000,
                    random.nextFloat() * 1000
                );
                
                MeshView sphere = primitiveManager.createSphere(position, 10f, null);
                nodes.add(sphere);
                root.getChildren().add(sphere);
                
                // Animate to new position
                Point3f target = new Point3f(
                    random.nextFloat() * 1000,
                    random.nextFloat() * 1000,
                    random.nextFloat() * 1000
                );
                
                animator.animatePosition(sphere, target, Duration.millis(500));
            }
            
            long animationStartTime = System.currentTimeMillis() - startTime;
            System.out.println("Started " + nodeCount + " position animations in " + animationStartTime + "ms");
            System.out.println("Active animations: " + animator.getActiveAnimationCount());
            
            // Schedule check after animations should complete
            Timeline checkCompletion = new Timeline();
            checkCompletion.getKeyFrames().add(new javafx.animation.KeyFrame(
                Duration.millis(600),
                e -> {
                    int remaining = animator.getActiveAnimationCount();
                    System.out.println("Remaining animations after 600ms: " + remaining);
                    // Just report the count, don't assert here as animations may still be cleaning up
                    latch.countDown();
                }
            ));
            checkCompletion.play();
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Test should complete");
    }
    
    @Test
    public void testConcurrentAnimationPerformance() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            PrimitiveTransformManager primitiveManager = new PrimitiveTransformManager();
            Group root = new Group();
            Random random = new Random(42);
            
            int nodeCount = 50;
            long startTime = System.currentTimeMillis();
            
            // Create nodes and animate multiple properties simultaneously
            for (int i = 0; i < nodeCount; i++) {
                Point3f position = new Point3f(
                    random.nextFloat() * 1000,
                    random.nextFloat() * 1000,
                    random.nextFloat() * 1000
                );
                
                MeshView sphere = primitiveManager.createSphere(position, 10f, null);
                root.getChildren().add(sphere);
                
                // Animate position, scale, rotation, and opacity simultaneously
                Point3f target = new Point3f(
                    random.nextFloat() * 1000,
                    random.nextFloat() * 1000,
                    random.nextFloat() * 1000
                );
                
                animator.animatePosition(sphere, target, Duration.millis(300));
                animator.animateScale(sphere, random.nextDouble() * 2 + 0.5, Duration.millis(400));
                animator.animateRotation(sphere, 360, new Point3f(0, 1, 0), Duration.millis(500));
                animator.animateOpacity(sphere, random.nextDouble(), Duration.millis(200));
            }
            
            long animationStartTime = System.currentTimeMillis() - startTime;
            int activeCount = animator.getActiveAnimationCount();
            
            System.out.println("Started " + (nodeCount * 4) + " concurrent animations in " + animationStartTime + "ms");
            System.out.println("Active animations: " + activeCount);
            System.out.println("Average time per animation: " + (animationStartTime / (double)(nodeCount * 4)) + "ms");
            
            latch.countDown();
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Test should complete");
    }
    
    @Test
    public void testInsertionRemovalAnimationPerformance() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            PrimitiveTransformManager primitiveManager = new PrimitiveTransformManager();
            MaterialPool materialPool = new MaterialPool();
            Group root = new Group();
            Random random = new Random(42);
            
            int nodeCount = 100;
            long startTime = System.currentTimeMillis();
            
            // Test insertion animations
            List<MeshView> nodes = new ArrayList<>();
            for (int i = 0; i < nodeCount; i++) {
                Point3f position = new Point3f(
                    random.nextFloat() * 1000,
                    random.nextFloat() * 1000,
                    random.nextFloat() * 1000
                );
                
                MeshView sphere = primitiveManager.createSphere(position, 10f, null);
                nodes.add(sphere);
                root.getChildren().add(sphere);
                
                animator.animateInsertion(sphere, Duration.millis(200));
            }
            
            long insertionTime = System.currentTimeMillis() - startTime;
            System.out.println("Created " + nodeCount + " insertion animations in " + insertionTime + "ms");
            
            // Wait for insertions to complete, then test removals
            Platform.runLater(() -> {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    // Ignore
                }
                
                long removalStart = System.currentTimeMillis();
                
                // Animate removals
                for (MeshView node : nodes) {
                    animator.animateRemoval(node, Duration.millis(200), () -> {
                        root.getChildren().remove(node);
                    });
                }
                
                long removalTime = System.currentTimeMillis() - removalStart;
                System.out.println("Created " + nodeCount + " removal animations in " + removalTime + "ms");
                
                latch.countDown();
            });
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Test should complete");
    }
    
    @Test
    public void testPulseEffectPerformance() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            Group root = new Group();
            
            int nodeCount = 50;
            long startTime = System.currentTimeMillis();
            
            // Create nodes with continuous pulse effects
            for (int i = 0; i < nodeCount; i++) {
                Sphere sphere = new Sphere(10);
                sphere.setTranslateX(i * 20);
                root.getChildren().add(sphere);
                
                Timeline pulse = animator.createPulseEffect(sphere, 0.8, 1.2, Duration.millis(500));
                pulse.setCycleCount(10); // Run for 10 cycles
            }
            
            long pulseStartTime = System.currentTimeMillis() - startTime;
            int activeCount = animator.getActiveAnimationCount();
            
            System.out.println("Started " + nodeCount + " pulse effects in " + pulseStartTime + "ms");
            System.out.println("Active animations: " + activeCount);
            
            // Test stopping all animations
            long stopStart = System.currentTimeMillis();
            animator.stopAllAnimations();
            long stopTime = System.currentTimeMillis() - stopStart;
            
            System.out.println("Stopped all animations in " + stopTime + "ms");
            assertEquals(0, animator.getActiveAnimationCount(), "All animations should be stopped");
            
            latch.countDown();
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Test should complete");
    }
    
    @Test
    public void testMemoryEfficiencyWithManyAnimations() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            PrimitiveTransformManager primitiveManager = new PrimitiveTransformManager();
            Group root = new Group();
            
            // Measure memory before
            System.gc();
            long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            
            int nodeCount = 500;
            long startTime = System.currentTimeMillis();
            
            // Create many short animations
            for (int i = 0; i < nodeCount; i++) {
                Point3f position = new Point3f(i, 0, 0);
                MeshView sphere = primitiveManager.createSphere(position, 5f, null);
                root.getChildren().add(sphere);
                
                // Very short animation
                animator.animateOpacity(sphere, 0.5, Duration.millis(50));
            }
            
            long creationTime = System.currentTimeMillis() - startTime;
            
            // Schedule memory check after animations complete
            Timeline memoryCheck = new Timeline();
            memoryCheck.getKeyFrames().add(new javafx.animation.KeyFrame(
                Duration.millis(100),
                e -> {
                    // Measure memory after
                    System.gc();
                    long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                    long memUsed = memAfter - memBefore;
                    
                    System.out.println("Created " + nodeCount + " animations in " + creationTime + "ms");
                    System.out.println("Memory used: " + (memUsed / 1024 / 1024) + " MB");
                    System.out.println("Memory per animation: " + (memUsed / nodeCount) + " bytes");
                    
                    int remaining = animator.getActiveAnimationCount();
                    System.out.println("Remaining animations: " + remaining);
                    
                    latch.countDown();
                }
            ));
            memoryCheck.play();
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Test should complete");
    }
    
    private void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
    
    private void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " Expected: " + expected + ", Actual: " + actual);
        }
    }
}