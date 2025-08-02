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

import javafx.animation.*;
import javafx.scene.Node;
import javafx.scene.shape.MeshView;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Transform;
import javafx.scene.transform.Translate;
import javafx.util.Duration;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Animation framework for transform-based primitives.
 * Provides efficient animation of transform-based nodes by animating their transform properties
 * rather than recreating geometry.
 *
 * @author hal.hildebrand
 */
public class TransformAnimator {
    
    private static final Duration DEFAULT_DURATION = Duration.millis(300);
    private static final Interpolator DEFAULT_INTERPOLATOR = Interpolator.EASE_BOTH;
    
    private final ConcurrentHashMap<Node, List<Timeline>> activeAnimations = new ConcurrentHashMap<>();
    private final AtomicInteger activeAnimationCount = new AtomicInteger(0);
    
    /**
     * Animate a node's position using transform-based translation.
     */
    public Timeline animatePosition(Node node, Point3f targetPosition, Duration duration) {
        return animatePosition(node, targetPosition, duration, DEFAULT_INTERPOLATOR, null);
    }
    
    /**
     * Animate a node's position with custom interpolator and completion callback.
     */
    public Timeline animatePosition(Node node, Point3f targetPosition, Duration duration, 
                                   Interpolator interpolator, Runnable onFinished) {
        // Find or create translate transform
        Translate translate = findOrCreateTranslate(node);
        
        // Create timeline
        Timeline timeline = new Timeline();
        
        KeyValue kvX = new KeyValue(translate.xProperty(), targetPosition.x, interpolator);
        KeyValue kvY = new KeyValue(translate.yProperty(), targetPosition.y, interpolator);
        KeyValue kvZ = new KeyValue(translate.zProperty(), targetPosition.z, interpolator);
        
        KeyFrame kf = new KeyFrame(duration, kvX, kvY, kvZ);
        timeline.getKeyFrames().add(kf);
        
        if (onFinished != null) {
            timeline.setOnFinished(e -> onFinished.run());
        }
        
        trackAnimation(node, timeline);
        timeline.play();
        
        return timeline;
    }
    
    /**
     * Animate a node's scale uniformly.
     */
    public Timeline animateScale(Node node, double targetScale, Duration duration) {
        return animateScale(node, targetScale, targetScale, targetScale, duration, DEFAULT_INTERPOLATOR, null);
    }
    
    /**
     * Animate a node's scale with separate X, Y, Z values.
     */
    public Timeline animateScale(Node node, double scaleX, double scaleY, double scaleZ, 
                                Duration duration, Interpolator interpolator, Runnable onFinished) {
        Scale scale = findOrCreateScale(node);
        
        Timeline timeline = new Timeline();
        
        KeyValue kvX = new KeyValue(scale.xProperty(), scaleX, interpolator);
        KeyValue kvY = new KeyValue(scale.yProperty(), scaleY, interpolator);
        KeyValue kvZ = new KeyValue(scale.zProperty(), scaleZ, interpolator);
        
        KeyFrame kf = new KeyFrame(duration, kvX, kvY, kvZ);
        timeline.getKeyFrames().add(kf);
        
        if (onFinished != null) {
            timeline.setOnFinished(e -> onFinished.run());
        }
        
        trackAnimation(node, timeline);
        timeline.play();
        
        return timeline;
    }
    
    /**
     * Animate a node's rotation.
     */
    public Timeline animateRotation(Node node, double angle, Point3f axis, Duration duration) {
        return animateRotation(node, angle, axis, duration, DEFAULT_INTERPOLATOR, null);
    }
    
    /**
     * Animate a node's rotation with custom interpolator.
     */
    public Timeline animateRotation(Node node, double angle, Point3f axis, Duration duration,
                                   Interpolator interpolator, Runnable onFinished) {
        Rotate rotate = findOrCreateRotate(node, axis);
        
        Timeline timeline = new Timeline();
        
        KeyValue kv = new KeyValue(rotate.angleProperty(), angle, interpolator);
        KeyFrame kf = new KeyFrame(duration, kv);
        timeline.getKeyFrames().add(kf);
        
        if (onFinished != null) {
            timeline.setOnFinished(e -> onFinished.run());
        }
        
        trackAnimation(node, timeline);
        timeline.play();
        
        return timeline;
    }
    
