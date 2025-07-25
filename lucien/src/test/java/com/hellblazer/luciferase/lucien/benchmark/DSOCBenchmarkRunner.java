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
package com.hellblazer.luciferase.lucien.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JUnit test runner for DSOC performance benchmarks
 *
 * @author hal.hildebrand
 */
public class DSOCBenchmarkRunner {
    
    @Test
    @Disabled("Manual benchmark - run explicitly when needed")
    @Tag("benchmark")
    public void runDSOCBenchmarks() throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(DSOCPerformanceBenchmark.class.getSimpleName())
            .forks(1)
            .warmupIterations(2)
            .measurementIterations(3)
            .threads(1)
            .param("entityCount", "1000", "10000")
            .param("occlusionRatio", "0.3", "0.7") 
            .param("dynamicEntityRatio", "0.2")
            .param("zBufferResolution", "1024")
            .build();
            
        new Runner(opt).run();
    }
}