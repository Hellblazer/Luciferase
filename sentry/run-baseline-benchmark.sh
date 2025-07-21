#!/bin/bash

# Script to run baseline benchmarks for Sentry optimization

echo "Running Sentry Baseline Benchmarks..."
echo "====================================="

# Get timestamp
TIMESTAMP=$(date +"%Y-%m-%d-%H-%M-%S")

# Build the project
echo "Building project..."
mvn -f sentry/pom.xml clean compile test-compile

# Create output directory (after clean!)
mkdir -p sentry/target/benchmarks

# Run benchmarks
echo "Running benchmarks..."
java -cp "sentry/target/test-classes:sentry/target/classes:$(mvn -f sentry/pom.xml dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=/dev/stdout)" \
    org.openjdk.jmh.Main \
    -f 1 \
    -wi 3 -w 1s \
    -i 5 -r 1s \
    -rf json -rff "sentry/target/benchmarks/baseline-$TIMESTAMP.json" \
    -o "sentry/target/benchmarks/baseline-$TIMESTAMP.txt" \
    "com.hellblazer.sentry.benchmark.*"

echo ""
echo "Baseline benchmark complete!"
echo "Results saved to:"
echo "  JSON: sentry/target/benchmarks/baseline-$TIMESTAMP.json"
echo "  Text: sentry/target/benchmarks/baseline-$TIMESTAMP.txt"
echo ""
echo "Save these baseline results for comparison after implementing optimizations."