    /**
     * Animate a node's opacity.
     */
    public Timeline animateOpacity(Node node, double targetOpacity, Duration duration) {
        return animateOpacity(node, targetOpacity, duration, DEFAULT_INTERPOLATOR, null);
    }
    
    /**
     * Animate a node's opacity with custom interpolator.
     */
    public Timeline animateOpacity(Node node, double targetOpacity, Duration duration,
                                  Interpolator interpolator, Runnable onFinished) {
        Timeline timeline = new Timeline();
        
        KeyValue kv = new KeyValue(node.opacityProperty(), targetOpacity, interpolator);
        KeyFrame kf = new KeyFrame(duration, kv);
        timeline.getKeyFrames().add(kf);
        
        if (onFinished != null) {
            timeline.setOnFinished(e -> onFinished.run());
        }
        
        trackAnimation(node, timeline);
        timeline.play();
        
        return timeline;
    }
    
    /**
     * Create a complex animation sequence.
     */
    public SequentialTransition createSequence(Node node) {
        return new SequentialTransition(node);
    }
    
    /**
     * Create a parallel animation group.
     */
    public ParallelTransition createParallel(Node node) {
        return new ParallelTransition(node);
    }
    
    /**
     * Animate insertion of a node (scale and fade in).
     */
    public ParallelTransition animateInsertion(Node node, Duration duration) {
        node.setOpacity(0.0);
        node.setScaleX(0.0);
        node.setScaleY(0.0);
        node.setScaleZ(0.0);
        
        ParallelTransition parallel = new ParallelTransition();
        
        // Don't play individual animations - they'll be played by the parent
        Timeline fadeIn = new Timeline();
        KeyValue fadeKv = new KeyValue(node.opacityProperty(), 1.0, DEFAULT_INTERPOLATOR);
        KeyFrame fadeKf = new KeyFrame(duration, fadeKv);
        fadeIn.getKeyFrames().add(fadeKf);
        
        Timeline scaleUp = new Timeline();
        KeyValue scaleKvX = new KeyValue(node.scaleXProperty(), 1.0, DEFAULT_INTERPOLATOR);
        KeyValue scaleKvY = new KeyValue(node.scaleYProperty(), 1.0, DEFAULT_INTERPOLATOR);
        KeyValue scaleKvZ = new KeyValue(node.scaleZProperty(), 1.0, DEFAULT_INTERPOLATOR);
        KeyFrame scaleKf = new KeyFrame(duration, scaleKvX, scaleKvY, scaleKvZ);
        scaleUp.getKeyFrames().add(scaleKf);
        
        parallel.getChildren().addAll(fadeIn, scaleUp);
        parallel.play();
        
        return parallel;
    }
    
    /**
     * Animate removal of a node (scale and fade out).
     */
    public ParallelTransition animateRemoval(Node node, Duration duration, Runnable onFinished) {
        ParallelTransition parallel = new ParallelTransition();
        
        // Don't play individual animations - they'll be played by the parent
        Timeline fadeOut = new Timeline();
        KeyValue fadeKv = new KeyValue(node.opacityProperty(), 0.0, DEFAULT_INTERPOLATOR);
        KeyFrame fadeKf = new KeyFrame(duration, fadeKv);
        fadeOut.getKeyFrames().add(fadeKf);
        
        // Find or create scale transform for animation
        Scale scale = findOrCreateScale(node);
        Timeline scaleDown = new Timeline();
        KeyValue scaleKvX = new KeyValue(scale.xProperty(), 0.0, DEFAULT_INTERPOLATOR);
        KeyValue scaleKvY = new KeyValue(scale.yProperty(), 0.0, DEFAULT_INTERPOLATOR);
        KeyValue scaleKvZ = new KeyValue(scale.zProperty(), 0.0, DEFAULT_INTERPOLATOR);
        KeyFrame scaleKf = new KeyFrame(duration, scaleKvX, scaleKvY, scaleKvZ);
        scaleDown.getKeyFrames().add(scaleKf);
        
        parallel.getChildren().addAll(fadeOut, scaleDown);
        
        if (onFinished != null) {
            parallel.setOnFinished(e -> onFinished.run());
        }
        
        parallel.play();
        
        return parallel;
    }
    
