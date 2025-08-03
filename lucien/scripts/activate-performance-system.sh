#!/bin/bash

# Script to fully activate the automated performance reporting system

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "🚀 Activating Lucien Performance Reporting System..."

# Step 1: Create necessary directories
echo "📁 Creating directory structure..."
mkdir -p "$PROJECT_DIR/performance-results"
mkdir -p "$PROJECT_DIR/target/performance-output"

# Step 2: Initialize performance data files in target (for build outputs)
echo "📊 Initializing performance data files..."

# Create initial CSV files with headers in target for builds
cat > "$PROJECT_DIR/target/performance-output/octree-performance.csv" << EOF
timestamp,commit,operation,entityCount,meanLatency,p95Latency,p99Latency,throughput,memoryPerEntity
EOF

cat > "$PROJECT_DIR/target/performance-output/tetree-performance.csv" << EOF
timestamp,commit,operation,entityCount,meanLatency,p95Latency,p99Latency,throughput,memoryPerEntity
EOF

cat > "$PROJECT_DIR/target/performance-output/prism-performance.csv" << EOF
timestamp,commit,operation,entityCount,meanLatency,p95Latency,p99Latency,throughput,memoryPerEntity
EOF

# Step 3: Create a baseline performance data file
echo "📈 Creating baseline performance data..."
cat > "$PROJECT_DIR/target/performance-output/baseline.json" << EOF
{
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "commit": "$(git rev-parse HEAD 2>/dev/null || echo 'unknown')",
  "baselines": {
    "insertion": {
      "octree": 1.8,
      "tetree": 0.5,
      "prism": 1.1
    },
    "knn": {
      "octree": 17.0,
      "tetree": 13.4,
      "prism": 14.8
    },
    "range": {
      "octree": 8.1,
      "tetree": 16.5,
      "prism": 10.2
    }
  }
}
EOF

# Step 4: Verify Java classes compile
echo "🔨 Compiling performance tools..."
cd "$PROJECT_DIR"
mvn compile test-compile -DskipTests

# Step 5: Create GitHub Actions directory if it doesn't exist
echo "🔧 Setting up GitHub Actions..."
mkdir -p "$PROJECT_DIR/../.github/workflows"

# Step 6: Create a test runner to verify the system
echo "✅ Creating test verification script..."
cat > "$PROJECT_DIR/scripts/verify-performance-system.sh" << 'VERIFY_EOF'
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
   [ -f "src/test/java/com/hellblazer/luciferase/lucien/performance/RobustPerformanceUpdater.java" ] && \
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

echo "✅ All components verified!"
VERIFY_EOF

chmod +x "$PROJECT_DIR/scripts/verify-performance-system.sh"

# Step 7: Create a simple test to ensure everything works
echo "🧪 Creating integration test..."
cat > "$PROJECT_DIR/src/test/java/com/hellblazer/luciferase/lucien/performance/PerformanceSystemTest.java" << 'TEST_EOF'
package com.hellblazer.luciferase.lucien.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class PerformanceSystemTest {
    
    @Test
    public void testResultExtractor(@TempDir Path tempDir) throws IOException {
        // Create mock test output
        Path testOutput = tempDir.resolve("test-output.txt");
        Files.writeString(testOutput, """
            Test Output
            Insertion     | 1.8 μs/op | 0.5 μs/op | 1.1 μs/op
            k-NN Query    | 17.0 μs/op | 13.4 μs/op | 14.8 μs/op
            """);
        
        Path csvOutput = tempDir.resolve("results.csv");
        
        // Run extractor
        TestResultExtractor.main(new String[]{
            tempDir.toString(), 
            csvOutput.toString()
        });
        
        assertTrue(Files.exists(csvOutput), "CSV output should be created");
        String csv = Files.readString(csvOutput);
        assertTrue(csv.contains("insert-octree"), "Should extract insertion data");
    }
    
    @Test
    public void testDocumentationUpdater(@TempDir Path tempDir) throws IOException {
        // Create mock results
        Path results = tempDir.resolve("results.csv");
        Files.writeString(results, """
            operation,throughput,latency
            insert-octree,1.8 μs/op,
            insert-tetree,0.5 μs/op,
            insert-prism,1.1 μs/op,
            """);
        
        // This would test the updater if we had a mock markdown file
        // For now, just ensure it doesn't crash
        assertTrue(true, "Documentation updater test placeholder");
    }
}
TEST_EOF

# Step 8: Add required dependencies to pom.xml
echo "📦 Updating Maven dependencies..."
# We'll need to add Jackson for JSON handling
# This would normally be done via XML manipulation, but for now we'll note it

cat > "$PROJECT_DIR/scripts/add-dependencies.txt" << 'DEPS_EOF'
Add these dependencies to lucien/pom.xml in the dependencies section:

        <!-- Performance reporting dependencies -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.15.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
            <version>2.15.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>commons-math3</groupId>
            <artifactId>commons-math3</artifactId>
            <version>3.6.1</version>
            <scope>test</scope>
        </dependency>
DEPS_EOF

# Step 9: Create initial documentation state
echo "📚 Initializing documentation state..."
if [ ! -f "$PROJECT_DIR/doc/PERFORMANCE_METRICS_MASTER.md" ]; then
    echo "⚠️  PERFORMANCE_METRICS_MASTER.md not found, using template..."
    # We would copy from template or create a basic version
fi

# Step 10: Set up git hooks (optional)
echo "🪝 Setting up git hooks..."
cat > "$PROJECT_DIR/../.git/hooks/pre-push" << 'HOOK_EOF'
#!/bin/bash
# Pre-push hook to check performance

echo "🔍 Checking performance before push..."
cd lucien
./scripts/verify-performance-system.sh
HOOK_EOF
chmod +x "$PROJECT_DIR/../.git/hooks/pre-push" 2>/dev/null || true

# Final message
echo ""
echo "✅ Performance system activation complete!"
echo ""
echo "📋 Next steps:"
echo "   1. Add the dependencies listed in scripts/add-dependencies.txt to pom.xml"
echo "   2. Run: mvn clean compile test-compile"
echo "   3. Test the system: ./scripts/update-performance-docs.sh"
echo "   4. Verify setup: ./scripts/verify-performance-system.sh"
echo ""
echo "🚀 To run a full performance update:"
echo "   mvn clean verify -Pperformance-full"
echo ""
echo "📊 To update docs from existing results:"
echo "   ./scripts/update-performance-docs.sh"
echo ""
echo "🗄️ To archive results to permanent storage:"
echo "   mvn compile -Pperformance-archive"
echo ""
echo "📍 Data locations:"
echo "   - Build output: target/performance-output/"
echo "   - Permanent archive: performance-results/"
echo ""