#!/bin/bash
# Test script for Phase 6B5 performance test fixes
# Bead: Luciferase-x8ei

set -e

echo "=== Testing Phase 6B5 Performance Test Fixes ==="
echo ""

echo "1. Testing testHeapStability..."
mvn test -pl simulation -Dtest=PerformanceStabilityTest#testHeapStability -Dsurefire.rerunFailingTestsCount=0 -q
echo "✓ testHeapStability passed"
echo ""

echo "2. Testing testEntityRetentionUnderLoad..."
mvn test -pl simulation -Dtest=PerformanceStabilityTest#testEntityRetentionUnderLoad -Dsurefire.rerunFailingTestsCount=0 -q
echo "✓ testEntityRetentionUnderLoad passed"
echo ""

echo "3. Running full PerformanceStabilityTest suite..."
mvn test -pl simulation -Dtest=PerformanceStabilityTest -Dsurefire.rerunFailingTestsCount=0 -q
echo "✓ Full test suite passed"
echo ""

echo "=== All tests passed! ==="
