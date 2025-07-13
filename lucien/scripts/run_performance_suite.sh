#!/bin/bash

# Performance Test Suite Runner
# Executes all spatial index performance benchmarks in the correct order
# Captures output and validates results

set -e  # Exit on any error

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_DIR/performance_results"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="$OUTPUT_DIR/performance_suite_$TIMESTAMP.log"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1" | tee -a "$LOG_FILE"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1" | tee -a "$LOG_FILE"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1" | tee -a "$LOG_FILE"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" | tee -a "$LOG_FILE"
}

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Initialize log file
cat > "$LOG_FILE" << EOF
=================================================================
Luciferase Spatial Index Performance Test Suite
=================================================================
Date: $(date)
System: $(uname -a)
Java Version: $(java -version 2>&1 | head -n1)
Maven Version: $(mvn --version 2>&1 | head -n1)
Working Directory: $PROJECT_DIR
=================================================================

EOF

log_info "Starting Performance Test Suite"
log_info "Results will be saved to: $OUTPUT_DIR"
log_info "Full log: $LOG_FILE"

# Function to run a test and capture results
run_test() {
    local test_name="$1"
    local test_class="$2"
    local output_file="$OUTPUT_DIR/${test_name}_$TIMESTAMP.log"
    
    log_info "Running $test_name..."
    
    cd "$PROJECT_DIR"
    
    if mvn test -Dtest="$test_class" -DRUN_SPATIAL_INDEX_PERF_TESTS=true > "$output_file" 2>&1; then
        log_success "$test_name completed successfully"
        
        # Extract key metrics if available
        if grep -q "PERFORMANCE COMPARISON" "$output_file"; then
            log_info "Performance data found in $test_name"
            echo "  Output file: $output_file" >> "$LOG_FILE"
        fi
        
        return 0
    else
        log_error "$test_name failed"
        echo "  Error details in: $output_file" >> "$LOG_FILE"
        return 1
    fi
}

# Function to validate environment
validate_environment() {
    log_info "Validating test environment..."
    
    # Check Java version
    local java_version=$(java -version 2>&1 | head -n1 | cut -d'"' -f2)
    if [[ ! "$java_version" =~ ^[0-9]+\. ]]; then
        log_error "Cannot determine Java version"
        return 1
    fi
    log_success "Java version: $java_version"
    
    # Check available memory
    local mem_gb=$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1024 / 1024 / 1024 ))
    if [ "$mem_gb" -lt 4 ]; then
        log_warning "Low memory detected: ${mem_gb}GB (recommend 8GB+)"
    else
        log_success "Available memory: ${mem_gb}GB"
    fi
    
    # Check if we're in the right directory
    if [ ! -f "pom.xml" ]; then
        log_error "pom.xml not found - please run from project root"
        return 1
    fi
    
    return 0
}

# Function to clean build
clean_build() {
    log_info "Performing clean build..."
    
    cd "$PROJECT_DIR"
    if mvn clean install -DskipTests > "$OUTPUT_DIR/build_$TIMESTAMP.log" 2>&1; then
        log_success "Clean build completed"
        return 0
    else
        log_error "Build failed - check build_$TIMESTAMP.log"
        return 1
    fi
}

