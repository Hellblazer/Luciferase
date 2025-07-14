#!/bin/bash

set -e

echo "🧪 Verifying performance system components..."

# Check if Maven profiles exist
echo -n "Checking Maven profiles... "
if grep -q "performance-full" pom.xml; then
    echo "✓"
else
    echo "✗ Missing performance-full profile"
    exit 1
fi

# Check if performance classes exist
echo -n "Checking performance tools... "
if [ -f "src/test/java/com/hellblazer/luciferase/lucien/performance/TestResultExtractor.java" ] && \
   [ -f "src/test/java/com/hellblazer/luciferase/lucien/performance/PerformanceDocumentationUpdater.java" ] && \
   [ -f "src/test/java/com/hellblazer/luciferase/lucien/performance/PerformanceReportGenerator.java" ]; then
    echo "✓"
else
    echo "✗ Missing performance tool classes"
    exit 1
fi

# Check if documentation exists
echo -n "Checking documentation files... "
if [ -f "doc/PERFORMANCE_METRICS_MASTER.md" ]; then
    echo "✓"
else
    echo "✗ Missing PERFORMANCE_METRICS_MASTER.md"
    exit 1
fi

# Check if performance data directory exists
echo -n "Checking performance data directory... "
if [ -d "doc/performance-data" ]; then
    echo "✓"
else
    echo "✗ Missing doc/performance-data directory"
    exit 1
fi

# Check if scripts are executable
echo -n "Checking script permissions... "
if [ -x "scripts/update-performance-docs.sh" ]; then
    echo "✓"
else
    echo "✗ Scripts not executable"
    exit 1
fi

# Check if dependencies are available
echo -n "Checking Maven dependencies... "
if mvn dependency:tree | grep -q "jackson-databind" && mvn dependency:tree | grep -q "commons-math3"; then
    echo "✓"
else
    echo "✗ Missing required dependencies"
    exit 1
fi

echo "✅ All components verified!"
echo ""
echo "📋 System Status:"
echo "   - Performance tools: Ready"
echo "   - Documentation: Ready"
echo "   - Maven profiles: Configured"
echo "   - Dependencies: Installed"
echo ""
echo "🚀 Ready to run performance updates!"