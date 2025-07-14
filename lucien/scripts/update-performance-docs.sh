#!/bin/bash

# Script to update performance documentation
# Can be run locally or in CI

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "🚀 Starting performance documentation update..."

# Step 1: Run performance tests
echo "📊 Running performance benchmarks..."
cd "$PROJECT_DIR"
mvn clean test -Dtest=OctreeVsTetreeVsPrismBenchmark -DfailIfNoTests=false

# Step 2: Extract results
echo "📝 Extracting performance data..."
mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.lucien.performance.TestResultExtractor" \
    -Dexec.args="target/surefire-reports target/performance-results.csv" \
    -Dexec.classpathScope=test

# Step 3: Generate reports
echo "📈 Generating performance reports..."
mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.lucien.performance.PerformanceReportGenerator" \
    -Dexec.args="target target/reports" \
    -Dexec.classpathScope=test

# Step 4: Update documentation
echo "📚 Updating documentation..."
mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.lucien.performance.PerformanceDocumentationUpdater" \
    -Dexec.args="target/performance-results.csv" \
    -Dexec.classpathScope=test

# Step 5: Check for changes
echo "🔍 Checking for documentation changes..."
if git diff --quiet doc/PERFORMANCE_METRICS_MASTER.md; then
    echo "✅ No performance changes detected"
else
    echo "📊 Performance metrics have been updated!"
    git diff --stat doc/PERFORMANCE_METRICS_MASTER.md
    
    # In CI, this would commit automatically
    if [ "$CI" = "true" ]; then
        git config --local user.email "action@github.com"
        git config --local user.name "GitHub Action"
        git add doc/PERFORMANCE_METRICS_MASTER.md
        git add doc/performance-data/*.csv || true
        git commit -m "Auto-update performance metrics [skip ci]"
        echo "✅ Changes committed"
    else
        echo "💡 Run 'git add doc/PERFORMANCE_METRICS_MASTER.md && git commit' to save changes"
    fi
fi

echo "✨ Performance documentation update complete!"

# Optional: Open the dashboard
if [ "$1" = "--open" ]; then
    if [ -f "target/reports/performance-dashboard.html" ]; then
        echo "🌐 Opening performance dashboard..."
        open "target/reports/performance-dashboard.html" 2>/dev/null || \
        xdg-open "target/reports/performance-dashboard.html" 2>/dev/null || \
        echo "Dashboard generated at: target/reports/performance-dashboard.html"
    fi
fi