package com.hellblazer.sentry.benchmark;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Main runner for Sentry benchmarks.
 * Saves results with timestamps for tracking performance over time.
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
        String resultFile = "sentry/target/benchmark-results-" + timestamp + ".json";

        Options opt = new OptionsBuilder()
                .include(FlipOperationBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.JSON)
                .result(resultFile)
                .build();

        new Runner(opt).run();
        
        System.out.println("Benchmark results saved to: " + resultFile);
        System.out.println("\nUse this baseline for comparison after implementing optimizations.");
    }
}