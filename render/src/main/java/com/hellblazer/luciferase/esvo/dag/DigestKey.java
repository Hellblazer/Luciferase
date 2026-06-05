/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.esvo.dag;

import java.util.Arrays;

/**
 * Value-equality wrapper around a byte[] digest for use as a Map key.
 *
 * <p>Byte arrays use identity equality, which causes distinct but content-equal digests
 * to hash to different buckets. This class delegates equals/hashCode to
 * {@link Arrays#equals}/{@link Arrays#hashCode} so the full digest bytes determine map
 * membership — avoiding the 64-bit truncation collision risk of the previous
 * {@code long}-keyed approach (Luciferase-7wzml.23 wave-2 remediation).
 *
 * <p>The canonical constructor defensively copies the supplied array so the key is
 * immutable even if the caller (e.g. a hasher returning its internal cache buffer)
 * later reuses or mutates the original — protecting Map equality invariants.
 *
 * @author hal.hildebrand
 */
public record DigestKey(byte[] digest) {

    public DigestKey {
        digest = digest.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DigestKey other)) return false;
        return Arrays.equals(digest, other.digest);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(digest);
    }
}
