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
     *
     * <p>Resource-limit directives (Luciferase-7wzml.33) are prepended to close the heap-exhaustion
     * DoS vector that exists when the class allow-list alone is applied.  Sizing rationale:
     * <ul>
     *   <li><b>maxbytes=524288</b> (512 KiB) — the largest legitimate payload is a GhostSync carrying
     *       ~256 {@link com.hellblazer.luciferase.simulation.von.TransportGhostData} records (13 fields
     *       each — 10 original + velX/velY/velZ added in Luciferase-chmxx — ~512 bytes serialized per
     *       record → ~128 KiB; 3 extra floats ≈ 12 bytes/record); 512 KiB retains comfortable
     *       4× headroom.</li>
     *   <li><b>maxarray=65536</b> — the dominant cost is String-internal byte arrays: 256 ghosts × 4
     *       String fields × ~50 chars = ~51 200 elements plus the ArrayList backing array; 65 536 is
     *       ~1.3× headroom while blocking a 100 M-element ArrayList attack.</li>
     *   <li><b>maxdepth=10</b> — the deepest legitimate chain is 5 levels:
     *       TransportVonMessage → ArrayList → TransportGhostData/TransportNeighborInfo →
     *       TransportBubbleBounds/String → byte[]; 10 is 2× the legitimate maximum.</li>
     *   <li><b>maxrefs=5000</b> — a 256-ghost GhostSync produces ~1 282 unique object references
     *       (1 outer + 1 list + 256 records + 1 024 Strings); 5 000 is ~4× headroom.</li>
     * </ul>
     */
    static final String PATTERN =
        "maxbytes=524288;"
        + "maxarray=65536;"
        + "maxdepth=10;"
        + "maxrefs=5000;"
        + "com.hellblazer.luciferase.simulation.von.TransportVonMessage;"
        + "com.hellblazer.luciferase.simulation.von.TransportGhostData;"
        + "com.hellblazer.luciferase.simulation.von.TransportNeighborInfo;"
        + "com.hellblazer.luciferase.simulation.von.TransportBubbleBounds;"
        + "com.hellblazer.luciferase.simulation.von.TransportMigrationMessage;"
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
