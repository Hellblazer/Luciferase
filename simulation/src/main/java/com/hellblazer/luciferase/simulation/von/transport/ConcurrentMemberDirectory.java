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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe implementation of MemberDirectory using ConcurrentHashMap.
 * <p>
 * Provides atomic member registration/deregistration and deterministic
 * spatial key routing with proper race condition handling.
 * <p>
 * Implementation details:
 * <ul>
 *   <li>Storage: ConcurrentHashMap for lock-free reads</li>
 *   <li>Routing: Deterministic hashing (key bits XOR → modulo member count)</li>
 *   <li>Race handling: Null check after member selection</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ConcurrentMemberDirectory implements MemberDirectory {

    private final Map<UUID, ProcessAddress> registry = new ConcurrentHashMap<>();

    @Override
    public void registerMember(UUID memberId, ProcessAddress address) {
        Objects.requireNonNull(memberId, "memberId must not be null");
        Objects.requireNonNull(address, "address must not be null");
        registry.put(memberId, address);
    }

    @Override
    public void unregisterMember(UUID memberId) {
        Objects.requireNonNull(memberId, "memberId must not be null");
        registry.remove(memberId);
    }

    @Override
    public Optional<Transport.MemberInfo> lookupMember(UUID memberId) {
        Objects.requireNonNull(memberId, "memberId must not be null");
        var address = registry.get(memberId);
        if (address == null) {
            return Optional.empty();
        }
        return Optional.of(new Transport.MemberInfo(memberId, address.toUrl()));
    }

    @Override
    public Transport.MemberInfo routeToKey(TetreeKey<?> key) throws Transport.TransportException {
        Objects.requireNonNull(key, "key must not be null");

        // Get snapshot of member IDs (thread-safe)
        var members = new ArrayList<>(registry.keySet());
        if (members.isEmpty()) {
            throw new Transport.TransportException("No members available for routing");
        }

        // Deterministic routing: hash key to member index
        // Same algorithm as original SocketTransport.routeToKey()
        var hash = key.getLowBits() ^ key.getHighBits();
        var absHash = hash == Long.MIN_VALUE ? 0 : Math.abs(hash);
        var index = (int) (absHash % members.size());

        var targetId = members.get(index);
        var address = registry.get(targetId);

        // Handle race condition: member could be removed between snapshot and get
        if (address == null) {
            throw new Transport.TransportException("Member " + targetId + " not found (removed during routing)");
        }

        return new Transport.MemberInfo(targetId, address.toUrl());
    }

    @Override
    public ProcessAddress getAddressFor(UUID memberId) {
        Objects.requireNonNull(memberId, "memberId must not be null");
        return registry.get(memberId);
    }

    @Override
    public Set<UUID> getRegisteredMembers() {
        // Return defensive copy for thread-safe snapshot
        return new HashSet<>(registry.keySet());
    }
}
