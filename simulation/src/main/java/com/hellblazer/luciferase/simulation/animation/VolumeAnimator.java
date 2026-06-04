/**
 * Copyright (C) 2008 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Prime Mover Event Driven Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.animation;

import com.hellblazer.luciferase.simulation.animation.*;

import com.hellblazer.luciferase.simulation.spatial.*;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.annotations.NonEvent;
import com.hellblazer.primeMover.api.Kronos;
import com.hellblazer.primeMover.controllers.RealTimeController;
import com.hellblazer.primeMover.runtime.Kairos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.concurrent.TimeUnit;

/**
 * An event controller for a volume of space.
 * <p>
 * Uses Lucien's Tetree (tetrahedral) spatial index for entity tracking instead of Sentry's
 * MutableGrid (Delaunay tetrahedralization). This provides O(log n) position
 * updates instead of O(n log n) per-frame rebuilds.
 * <p>
 * <b>Lifecycle:</b> VolumeAnimator owns a {@link RealTimeController} and must be closed
 * when no longer needed to stop the controller's scheduling thread. Use try-with-resources
 * or call {@link #close()} explicitly.
 * <p>
 * <b>Kairos thread-local scope:</b> {@code Kairos.setController} is thread-local (backed by
 * {@link ThreadLocal}). The constructor binds the controller to the constructing thread.
 * {@link #start()} runs on the same thread as the constructor. {@link #close()} <em>must be
 * called on the same thread that called {@link #start()}</em>; only that thread holds the
 * active Kairos binding, and only that thread can clear it via
 * {@code Kairos.setController(null)}. Calling {@code close()} from a different thread stops
 * the RealTimeController but cannot clear the Kairos binding on the original thread — the
 * binding will leak until that thread terminates. A WARN log is emitted in this case.
 * A single thread should own at most one active VolumeAnimator at a time;
 * creating a second one on the same thread silently rebinds the thread-local to the new
 * controller.
 *
 * @author hal.hildebrand
 */
public class VolumeAnimator implements AutoCloseable {
    private static final Logger log         = LoggerFactory.getLogger(VolumeAnimator.class);
    private static final byte   LEVEL       = 12; // Spatial resolution level
    private static final float  WORLD_SCALE = 32200f; // Scale for normalizing world coords to [0,1]

    private volatile Clock   clock         = Clock.system();
    /** Thread that called start() and owns the Kairos ThreadLocal binding. */
    private volatile Thread  bindingThread = null;

    private final Tetree<LongEntityID, Void> index;
    private final RealTimeController         controller;
    private final AnimationFrame             frame = new AnimationFrame(100);

    /**
     * Set the clock source for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Create VolumeAnimator.
     *
     * @param name Controller name
     */
    public VolumeAnimator(String name) {
        this.controller = new RealTimeController(name);
        this.index = new Tetree<>(new SequentialLongIDGenerator(), 16, (byte) 21);
        Kairos.setController(controller);
    }

    /**
     * @deprecated Use {@link #VolumeAnimator(String)} instead.
     *             The Tet cell and Random parameters are no longer needed.
     */
    @Deprecated(forRemoval = true)
    public VolumeAnimator(String name, Object cell, Object entropy) {
        this(name);
    }

    @NonEvent
    public AnimationFrame getFrame() {
        return frame;
    }

    public void start() {
        bindingThread = Thread.currentThread();
        frame.track();
        controller.start();
    }

    /**
     * Stop the RealTimeController scheduling thread and clear the thread-local
     * Kairos controller binding for the calling thread.
     * <p>
     * <b>Precondition:</b> must be called on the same thread that called {@link #start()}.
     * The Kairos controller binding is thread-local; only the thread that set it can clear it.
     * If called from a different thread, the controller is still stopped, but the Kairos
     * binding on the original thread cannot be cleared and will leak until that thread
     * terminates. A WARN log is emitted in this case.
     * <p>
     * Safe to call multiple times; {@code controller.stop()} is idempotent.
     * Does not affect other threads' Kairos bindings.
     */
    @Override
    @NonEvent
    public void close() {
        controller.stop();
        var bt = bindingThread;
        if (bt != null && bt != Thread.currentThread()) {
            log.warn("VolumeAnimator.close() called from thread '{}' but Kairos was bound on thread '{}'; "
                     + "the Kairos binding on the original thread cannot be cleared from here.",
                     Thread.currentThread().getName(), bt.getName());
        } else {
            Kairos.setController(null);
        }
    }

    /**
     * Track a point in the spatial index.
     *
     * @param p the position to track
     * @return a Cursor for the tracked entity, or null if tracking failed
     */
    public Cursor track(Point3f p) {
        // Normalize position to [0,1] range for tetree
        var normalized = normalizePosition(p);
        if (!isValidPosition(normalized)) {
            return null;
        }

        var entityId = index.insert(normalized, LEVEL, null);

        // Pass WORLD_SCALE so cursor can properly normalize deltas in moveBy/moveTo
        return new SpatialCursor<TetreeKey<?>, LongEntityID, Void>(index, entityId, LEVEL, 10, Float.MAX_VALUE, WORLD_SCALE);
    }

    /**
     * Normalize position to tetree coordinate space [0,1].
     */
    private Point3f normalizePosition(Point3f p) {
        return new Point3f(p.x / WORLD_SCALE, p.y / WORLD_SCALE, p.z / WORLD_SCALE);
    }

    private boolean isValidPosition(Point3f p) {
        return p.x >= 0 && p.x <= 1 && p.y >= 0 && p.y <= 1 && p.z >= 0 && p.z <= 1;
    }

    /**
     * Compute the per-frame sleep duration, clamped to be non-negative
     * (Luciferase-0frcy.74). When the frame's work plus event overhead exceeds
     * the frame budget, {@code frameRateNs - duration - eventOverhead} is
     * negative; passing a negative duration to {@code Kronos.sleep} has
     * unspecified behavior (potential stall). Mirrors the
     * {@code if (sleepNs > 0)} guard in RealTimeController.tickLoop().
     * Visible-for-testing (public because the @Entity PrimeMover transformation
     * prevents package-private access from cross-package tests).
     */
    public static long frameSleepNs(long frameRateNs, long duration, long eventOverhead) {
        return Math.max(0, frameRateNs - duration - eventOverhead);
    }

    @Entity
    public class AnimationFrame {
        private final long frameRateNs;
        private       long frameCount          = 0;
        private       long cumulativeDurations = 0;
        private       long cumulativeDelay     = 0;
        private       long lastActive          = clock.nanoTime();
        private       long eventOverhead       = 0;

        public AnimationFrame(int frameRate) {
            this.frameRateNs = (TimeUnit.NANOSECONDS.convert(1, TimeUnit.SECONDS) / frameRate);
        }

        @NonEvent
        public long getCumulativeDelay() {
            return cumulativeDelay;
        }

        @NonEvent
        public long getCumulativeDurations() {
            return cumulativeDurations;
        }

        @NonEvent
        public long getFrameCount() {
            return frameCount;
        }

        public void track() {
            frameCount++;
            long start = clock.nanoTime();
            cumulativeDelay += start - lastActive;
            // No rebuild needed! SpatialIndex updates are incremental.
            // The old MutableGrid.rebuild() call is eliminated.
            var now = clock.nanoTime();
            var duration = now - start;
            cumulativeDurations += duration;
            Kronos.sleep(VolumeAnimator.frameSleepNs(frameRateNs, duration, eventOverhead));
            this.track();
            lastActive = clock.nanoTime();
            eventOverhead = (lastActive - now) / 2;
        }
    }
}
