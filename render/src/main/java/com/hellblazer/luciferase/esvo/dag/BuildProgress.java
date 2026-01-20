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
package com.hellblazer.luciferase.esvo.dag;

/**
 * Immutable progress snapshot for DAG build operations.
 *
 * <p>Represents the current phase and percentage complete during asynchronous
 * DAG construction. The percentage is automatically clamped to [0, 100] range.
 *
 * <p>Use the static factory method {@link #of(BuildPhase, int)} to create instances
 * with automatic clamping.
 *
 * @param phase current build phase
 * @param percentComplete completion percentage (clamped to [0, 100])
 * @author hal.hildebrand
 * @see BuildPhase
 */
public record BuildProgress(BuildPhase phase, int percentComplete) {

    /**
     * Create a BuildProgress with automatic percentage clamping.
     *
     * @param phase current build phase
     * @param percent completion percentage (will be clamped to [0, 100])
     * @return new BuildProgress instance
     */
    public static BuildProgress of(BuildPhase phase, int percent) {
        return new BuildProgress(phase, Math.min(100, Math.max(0, percent)));
    }
}
