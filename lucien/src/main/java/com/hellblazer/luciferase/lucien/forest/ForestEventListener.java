/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.forest;

/**
 * Functional interface for receiving forest lifecycle events.
 *
 * Listeners are notified of structural changes in the forest (tree creation,
 * subdivision, removal, merging, and entity migration). Events are dispatched
 * synchronously but non-blocking (event records are small and immutable).
 *
 * Thread Safety: Implementations should be thread-safe as events may be
 * dispatched from multiple threads concurrently.
 *
 * Error Handling: Exceptions thrown by listeners are caught and logged by
 * the forest, ensuring one failing listener doesn't impact others.
 *
 * Example usage:
 * <pre>
 * ForestEventListener listener = event -> {
 *     switch (event) {
 *         case ForestEvent.TreeSubdivided e -> handleSubdivision(e);
 *         default -> {} // Ignore other events
 *     }
 * };
 * forest.addEventListener(listener);
 * </pre>
 *
 * @author hal.hildebrand
 */
@FunctionalInterface
public interface ForestEventListener {

    /**
     * Called when a forest event occurs.
     *
     * Implementations should be fast and non-blocking. For expensive operations,
     * consider dispatching to a background thread or queue.
     *
     * @param event the forest event
     */
    void onEvent(ForestEvent event);
}
