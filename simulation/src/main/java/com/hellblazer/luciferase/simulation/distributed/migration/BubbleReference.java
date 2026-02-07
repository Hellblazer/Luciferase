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

package com.hellblazer.luciferase.simulation.distributed.migration;

import javafx.geometry.Point3D;

import java.util.Set;
import java.util.UUID;

/**
 * Abstract reference to a bubble (local or remote).
 * <p>
 * Allows code to treat local and remote bubbles uniformly.
 * Implementations provide type-specific access via asLocal() and asRemote().
 * <p>
 * Architecture Decision D6B.4: Remote Bubble Proxies
 *
 * @author hal.hildebrand
 */
public interface BubbleReference {

    /**
     * Check if this is a local bubble reference.
     *
     * @return true if this is a local bubble, false if remote
     */
    boolean isLocal();

    /**
     * Get as local bubble reference.
     *
     * @return LocalBubbleReference
     * @throws IllegalStateException if this is a remote reference
     */
    LocalBubbleReference asLocal();

    /**
     * Get as remote bubble proxy.
     *
     * @return RemoteBubbleProxy
     * @throws IllegalStateException if this is a local reference
     */
    RemoteBubbleProxy asRemote();

    /**
     * Get the bubble's unique identifier.
     *
     * @return UUID of the bubble
     */
    UUID getBubbleId();

    /**
     * Get the bubble's current position.
     *
     * @return Point3D position
     */
    Point3D getPosition();

    /**
     * Get the bubble's current neighbors.
     *
     * @return Set of neighbor UUIDs
     */
    Set<UUID> getNeighbors();
}
