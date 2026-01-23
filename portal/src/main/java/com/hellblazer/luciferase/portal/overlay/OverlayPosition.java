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
package com.hellblazer.luciferase.portal.overlay;

import javafx.geometry.Pos;

/**
 * Defines overlay positioning within the viewport.
 * Maps to JavaFX Pos alignment values for StackPane placement.
 *
 * @author hal.hildebrand
 */
public enum OverlayPosition {
    /**
     * Top-left corner of the viewport.
     */
    TOP_LEFT(Pos.TOP_LEFT),

    /**
     * Top-right corner of the viewport.
     */
    TOP_RIGHT(Pos.TOP_RIGHT),

    /**
     * Bottom-left corner of the viewport.
     */
    BOTTOM_LEFT(Pos.BOTTOM_LEFT),

    /**
     * Bottom-right corner of the viewport.
     */
    BOTTOM_RIGHT(Pos.BOTTOM_RIGHT);

    private final Pos alignment;

    OverlayPosition(Pos alignment) {
        this.alignment = alignment;
    }

    /**
     * Gets the JavaFX Pos alignment value.
     *
     * @return The alignment for StackPane.setAlignment()
     */
    public Pos getAlignment() {
        return alignment;
    }
}
