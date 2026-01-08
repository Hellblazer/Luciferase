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

package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.von.VonBubble;
import javafx.geometry.Point3D;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reference to a bubble in the local process.
 * <p>
 * Wraps a VonBubble instance and delegates method calls directly.
 * No network communication required.
 * <p>
 * Thread-safe: delegates to thread-safe VonBubble.
 *
 * @author hal.hildebrand
 */
public class LocalBubbleReference implements BubbleReference {

    private final VonBubble bubble;

    /**
     * Create a reference to a local bubble.
     *
     * @param bubble VonBubble instance to wrap
     * @throws NullPointerException if bubble is null
     */
    public LocalBubbleReference(VonBubble bubble) {
        this.bubble = Objects.requireNonNull(bubble, "Bubble cannot be null");
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public LocalBubbleReference asLocal() {
        return this;
    }

    @Override
    public RemoteBubbleProxy asRemote() {
        throw new IllegalStateException("Cannot cast LocalBubbleReference to RemoteBubbleProxy");
    }

    @Override
    public UUID getBubbleId() {
        return bubble.id();
    }

    @Override
    public Point3D getPosition() {
        return bubble.position();
    }

    @Override
    public Set<UUID> getNeighbors() {
        return bubble.neighbors();
    }

    /**
     * Get the wrapped VonBubble instance.
     *
     * @return VonBubble
     */
    public VonBubble getBubble() {
        return bubble;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        var that = (LocalBubbleReference) o;
        return Objects.equals(bubble.id(), that.bubble.id());
    }

    @Override
    public int hashCode() {
        return Objects.hash(bubble.id());
    }

    @Override
    public String toString() {
        return "LocalBubbleReference{bubbleId=" + bubble.id() + "}";
    }
}