# Function to run all performance tests
run_performance_tests() {
    local failed_tests=0
    local total_tests=0
    
    log_info "Starting performance test execution..."
    
    # Test execution order (important for consistent system state)
    local tests=(
        "primary_comparison:OctreeVsTetreeBenchmark"
        "memory_spatial:SpatialIndexMemoryPerformanceTest"
        "memory_octree:OctreeMemoryPerformanceTest"
        "memory_tetree:TetreeMemoryPerformanceTest"
        "stress_test:SpatialIndexStressTest"
        "baseline_comparison:BaselinePerformanceBenchmark"
        "prism_stress:PrismStressTest"
        "prism_vs_octree:PrismVsOctreeComparisonTest"
        "prism_vs_tetree:PrismVsTetreeComparisonTest"
        "concurrent_forest:ForestConcurrencyTest"
        "lockfree_performance:LockFreePerformanceTest"
    )
    
    for test_entry in "${tests[@]}"; do
        IFS=':' read -r test_name test_class <<< "$test_entry"
        ((total_tests++))
        
        if ! run_test "$test_name" "$test_class"; then
            ((failed_tests++))
        fi
        
        # Brief pause between tests to allow system stabilization
        sleep 2
    done
    
    log_info "Performance test execution completed"
    log_info "Results: $((total_tests - failed_tests))/$total_tests tests passed"
    
    if [ $failed_tests -gt 0 ]; then
        log_warning "$failed_tests tests failed - check individual log files"
        return 1
    fi
    
    return 0
}

# Function to generate summary report
generate_summary() {
    local summary_file="$OUTPUT_DIR/summary_$TIMESTAMP.md"
    
    log_info "Generating summary report..."
    
    cat > "$summary_file" << EOF
# Performance Test Suite Summary

**Date**: $(date)  
**Environment**: $(uname -s) $(uname -m)  
**Java**: $(java -version 2>&1 | head -n1 | cut -d'"' -f2)  
**Duration**: Started at $(date -r $(stat -f%B "$LOG_FILE" 2>/dev/null || echo 0) 2>/dev/null || echo "Unknown")

## Test Results

EOF

    # Extract results from individual test logs
    for log_file in "$OUTPUT_DIR"/*_$TIMESTAMP.log; do
        if [ -f "$log_file" ] && [ "$(basename "$log_file")" != "$(basename "$LOG_FILE")" ]; then
            local test_name=$(basename "$log_file" | sed "s/_$TIMESTAMP.log//")
            echo "### $test_name" >> "$summary_file"
            
            if grep -q "BUILD SUCCESS" "$log_file"; then
                echo "✅ **Status**: PASSED" >> "$summary_file"
            else
                echo "❌ **Status**: FAILED" >> "$summary_file"
            fi
            
            # Extract performance highlights if available
            if grep -q "ms\|μs\|ops/sec" "$log_file"; then
                echo "" >> "$summary_file"
                echo "**Key Metrics Found**:" >> "$summary_file"
                grep -E "(ms|μs|ops/sec|MB|faster|slower)" "$log_file" | head -5 | sed 's/^/- /' >> "$summary_file"
            fi
            
            echo "" >> "$summary_file"
        fi
    done
    
    cat >> "$summary_file" << EOF

## Next Steps

1. Review individual test logs in \`performance_results/\`
2. Extract performance data using \`extract_performance_data.sh\`
3. Update performance documentation following \`PERFORMANCE_TESTING_PROCESS.md\`

## Files Generated

EOF

    # List all generated files
    for file in "$OUTPUT_DIR"/*_$TIMESTAMP.*; do
        if [ -f "$file" ]; then
            echo "- $(basename "$file")" >> "$summary_file"
        fi
    done
    
    log_success "Summary report generated: $summary_file"
}

# Main execution
main() {
    log_info "Performance Test Suite Starting"
    
    # Set environment variables
    export RUN_SPATIAL_INDEX_PERF_TESTS=true
    export MAVEN_OPTS="-Xmx8g -XX:MaxMetaspaceSize=512m"
    
    # Execute phases
    if ! validate_environment; then
        log_error "Environment validation failed"
        exit 1
    fi
    
    if ! clean_build; then
        log_error "Build failed"
        exit 1
    fi
    
    if ! run_performance_tests; then
        log_warning "Some performance tests failed"
        # Continue to generate summary even if some tests failed
    fi
    
    generate_summary
    
    log_success "Performance Test Suite Completed"
    log_info "Results directory: $OUTPUT_DIR"
    log_info "Summary: $OUTPUT_DIR/summary_$TIMESTAMP.md"
}

# Execute main function
main "$@"