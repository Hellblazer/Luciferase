package com.hellblazer.sentry.benchmark;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Simple test to verify benchmarks can run
 */
public class SimpleBenchmarkTest {

    @Test
    public void testBenchmarksCanRun() throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(SimpleBenchmark.class.getSimpleName())
                .warmupIterations(1)
                .measurementIterations(1)
                .forks(0) // Run in same JVM for testing
                .build();

        Collection<RunResult> results = new Runner(opt).run();
        assertFalse(results.isEmpty(), "Benchmarks should have run");
    }

    @State(Scope.Thread)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public static class SimpleBenchmark {
        
        private int counter = 0;

        @Benchmark
        public int simpleIncrement() {
            return ++counter;
        }
    }
}