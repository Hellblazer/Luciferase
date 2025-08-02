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

import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Transform;
import javafx.scene.transform.Translate;
import javafx.util.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TransformAnimator.
 *
 * @author hal.hildebrand
 */
@RequiresJavaFX
public class TransformAnimatorTest {
    
    @BeforeAll
    public static void setupJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Already initialized
        }
    }
    
    @Test
    public void testPositionAnimation() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            Sphere sphere = new Sphere(10);
            
            // Animate to new position
            Point3f target = new Point3f(100, 200, 300);
            Timeline timeline = animator.animatePosition(sphere, target, Duration.millis(100));
            
            // Verify translate transform was created
            boolean hasTranslate = sphere.getTransforms().stream()
                .anyMatch(t -> t instanceof Translate);
            assertTrue(hasTranslate, "Should have created Translate transform");
            
            // Verify animation is tracked
            assertEquals(1, animator.getActiveAnimationCount());
            
            timeline.setOnFinished(e -> {
                // Check final position
                Translate translate = (Translate) sphere.getTransforms().stream()
                    .filter(t -> t instanceof Translate)
                    .findFirst().orElse(null);
                assertNotNull(translate);
                assertEquals(100, translate.getX(), 0.1);
                assertEquals(200, translate.getY(), 0.1);
                assertEquals(300, translate.getZ(), 0.1);
                
                latch.countDown();
            });
        });
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Animation should complete");
    }
    
    @Test
    public void testScaleAnimation() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            Sphere sphere = new Sphere(10);
            
            // Animate scale
            Timeline timeline = animator.animateScale(sphere, 2.0, Duration.millis(100));
            
            // Verify scale transform was created
            boolean hasScale = sphere.getTransforms().stream()
                .anyMatch(t -> t instanceof Scale);
            assertTrue(hasScale, "Should have created Scale transform");
            
            timeline.setOnFinished(e -> {
                // Check final scale
                Scale scale = (Scale) sphere.getTransforms().stream()
                    .filter(t -> t instanceof Scale)
                    .findFirst().orElse(null);
                assertNotNull(scale);
                assertEquals(2.0, scale.getX(), 0.1);
                assertEquals(2.0, scale.getY(), 0.1);
                assertEquals(2.0, scale.getZ(), 0.1);
                
                latch.countDown();
            });
        });
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Animation should complete");
    }
    
    @Test
    public void testRotationAnimation() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            Sphere sphere = new Sphere(10);
            
            // Animate rotation
            Point3f axis = new Point3f(0, 1, 0); // Y-axis
            Timeline timeline = animator.animateRotation(sphere, 180, axis, Duration.millis(100));
            
            // Verify rotate transform was created
            boolean hasRotate = sphere.getTransforms().stream()
                .anyMatch(t -> t instanceof Rotate);
            assertTrue(hasRotate, "Should have created Rotate transform");
            
            timeline.setOnFinished(e -> {
                // Check final rotation
                Rotate rotate = (Rotate) sphere.getTransforms().stream()
                    .filter(t -> t instanceof Rotate)
                    .findFirst().orElse(null);
                assertNotNull(rotate);
                assertEquals(180, rotate.getAngle(), 0.1);
                
                latch.countDown();
            });
        });
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Animation should complete");
    }
    
    @Test
    public void testOpacityAnimation() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            Sphere sphere = new Sphere(10);
            sphere.setOpacity(1.0);
            
            // Animate opacity
            Timeline timeline = animator.animateOpacity(sphere, 0.5, Duration.millis(100));
            
            timeline.setOnFinished(e -> {
                assertEquals(0.5, sphere.getOpacity(), 0.1);
                latch.countDown();
            });
        });
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Animation should complete");
    }
    
    @Test
    public void testInsertionAnimation() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            Sphere sphere = new Sphere(10);
            
            // Test insertion animation
            ParallelTransition animation = animator.animateInsertion(sphere, Duration.millis(100));
            
            // Initially should be invisible and scaled to 0
            assertEquals(0.0, sphere.getOpacity(), 0.01);
            assertEquals(0.0, sphere.getScaleX(), 0.01);
            
            // Set up callback for when animation completes
            animation.setOnFinished(e -> {
                // Should be fully visible and scaled
                assertEquals(1.0, sphere.getOpacity(), 0.1);
                assertEquals(1.0, sphere.getScaleX(), 0.1);
                
                latch.countDown();
            });
        });
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Animation should complete");
    }
    
    @Test
    public void testRemovalAnimation() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            Sphere sphere = new Sphere(10);
            sphere.setOpacity(1.0);
            
            // Test removal animation - the callback parameter will be used by animateRemoval
            ParallelTransition animation = animator.animateRemoval(sphere, Duration.millis(100), () -> {
                // Should be invisible and scaled to 0
                assertEquals(0.0, sphere.getOpacity(), 0.1);
                
                Scale scale = (Scale) sphere.getTransforms().stream()
                    .filter(t -> t instanceof Scale)
                    .findFirst().orElse(null);
                assertNotNull(scale);
                assertEquals(0.0, scale.getX(), 0.1);
                
                latch.countDown();
            });
        });
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Animation should complete");
    }
    
    @Test
    public void testStopAnimations() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            Sphere sphere = new Sphere(10);
            
            // Start multiple animations
            animator.animatePosition(sphere, new Point3f(100, 100, 100), Duration.seconds(1));
            animator.animateScale(sphere, 2.0, Duration.seconds(1));
            animator.animateRotation(sphere, 360, new Point3f(0, 1, 0), Duration.seconds(1));
            
            assertEquals(3, animator.getActiveAnimationCount());
            
            // Stop all animations for the node
            animator.stopAnimations(sphere);
            
            assertEquals(0, animator.getActiveAnimationCount());
            
            latch.countDown();
        });
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Test should complete");
    }
    
    @Test
    public void testPulseEffect() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            Sphere sphere = new Sphere(10);
            
            // Create pulse effect
            Timeline pulse = animator.createPulseEffect(sphere, 0.8, 1.2, Duration.millis(200));
            
            // Should be cycling
            assertEquals(Timeline.INDEFINITE, pulse.getCycleCount());
            assertTrue(pulse.isAutoReverse());
            
            // Stop it after a moment
            Platform.runLater(() -> {
                pulse.stop();
                latch.countDown();
            });
        });
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Test should complete");
    }
    
    @Test
    public void testTransformOrder() {
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            Sphere sphere = new Sphere(10);
            
            // Add transforms in specific order
            animator.animatePosition(sphere, new Point3f(10, 20, 30), Duration.millis(1));
            animator.animateScale(sphere, 2.0, Duration.millis(1));
            animator.animateRotation(sphere, 45, new Point3f(0, 1, 0), Duration.millis(1));
            
            // Verify transform order: Translate, Scale, Rotate
            assertEquals(3, sphere.getTransforms().size());
            assertTrue(sphere.getTransforms().get(0) instanceof Translate);
            assertTrue(sphere.getTransforms().get(1) instanceof Scale);
            assertTrue(sphere.getTransforms().get(2) instanceof Rotate);
        });
    }
    
    @Test
    public void testActiveAnimationTracking() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        
        Platform.runLater(() -> {
            TransformAnimator animator = new TransformAnimator();
            Sphere sphere1 = new Sphere(10);
            Sphere sphere2 = new Sphere(10);
            
            // Start animations on different nodes
            animator.animatePosition(sphere1, new Point3f(100, 0, 0), Duration.millis(100));
            animator.animateScale(sphere2, 2.0, Duration.millis(100));
            
            assertEquals(2, animator.getActiveAnimationCount());
            
            // Stop all animations
            animator.stopAllAnimations();
            
            assertEquals(0, animator.getActiveAnimationCount());
            
            latch.countDown();
        });
        
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Test should complete");
    }
}
