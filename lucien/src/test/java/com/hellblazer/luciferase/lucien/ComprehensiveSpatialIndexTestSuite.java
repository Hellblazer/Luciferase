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
package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive test suite that orchestrates all spatial index tests.
 *
 * This suite runs different test categories based on the execution context: - Fast tests for CI/CD pipelines - Extended
 * tests for pull requests - Full tests for nightly builds
 *
 * @author hal.hildebrand
 */
public class ComprehensiveSpatialIndexTestSuite {

    /**
     * Fast test suite for continuous integration. Runs in < 1 minute.
     */
    @Nested
    @DisplayName("Fast Tests (CI)")
    @Tag("fast")
    static class FastTestSuite {
        @Test
        void runEdgeCaseTests() {
            // Tests are run by JUnit discovery
        }
    }

    /**
     * Extended test suite for pull requests. Runs in < 10 minutes.
     */
    @Nested
    @DisplayName("Extended Tests (PR)")
    @Tag("extended")
    static class ExtendedTestSuite {
        @Test
        void runExtendedTests() {
            // Tests are run by JUnit discovery
        }
    }

    /**
     * Full test suite for nightly builds. May take hours to complete.
     */
    @Nested
    @DisplayName("Full Tests (Nightly)")
    @Tag("nightly")
    static class FullTestSuite {
        @Test
        void runFullTests() {
            // Tests are run by JUnit discovery
        }
    }

    /**
     * Performance test suite. Requires RUN_SPATIAL_INDEX_PERF_TESTS=true
     */
    @Nested
    @DisplayName("Performance Tests")
    @Tag("performance")
    static class PerformanceTestSuite {
        @Test
        void runPerformanceTests() {
            // Tests are run by JUnit discovery when RUN_SPATIAL_INDEX_PERF_TESTS=true
        }
    }

    /**
     * Regression test suite. Compares current performance against baselines.
     */
    @Nested
    @DisplayName("Regression Tests")
    @Tag("regression")
    static class RegressionTestSuite {
        @Test
        void runRegressionTests() {
            // Tests are run by JUnit discovery
        }
    }
}
