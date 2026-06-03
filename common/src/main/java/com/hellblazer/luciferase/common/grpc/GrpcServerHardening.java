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

package com.hellblazer.luciferase.common.grpc;

import io.grpc.ServerBuilder;

import java.util.Objects;

/**
 * Shared DoS hardening for Luciferase gRPC servers (RDR-013, Luciferase-06ujn).
 *
 * <p>Applies an <b>explicit</b> inbound message-size bound to a {@link ServerBuilder}. Without this, a server
 * relies on gRPC's <i>implicit</i> 4&nbsp;MiB default — a real bound, but invisible, unconfigurable, and easy to
 * remove by accident. A single oversized frame forces a large heap allocation before any application code runs;
 * an explicit, tunable limit is the single DoS knob operators on a hostile network need.
 *
 * <p>This is the one place server construction across the codebase (the production Ghost server, and any future
 * Balance server) applies the inbound bound, so the policy cannot drift between servers. Authentication is a
 * separate concern, applied via the RDR-005 {@code GrpcCredentialFactory.ServerAuth} unit (trust-anchored
 * transport credentials + the matching {@code PeerAuthInterceptor}); this helper deliberately does not touch it.
 *
 * @author hal.hildebrand
 */
public final class GrpcServerHardening {

    /** Default explicit inbound message bound: 4&nbsp;MiB, matching gRPC's implicit default but now explicit. */
    public static final int DEFAULT_MAX_INBOUND_MESSAGE_BYTES = 4 * 1024 * 1024;

    /** Default explicit inbound metadata (header) bound: 8&nbsp;KiB, matching gRPC's implicit default. */
    public static final int DEFAULT_MAX_INBOUND_METADATA_BYTES = 8 * 1024;

    private GrpcServerHardening() {
    }

    /**
     * Apply the default inbound message and metadata bounds to the given server builder.
     *
     * @param builder the server builder to harden (not null), mutated in place
     * @throws NullPointerException if {@code builder} is null
     */
    public static void applyInboundLimits(ServerBuilder<?> builder) {
        applyInboundLimit(builder, DEFAULT_MAX_INBOUND_MESSAGE_BYTES);
    }

    /**
     * Apply an explicit inbound message-size bound (plus the default metadata bound) to the given server builder.
     * Header decoding happens before the message body, so the metadata bound closes a header-stuffing DoS vector
     * that the message-size limit alone does not.
     *
     * @param builder                the server builder to harden (not null), mutated in place
     * @param maxInboundMessageBytes the maximum inbound message size in bytes (must be positive)
     * @throws NullPointerException     if {@code builder} is null
     * @throws IllegalArgumentException if {@code maxInboundMessageBytes <= 0}
     */
    public static void applyInboundLimit(ServerBuilder<?> builder, int maxInboundMessageBytes) {
        Objects.requireNonNull(builder, "builder must not be null");
        if (maxInboundMessageBytes <= 0) {
            throw new IllegalArgumentException(
                "maxInboundMessageBytes must be positive, got " + maxInboundMessageBytes);
        }
        builder.maxInboundMessageSize(maxInboundMessageBytes);
        builder.maxInboundMetadataSize(DEFAULT_MAX_INBOUND_METADATA_BYTES);
    }
}
