/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.sentinel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;

import com.hellblazer.delaunay.Tetrahedralization;

/**
 * Kinetic point cloud tracking
 *
 * @author hal.hildebrand
 */
public class Sentinel {
    public static class OutOfBoundsException extends Exception {
        private static final long serialVersionUID = 1L;
        private final Tuple3f     destination;
        private final Tuple3f     extent;

        public OutOfBoundsException(String message, Tuple3f destination, Tuple3f extent) {
            super(message);
            this.destination = new Point3f(destination);
            this.extent = new Point3f(extent);
        }

        public Tuple3f getDestination() {
            return destination;
        }

        public Tuple3f getExtent() {
            return extent;
        }
    }

    private Tetrahedralization grid;
    private final List<Site>   tracking = new ArrayList<>();

    /**
     * Move the site by the delta. If the site exits the tracking volume, the site
     * is no longer tracked by the receiver
     *
     * @param site
     * @param delta
     */
    public void moveBy(Site site, Tuple3f delta) {
        site.moveBy(delta);
    }

    /**
     * Move the site to the new location
     *
     * @param site
     * @param newLocation
     */
    public void moveTo(Site site, Tuple3f newLocation) {
        var index = Collections.binarySearch(tracking, site);
        if (index < 0) {
            throw new IllegalArgumentException("Site located at: %s is not being tracked by the receiver".formatted(site));
        }
        site.set(newLocation);
    }

    public void retriangulate() {
        grid = new Tetrahedralization();
        for (var site : tracking) {
            grid.insert(site);
        }
    }

    public Stream<Site> stream() {
        return tracking.stream();
    }

    /**
     * Track as site from the initial location
     *
     * @param initial - the initial location of the site
     * @return the Site tracked starting at the initial location
     */
    public void track(Site s) {
        tracking.add(s);
    }
}
