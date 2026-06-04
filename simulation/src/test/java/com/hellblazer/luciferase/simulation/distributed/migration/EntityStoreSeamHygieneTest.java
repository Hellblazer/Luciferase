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

package com.hellblazer.luciferase.simulation.distributed.migration;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.111: the production entity-store seam must not contain a
 * Thread.sleep timing hook, and the test-only delay interface must live in the test source tree.
 *
 * @author hal.hildebrand
 */
class EntityStoreSeamHygieneTest {

    @Test
    void productionSeamHasNoSimulateDelayHook() {
        // EntityStoreOperations is the production seam CrossProcessMigration depends on.
        var methods = Arrays.stream(EntityStoreOperations.class.getDeclaredMethods())
                            .map(Method::getName)
                            .toList();
        assertFalse(methods.contains("simulateDelay"),
                    "production EntityStoreOperations must not expose a Thread.sleep delay hook");
        // The store ops it SHOULD expose:
        assertTrue(methods.contains("removeEntity"));
        assertTrue(methods.contains("addEntity"));
        assertTrue(methods.contains("isReachable"));
    }

    @Test
    void testableEntityStoreLivesInTestTreeAndExtendsProductionSeam() {
        // TestableEntityStore (with simulateDelay) must extend the production seam...
        assertTrue(EntityStoreOperations.class.isAssignableFrom(TestableEntityStore.class),
                   "TestableEntityStore must extend the production EntityStoreOperations seam");
        var testMethods = Arrays.stream(TestableEntityStore.class.getDeclaredMethods())
                               .map(Method::getName)
                               .toList();
        assertTrue(testMethods.contains("simulateDelay"),
                   "the delay hook belongs on the test-only interface");

        // ...and it must be loaded from a test-classes location, not the production jar/classes.
        URL location = TestableEntityStore.class.getProtectionDomain().getCodeSource().getLocation();
        assertTrue(location.getPath().contains("test-classes"),
                   "TestableEntityStore must reside in the test source tree (loaded from "
                   + location.getPath() + ")");
    }
}
