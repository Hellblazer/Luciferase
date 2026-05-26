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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.balancing.TwoOneBalanceChecker.BalanceViolation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deduplication + robustness regression for {@link ButterflyViolationAggregator} (Luciferase-xb5).
 * The pre-fix {@code ViolationKey} deduplicated by {@code (localKey.hashCode(), ghostKey.hashCode())}
 * and compared only those ints, so two distinct violations whose keys merely collide on hashCode were
 * falsely merged — silently dropping a real violation. The fix stores the actual keys and delegates
 * equals/hashCode to them. Separately, {@code exchangeWithPartner} now guards a null exchanger result.
 *
 * @author hal.hildebrand
 */
class ButterflyViolationAggregatorDedupTest {

    /** A SpatialKey whose instances all share one hashCode but are equal only by id — forces a collision. */
    static final class CollidingKey implements SpatialKey<CollidingKey> {
        final int id;

        CollidingKey(int id) {
            this.id = id;
        }

        @Override
        public int hashCode() {
            return 42; // every instance collides
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CollidingKey c && c.id == id;
        }

        @Override
        public int compareTo(CollidingKey o) {
            return Integer.compare(id, o.id);
        }

        @Override
        public byte getLevel() {
            return 0;
        }

        @Override
        public CollidingKey parent() {
            return this;
        }

        @Override
        public CollidingKey root() {
            return this;
        }

        @Override
        public String toString() {
            return "CK" + id;
        }
    }

    private static BalanceViolation<CollidingKey> violation(int localId, int ghostId) {
        return new BalanceViolation<>(new CollidingKey(localId), new CollidingKey(ghostId), 0, 3, 3, 0);
    }

    @Test
    void hashCodeCollidingButDistinctViolationsAreNotDeduplicated() {
        // single partition -> pure local dedup, no exchange
        var agg = new ButterflyViolationAggregator<CollidingKey>(0, 1, (partner, batch) -> batch);

        // two genuinely distinct violations; all four keys share hashCode 42 but none are equal
        var v1 = violation(1, 2);
        var v2 = violation(3, 4);

        var result = agg.aggregateViolations(List.of(v1, v2));

        assertEquals(2, result.size(),
                     "distinct violations whose keys collide on hashCode must NOT be deduplicated");
        assertTrue(result.contains(v1) && result.contains(v2), "both distinct violations retained");
    }

    @Test
    void genuinelyEqualViolationsAreStillDeduplicated() {
        var agg = new ButterflyViolationAggregator<CollidingKey>(0, 1, (partner, batch) -> batch);

        var result = agg.aggregateViolations(List.of(violation(1, 2), violation(1, 2)));

        assertEquals(1, result.size(), "equal violations (same keys) still collapse to one");
    }

    @Test
    void nullExchangeResultIsTreatedAsEmptyAndDoesNotThrow() {
        // two partitions -> one butterfly round with a partner; exchanger returns null
        var agg = new ButterflyViolationAggregator<CollidingKey>(0, 2, (partner, batch) -> null);

        var local = violation(5, 6);
        var result = assertDoesNotThrow(() -> agg.aggregateViolations(List.of(local)),
                                        "a null exchange result must not NPE the aggregation");
        assertTrue(result.contains(local), "local violation survives a null partner exchange");
    }
}
