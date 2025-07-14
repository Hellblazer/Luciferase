#!/bin/bash

# Test script to demonstrate the performance update process

set -e

echo "🧪 Testing Performance Documentation Update System..."
echo ""

# Create test data with Prism results
cat > target/prism-test-data.csv << EOF
operation,throughput,latency
insert-prism,1.1 μs/op,
knn-prism,,14.8 μs/op
range-prism,,10.2 μs/op
memory-prism,265000,
EOF

echo "📊 Test data created:"
cat target/prism-test-data.csv
echo ""

# Backup current doc
cp doc/PERFORMANCE_METRICS_MASTER.md doc/PERFORMANCE_METRICS_MASTER.md.test-backup

echo "📝 Running Robust Performance Updater..."
mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.lucien.performance.RobustPerformanceUpdater" \
    -Dexec.args="target/prism-test-data.csv" \
    -Dexec.classpathScope=test -q

echo ""
echo "✅ Update complete!"
echo ""

# Show what changed
echo "📋 Changes made:"
if command -v colordiff &> /dev/null; then
    colordiff -u doc/PERFORMANCE_METRICS_MASTER.md.test-backup doc/PERFORMANCE_METRICS_MASTER.md | head -50 || true
else
    diff -u doc/PERFORMANCE_METRICS_MASTER.md.test-backup doc/PERFORMANCE_METRICS_MASTER.md | head -50 || true
fi

echo ""
echo "💡 To restore original: cp doc/PERFORMANCE_METRICS_MASTER.md.test-backup doc/PERFORMANCE_METRICS_MASTER.md"