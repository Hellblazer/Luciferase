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

import com.hellblazer.luciferase.lucien.Tet;
import com.hellblazer.primeMover.Kronos;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.annotations.NonEvent;
import com.hellblazer.primeMover.controllers.RealTimeController;
import com.hellblazer.primeMover.runtime.Kairos;
import com.hellblazer.sentry.MutableGrid;
import com.hellblazer.sentry.Vertex;

import javax.vecmath.Point3f;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * An event controller for a volume of space
 *
 * @author hal.hildebrand
 */
public class VolumeAnimator {
    private static final Logger log = Logger.getLogger(VolumeAnimator.class.getCanonicalName());

    private final Tet                cell;
    private final MutableGrid        grid;
    private final RealTimeController controller;
    private final AnimationFrame     frame = new AnimationFrame(100);
    private final Random             entropy;

    public VolumeAnimator(String name, Tet cell, Random entropy) {
        this.controller = new RealTimeController(name);
        this.cell = cell;
        this.grid = new MutableGrid(Vertex.vertices(cell.vertices()));
        this.entropy = entropy;
        Kairos.setController(controller);
    }

    @NonEvent
    public AnimationFrame getFrame() {
        return frame;
    }

    public void start() {
        frame.track();
        controller.start();
    }

    public Vertex track(Point3f p) {
        return grid.track(p, entropy);
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
            grid.rebuild(entropy);
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
