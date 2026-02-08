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

package com.hellblazer.luciferase.simulation.von.transport;

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.von.Transport;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Manages member registration and routing for spatial keys.
 * <p>
 * Provides thread-safe member registry with deterministic spatial routing.
 * Extracted from SocketTransport to achieve Single Responsibility Principle.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Member registration and deregistration</li>
 *   <li>Member lookup by UUID</li>
 *   <li>Deterministic routing from spatial keys to members</li>
 * </ul>
 * <p>
 * Thread-Safety: All implementations must support concurrent access from multiple threads.
 *
 * @author hal.hildebrand
 */
public interface MemberDirectory {

    /**
     * Register a member with its network address.
     * <p>
     * If member already registered, updates to new address.
     *
     * @param memberId UUID of the member
     * @param address  ProcessAddress for the member
     * @throws NullPointerException if memberId or address is null
     */
    void registerMember(UUID memberId, ProcessAddress address);

    /**
     * Unregister a member from the directory.
     * <p>
     * <strong>NEW CAPABILITY:</strong> This method is not present in current SocketTransport.
     * Provides clean testing seam to replace reflection-based tests.
     * <p>
     * If member not registered, this is a no-op.
     *
     * @param memberId UUID of the member to remove
     * @throws NullPointerException if memberId is null
     */
    void unregisterMember(UUID memberId);

    /**
     * Look up a member by UUID.
     *
     * @param memberId UUID of the member
     * @return MemberInfo if registered, empty if not found
     * @throws NullPointerException if memberId is null
     */
    Optional<Transport.MemberInfo> lookupMember(UUID memberId);

    /**
     * Route a spatial key to a member using deterministic hashing.
     * <p>
     * Uses key's bits to select member consistently. Same key always routes to same member
     * as long as member set unchanged.
     * <p>
     * Thread-safe: Handles race condition where member removed between selection and lookup.
     *
     * @param key Spatial key to route
     * @return MemberInfo for the selected member
     * @throws Transport.TransportException if no members registered or member removed during routing
     * @throws NullPointerException         if key is null
     */
    Transport.MemberInfo routeToKey(TetreeKey<?> key) throws Transport.TransportException;

    /**
     * Get the ProcessAddress for a registered member.
     * <p>
     * Returns null if member not registered.
     *
     * @param memberId UUID of the member
     * @return ProcessAddress if registered, null if not found
     * @throws NullPointerException if memberId is null
     */
    ProcessAddress getAddressFor(UUID memberId);

    /**
     * Get all registered member UUIDs.
     * <p>
     * Returns a thread-safe snapshot. Changes to the directory do not affect the returned set.
     *
     * @return Set of registered member UUIDs
     */
    Set<UUID> getRegisteredMembers();
}