    /**
     * Create a continuous rotation animation.
     */
    public Timeline createContinuousRotation(Node node, Point3f axis, Duration period) {
        Rotate rotate = findOrCreateRotate(node, axis);
        
        Timeline timeline = new Timeline();
        timeline.setCycleCount(Timeline.INDEFINITE);
        
        KeyValue kv = new KeyValue(rotate.angleProperty(), 360);
        KeyFrame kf = new KeyFrame(period, kv);
        timeline.getKeyFrames().add(kf);
        
        trackAnimation(node, timeline);
        timeline.play();
        
        return timeline;
    }
    
    /**
     * Stop all animations for a node.
     */
    public void stopAnimations(Node node) {
        List<Timeline> animations = activeAnimations.get(node);
        if (animations != null) {
            animations.forEach(Timeline::stop);
            activeAnimations.remove(node);
            activeAnimationCount.addAndGet(-animations.size());
        }
    }
    
    /**
     * Stop all active animations.
     */
    public void stopAllAnimations() {
        activeAnimations.values().forEach(list -> list.forEach(Timeline::stop));
        int count = activeAnimationCount.getAndSet(0);
        activeAnimations.clear();
    }
    
    /**
     * Get the number of active animations.
     */
    public int getActiveAnimationCount() {
        return activeAnimationCount.get();
    }
    
    /**
     * Create a pulse effect for highlighting.
     */
    public Timeline createPulseEffect(Node node, double minScale, double maxScale, Duration period) {
        Scale scale = findOrCreateScale(node);
        
        Timeline timeline = new Timeline();
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.setAutoReverse(true);
        
        KeyValue kvMinX = new KeyValue(scale.xProperty(), minScale);
        KeyValue kvMinY = new KeyValue(scale.yProperty(), minScale);
        KeyValue kvMinZ = new KeyValue(scale.zProperty(), minScale);
        KeyFrame kfMin = new KeyFrame(Duration.ZERO, kvMinX, kvMinY, kvMinZ);
        
        KeyValue kvMaxX = new KeyValue(scale.xProperty(), maxScale);
        KeyValue kvMaxY = new KeyValue(scale.yProperty(), maxScale);
        KeyValue kvMaxZ = new KeyValue(scale.zProperty(), maxScale);
        KeyFrame kfMax = new KeyFrame(period.divide(2), kvMaxX, kvMaxY, kvMaxZ);
        
        timeline.getKeyFrames().addAll(kfMin, kfMax);
        
        trackAnimation(node, timeline);
        timeline.play();
        
        return timeline;
    }
    
    private Translate findOrCreateTranslate(Node node) {
        for (Transform transform : node.getTransforms()) {
            if (transform instanceof Translate) {
                return (Translate) transform;
            }
        }
        Translate translate = new Translate();
        node.getTransforms().add(0, translate); // Add at beginning
        return translate;
    }
    
    private Scale findOrCreateScale(Node node) {
        for (Transform transform : node.getTransforms()) {
            if (transform instanceof Scale) {
                return (Scale) transform;
            }
        }
        Scale scale = new Scale(1, 1, 1);
        // Add after translate but before rotate
        int index = 0;
        for (int i = 0; i < node.getTransforms().size(); i++) {
            if (node.getTransforms().get(i) instanceof Translate) {
                index = i + 1;
                break;
            }
        }
        node.getTransforms().add(index, scale);
        return scale;
    }
    
    private Rotate findOrCreateRotate(Node node, Point3f axis) {
        for (Transform transform : node.getTransforms()) {
            if (transform instanceof Rotate rotate) {
                // Update axis if different
                rotate.setAxis(new javafx.geometry.Point3D(axis.x, axis.y, axis.z));
                return rotate;
            }
        }
        Rotate rotate = new Rotate(0, new javafx.geometry.Point3D(axis.x, axis.y, axis.z));
        node.getTransforms().add(rotate); // Add at end
        return rotate;
    }
    
    private void trackAnimation(Node node, Timeline timeline) {
        activeAnimations.computeIfAbsent(node, k -> new ArrayList<>()).add(timeline);
        activeAnimationCount.incrementAndGet();
        
        timeline.setOnFinished(e -> {
            List<Timeline> animations = activeAnimations.get(node);
            if (animations != null) {
                animations.remove(timeline);
                if (animations.isEmpty()) {
                    activeAnimations.remove(node);
                }
            }
            activeAnimationCount.decrementAndGet();
        });
    }
}