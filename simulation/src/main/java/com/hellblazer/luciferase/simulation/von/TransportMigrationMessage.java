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

package com.hellblazer.luciferase.simulation.von;

import java.io.Serializable;

/**
 * Primitive-only wire carrier for the 2PC {@link MigrationProtocolMessages} family (Luciferase-l5gr9).
 * <p>
 * The 2PC domain messages carry rich types — {@code IdempotencyToken} and {@code EntitySnapshot}
 * (the latter with an arbitrary {@code Object content}). Placing those on the VoN deserialization
 * allow-list ({@code VonTransportFilter}) would re-open the RDR-004 gadget vector. This record instead
 * decomposes every subtype into {@code String}/boxed-primitive fields so the narrow allow-list never
 * has to admit a domain type. The {@code subtype} discriminator drives reconstruction.
 * <p>
 * Boxed types ({@code Long}/{@code Double}/{@code Boolean}) are used for fields that belong to an
 * optional group (idempotency token, entity snapshot): a {@code null} signals the group is absent.
 * <p>
 * {@code snapContent} carries the entity content as a {@code String} only. Arbitrary domain content
 * is intentionally NOT serialized — round-tripping rich content under the narrow allow-list would
 * require a per-type codec, which is out of scope. Callers needing content fidelity must register a
 * codec; the 2PC control fields (transaction, token, position, epoch/version) always round-trip.
 *
 * @author hal.hildebrand
 */
public record TransportMigrationMessage(
    String subtype,            // "PrepareRequest" | "PrepareResponse" | "CommitRequest" | ...
    String transactionId,      // UUID as String
    long timestamp,
    // ---- IdempotencyToken group (null when absent) ----
    String tokEntityId,
    String tokSourceProcessId, // UUID as String
    String tokDestProcessId,   // UUID as String
    Long   tokTimestamp,
    String tokNonce,           // UUID as String
    // ---- EntitySnapshot group (null when absent) ----
    String  snapEntityId,
    Double  snapPosX,
    Double  snapPosY,
    Double  snapPosZ,
    String  snapContent,       // content.toString() only — see class doc
    String  snapAuthorityBubbleId, // UUID as String
    Long    snapEpoch,
    Long    snapVersion,
    Long    snapTimestamp,
    // ---- routing / coordinates ----
    String  sourceId,          // UUID as String (PrepareRequest)
    String  destId,            // UUID as String (PrepareRequest)
    // ---- response fields ----
    Boolean success,           // PrepareResponse / CommitResponse / AbortResponse(rolledBack)
    String  reason,
    String  destProcessId,     // PrepareResponse destination process UUID as String
    Boolean confirmed          // CommitRequest
) implements Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;
}
