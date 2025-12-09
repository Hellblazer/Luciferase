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
package com.hellblazer.luciferase.lucien.benchmark.baseline;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Runner for Morton encoding baseline benchmarks.
 * Provides programmatic configuration for JMH benchmarks.
 * 
 * @author hal.hildebrand
 */
public class RunMortonBaseline {
    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
            .include(MortonEncodingBaselineBenchmark.class.getSimpleName())
            .forks(1)
            .warmupIterations(3)
            .measurementIterations(5)
            .resultFormat(org.openjdk.jmh.results.format.ResultFormatType.JSON)
            .result("../morton-baseline-results.json")
            .build();

        new Runner(opt).run();
    }
}
