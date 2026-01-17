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

/**
 * Listener interface for topology change events.
 * <p>
 * Implementations can register with topology components to receive
 * real-time notifications of topology changes (split, merge, move),
 * density state transitions, and consensus voting.
 * <p>
 * Use cases:
 * <ul>
 *   <li>WebSocket streaming to visualization clients</li>
 *   <li>Metrics collection and monitoring</li>
 *   <li>Logging and audit trails</li>
 *   <li>Testing and validation</li>
 * </ul>
 * <p>
 * Interactive Visualization Demo Enhancement
 *
 * @author hal.hildebrand
 */
@FunctionalInterface
public interface TopologyEventListener {

    /**
     * Called when a topology event occurs.
     * <p>
     * This method is called synchronously on the thread that fires the event.
     * Implementations should be non-blocking and thread-safe.
     *
     * @param event the topology event
     */
    void onTopologyEvent(TopologyEvent event);
}
