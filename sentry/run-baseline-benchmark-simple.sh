#!/bin/bash

# Simple script to run a single benchmark class directly

echo "Building Sentry module..."
mvn -f sentry/pom.xml clean test-compile

echo "Running FlipOperationBenchmark..."
TIMESTAMP=$(date +"%Y-%m-%d-%H-%M-%S")
mkdir -p sentry/target/benchmarks

java -cp "sentry/target/test-classes:sentry/target/classes:$(mvn -f sentry/pom.xml dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=/dev/stdout)" \
    com.hellblazer.sentry.benchmark.BenchmarkRunner

echo "Benchmark complete. Check sentry/target/ for results."