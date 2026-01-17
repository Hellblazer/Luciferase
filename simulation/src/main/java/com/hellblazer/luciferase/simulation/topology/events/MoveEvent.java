/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.simulation.topology.events;

import javax.vecmath.Point3f;
import java.util.UUID;

/**
 * Event fired when a bubble boundary moves.
 *
 * @param eventId        unique event identifier
 * @param timestamp      event timestamp (ms since epoch)
 * @param bubbleId       bubble that moved
 * @param oldCentroidX   old centroid X coordinate
 * @param oldCentroidY   old centroid Y coordinate
 * @param oldCentroidZ   old centroid Z coordinate
 * @param newCentroidX   new centroid X coordinate
 * @param newCentroidY   new centroid Y coordinate
 * @param newCentroidZ   new centroid Z coordinate
 * @param success        whether move succeeded
 *
 * @author hal.hildebrand
 */
public record MoveEvent(
    UUID eventId,
    long timestamp,
    UUID bubbleId,
    float oldCentroidX,
    float oldCentroidY,
    float oldCentroidZ,
    float newCentroidX,
    float newCentroidY,
    float newCentroidZ,
    boolean success
) implements TopologyEvent {
    @Override
    public String eventType() {
        return "move";
    }

    /**
     * Convenience constructor with Point3f.
     */
    public MoveEvent(UUID eventId, long timestamp, UUID bubbleId, Point3f oldCentroid, Point3f newCentroid, boolean success) {
        this(eventId, timestamp, bubbleId,
             oldCentroid.x, oldCentroid.y, oldCentroid.z,
             newCentroid.x, newCentroid.y, newCentroid.z,
             success);
    }
}
