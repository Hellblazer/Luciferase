#!/bin/bash

# Simple script to run performance updates
# This handles the common performance testing workflow

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

echo "🚀 Performance Update Tool"
echo "========================="
echo ""
echo "Choose an option:"
echo "1) Run performance tests and update docs (full workflow)"
echo "2) Just update docs from existing test results"
echo "3) Run specific benchmark test"
echo ""
read -p "Enter choice (1-3): " choice

case $choice in
    1)
        echo "Running full performance workflow..."
        export RUN_SPATIAL_INDEX_PERF_TESTS=true
        mvn clean verify -Pperformance-full
        ;;
    2)
        echo "Updating docs from existing results..."
        mvn compile -Pperformance-update
        ;;
    3)
        echo "Available benchmarks:"
        echo "- OctreeVsTetreeBenchmark"
        echo "- OctreeVsTetreeVsPrismBenchmark"
        echo "- BaselinePerformanceBenchmark"
        echo ""
        read -p "Enter benchmark class name: " benchmark
        echo "Running $benchmark..."
        export RUN_SPATIAL_INDEX_PERF_TESTS=true
        mvn test -Dtest=$benchmark
        echo ""
        echo "Now updating documentation..."
        mvn compile -Pperformance-update
        ;;
    *)
        echo "Invalid choice"
        exit 1
        ;;
esac

echo ""
echo ""
echo "✅ Done! Check the following:"
echo "- Performance data: target/performance-output/performance-data.csv"
echo "- Updated docs: doc/PERFORMANCE_METRICS_MASTER.md"
echo ""
echo "To archive results to permanent storage, run:"
echo "  mvn compile -Pperformance-archive"
echo ""
echo "Run 'git diff doc/PERFORMANCE_METRICS_MASTER.md' to see changes"