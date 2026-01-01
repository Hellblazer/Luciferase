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

package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.annotations.NonEvent;
import com.hellblazer.primeMover.api.Kronos;
import com.hellblazer.primeMover.controllers.RealTimeController;
import com.hellblazer.primeMover.runtime.Kairos;
import com.hellblazer.sentry.Cursor;

import javax.vecmath.Point3f;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * An event controller for a volume of space.
 * <p>
 * Uses Lucien's Tetree (tetrahedral) spatial index for entity tracking instead of Sentry's
 * MutableGrid (Delaunay tetrahedralization). This provides O(log n) position
 * updates instead of O(n log n) per-frame rebuilds.
 *
 * @author hal.hildebrand
 */
public class VolumeAnimator {
    private static final Logger log         = Logger.getLogger(VolumeAnimator.class.getCanonicalName());
    private static final byte   LEVEL       = 12; // Spatial resolution level
    private static final float  WORLD_SCALE = 32200f; // Scale for normalizing world coords to [0,1]

    private final Tetree<LongEntityID, Void> index;
    private final RealTimeController         controller;
    private final AnimationFrame             frame = new AnimationFrame(100);

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
        frame.track();
        controller.start();
    }

    /**
     * Track a point in the spatial index.
     *
     * @param p the position to track
     * @return a Cursor for the tracked entity, or null if tracking failed
     */
    public Cursor track(Point3f p) {
        // Normalize position to [0,1] range for octree
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

    @Entity
    public class AnimationFrame {
        private final long frameRateNs;
        private       long frameCount          = 0;
        private       long cumulativeDurations = 0;
        private       long cumulativeDelay     = 0;
        private       long lastActive          = System.nanoTime();
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
            long start = System.nanoTime();
            cumulativeDelay += start - lastActive;
            // No rebuild needed! SpatialIndex updates are incremental.
            // The old MutableGrid.rebuild() call is eliminated.
            var now = System.nanoTime();
            var duration = now - start;
            cumulativeDurations += duration;
            Kronos.sleep(frameRateNs - duration - eventOverhead);
            this.track();
            lastActive = System.nanoTime();
            eventOverhead = (lastActive - now) / 2;
        }
    }
}
