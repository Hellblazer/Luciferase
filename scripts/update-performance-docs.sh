#!/bin/bash

# Update Performance Documentation Script
# Purpose: Extract performance metrics from benchmark runs and update documentation
# Usage: ./scripts/update-performance-docs.sh [benchmark-output-file]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
METRICS_FILE="$PROJECT_ROOT/lucien/doc/PERFORMANCE_METRICS_MASTER.md"
CURRENT_DATE=$(date '+%Y-%m-%d')

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

cd "$PROJECT_ROOT"

echo "=========================================="
echo "Performance Documentation Update"
echo "=========================================="
echo "Date: $CURRENT_DATE"
echo ""

# Check if benchmark output file provided
if [ -n "$1" ] && [ -f "$1" ]; then
    BENCHMARK_OUTPUT="$1"
    echo -e "${BLUE}Using benchmark output: $BENCHMARK_OUTPUT${NC}"
else
    echo -e "${YELLOW}No benchmark output file provided${NC}"
    echo "Usage: $0 <benchmark-output-file>"
    echo ""
    echo "Running benchmarks now..."
    
    # Run performance benchmarks
    mvn clean test -Pperformance -q > /tmp/luciferase-perf-$$.txt 2>&1
    BENCHMARK_OUTPUT="/tmp/luciferase-perf-$$.txt"
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Benchmarks completed${NC}"
    else
        echo -e "${YELLOW}! Benchmark run had issues, check output${NC}"
    fi
fi

echo ""

# Extract key metrics
echo -e "${BLUE}Extracting performance metrics...${NC}"

# Create backup of current metrics
if [ -f "$METRICS_FILE" ]; then
    cp "$METRICS_FILE" "${METRICS_FILE}.backup-${CURRENT_DATE}"
    echo "  Backed up current metrics to ${METRICS_FILE}.backup-${CURRENT_DATE}"
fi

# Extract environment info
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
OS_VERSION=$(uname -s)
ARCH=$(uname -m)

echo "  Environment:"
echo "    Java: $JAVA_VERSION"
echo "    OS: $OS_VERSION"
echo "    Arch: $ARCH"
echo ""

# Parse benchmark results
# This is a template - actual parsing depends on JMH output format
echo -e "${BLUE}Parsing benchmark results...${NC}"

# Look for key benchmarks
if grep -q "OctreeVsTetreeBenchmark" "$BENCHMARK_OUTPUT"; then
    echo "  ✓ Found OctreeVsTetreeBenchmark results"
fi

if grep -q "GhostPerformanceBenchmark" "$BENCHMARK_OUTPUT"; then
    echo "  ✓ Found GhostPerformanceBenchmark results"
fi

if grep -q "LockFreeOperationsBenchmark" "$BENCHMARK_OUTPUT"; then
    echo "  ✓ Found LockFreeOperationsBenchmark results"
fi

echo ""

# Create performance summary
SUMMARY_FILE="$PROJECT_ROOT/performance-results/summary-${CURRENT_DATE}.txt"
mkdir -p "$PROJECT_ROOT/performance-results"

cat > "$SUMMARY_FILE" << EOF
Performance Benchmark Summary
============================
Date: $CURRENT_DATE
Environment: $OS_VERSION $ARCH, Java $JAVA_VERSION

Benchmark Results:
EOF

# Extract results (this is a template - customize based on actual output)
grep "Benchmark\|Score\|ops/s" "$BENCHMARK_OUTPUT" >> "$SUMMARY_FILE" 2>/dev/null || true

echo -e "${GREEN}✓ Performance summary written to: $SUMMARY_FILE${NC}"
echo ""

# Compare with baseline if exists
BASELINE_FILE="$PROJECT_ROOT/performance-results/baseline.txt"
if [ -f "$BASELINE_FILE" ]; then
    echo -e "${BLUE}Comparing with baseline...${NC}"
    echo ""
    echo "To update PERFORMANCE_METRICS_MASTER.md:"
    echo "  1. Review: $SUMMARY_FILE"
    echo "  2. Compare with: $BASELINE_FILE"
    echo "  3. Update: $METRICS_FILE"
    echo "  4. Set 'Last Updated' header to: $CURRENT_DATE"
    echo ""
    echo "If performance changed by >10%, document the reason in the metrics file."
else
    echo -e "${YELLOW}No baseline file found${NC}"
    echo "To create baseline:"
    echo "  cp $SUMMARY_FILE $BASELINE_FILE"
fi

echo ""
echo "=========================================="
echo "Documentation Update Checklist"
echo "=========================================="
echo ""
echo "If performance metrics changed significantly (>10%):"
echo "  [ ] Update $METRICS_FILE"
echo "  [ ] Set 'Last Updated: $CURRENT_DATE'"
echo "  [ ] Document reason for performance change"
echo "  [ ] Update related API documentation with new performance characteristics"
echo "  [ ] Update ARCHITECTURE_SUMMARY.md if optimization changed structure"
echo "  [ ] Add entry to HISTORICAL_FIXES_REFERENCE.md if significant"
echo ""
echo "For documentation standards, see: DOCUMENTATION_STANDARDS.md"
echo "For update checklist, see: .github/DOCUMENTATION_UPDATE_CHECKLIST.md"
echo ""

# Clean up temp file if we created it
if [ "$BENCHMARK_OUTPUT" = "/tmp/luciferase-perf-$$.txt" ]; then
    rm -f "$BENCHMARK_OUTPUT"
fi
