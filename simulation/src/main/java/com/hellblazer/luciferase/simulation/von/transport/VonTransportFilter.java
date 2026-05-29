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

import java.io.ObjectInputFilter;

/**
 * Deserialization allow-list for the VoN socket transport (RDR-004, Direction A).
 * <p>
 * The VoN transport reads Java-serialized {@code TransportVonMessage} objects directly off a network
 * socket. Unfiltered {@code ObjectInputStream.readObject()} on untrusted network input is the canonical
 * Java deserialization RCE vector: a gadget chain executes during deserialization, before the
 * {@code (TransportVonMessage)} cast is reached. This filter restricts deserialization to the concrete
 * types actually on the wire and rejects everything else.
 * <p>
 * Unlike the file-input allow-lists added in Tranche D-1, this network-path filter deliberately
 * <em>omits</em> the broad {@code java.util.*} wildcard: that wildcard admits gadget-bearing collections
 * ({@code PriorityQueue}, {@code TreeMap}, {@code LinkedList}) that appear in published ysoserial chains.
 * The actual wire payload is records of {@code String}/{@code float}/{@code double}/{@code long}/{@code Long}
 * plus {@code List} fields whose concrete type is {@code java.util.ArrayList}; there is no
 * {@code javax.vecmath} or JavaFX type on the wire (the {@code Point3f}/{@code Point3d} in the transport
 * records are conversion-only, never serialized).
 *
 * @author hal.hildebrand
 */
final class VonTransportFilter {

    /**
     * Allow-list pattern for {@link ObjectInputFilter.Config#createFilter(String)}. Concrete wire types
     * only; the trailing {@code !*} rejects everything not explicitly named.
     */
    static final String PATTERN =
        "com.hellblazer.luciferase.simulation.von.TransportVonMessage;"
        + "com.hellblazer.luciferase.simulation.von.TransportGhostData;"
        + "com.hellblazer.luciferase.simulation.von.TransportNeighborInfo;"
        + "java.util.ArrayList;"
        + "java.util.Collections$UnmodifiableList;"
        + "java.util.Arrays$ArrayList;"
        + "java.lang.*;"
        + "java.time.*;"
        + "java.math.*;"
        + "!*";

    private VonTransportFilter() {
    }

    /**
     * @return a fresh {@link ObjectInputFilter} enforcing the VoN wire allow-list
     */
    static ObjectInputFilter create() {
        return ObjectInputFilter.Config.createFilter(PATTERN);
    }
}
