#!/bin/bash

# Test script to demonstrate the fixed performance update process

set -e

echo "🧪 Testing Fixed Performance Documentation Update System..."
echo ""

# Create test data with entity counts in operation names
cat > target/prism-test-data-fixed.csv << EOF
operation,throughput,latency
insert-prism-100,0.8 ms,
insert-prism-1000,12.5 ms,
insert-prism-10000,450.2 ms,
knn-prism-100,,0.025 ms
knn-prism-1000,,0.024 ms
knn-prism-10000,,0.095 ms
range-prism-100,,0.008 ms
range-prism-1000,,0.018 ms
range-prism-10000,,0.155 ms
memory-prism-100,45000,
memory-prism-1000,380000,
memory-prism-10000,3800000,
update-prism-100,0.008 ms,
update-prism-1000,0.015 ms,
update-prism-10000,0.120 ms,
remove-prism-100,0.0015 ms,
remove-prism-1000,0.0008 ms,
remove-prism-10000,0.005 ms,
EOF

echo "📊 Test data created:"
cat target/prism-test-data-fixed.csv
echo ""

# Backup current doc
cp doc/PERFORMANCE_METRICS_MASTER.md doc/PERFORMANCE_METRICS_MASTER.md.test-backup

echo "📝 Running Fixed Robust Performance Updater..."
mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.lucien.performance.RobustPerformanceUpdater" \
    -Dexec.args="target/prism-test-data-fixed.csv" \
    -Dexec.classpathScope=test -q

echo ""
echo "✅ Update complete!"
echo ""

# Show what changed
echo "📋 Changes made:"
if command -v colordiff &> /dev/null; then
    colordiff -u doc/PERFORMANCE_METRICS_MASTER.md.test-backup doc/PERFORMANCE_METRICS_MASTER.md | head -100 || true
else
    diff -u doc/PERFORMANCE_METRICS_MASTER.md.test-backup doc/PERFORMANCE_METRICS_MASTER.md | head -100 || true
fi

echo ""
echo "💡 To restore original: cp doc/PERFORMANCE_METRICS_MASTER.md.test-backup doc/PERFORMANCE_METRICS_MASTER.md"