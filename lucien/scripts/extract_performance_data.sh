#!/bin/bash

# Performance Data Extraction Script
# Parses benchmark outputs and generates standardized performance metrics
# Outputs CSV and markdown tables for documentation updates

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="$PROJECT_DIR/performance_results"
OUTPUT_FILE="$RESULTS_DIR/extracted_metrics_$(date +%Y%m%d).csv"
MARKDOWN_FILE="$RESULTS_DIR/performance_tables_$(date +%Y%m%d).md"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Find the most recent test results
find_latest_results() {
    if [ ! -d "$RESULTS_DIR" ]; then
        log_error "Results directory not found: $RESULTS_DIR"
        log_info "Please run ./scripts/run_performance_suite.sh first"
        exit 1
    fi
    
    # Find the most recent timestamp from log files
    local latest_timestamp=$(ls -1 "$RESULTS_DIR"/*_*.log 2>/dev/null | \
                           sed 's/.*_\([0-9]\{8\}_[0-9]\{6\}\)\.log/\1/' | \
                           sort -u | tail -1)
    
    if [ -z "$latest_timestamp" ]; then
        log_error "No performance test results found in $RESULTS_DIR"
        log_info "Please run ./scripts/run_performance_suite.sh first"
        exit 1
    fi
    
    echo "$latest_timestamp"
}

# Extract insertion performance data
extract_insertion_data() {
    local timestamp="$1"
    local primary_log="$RESULTS_DIR/primary_comparison_$timestamp.log"
    
    if [ ! -f "$primary_log" ]; then
        log_warning "Primary comparison log not found: $primary_log"
        return 1
    fi
    
    log_info "Extracting insertion performance data..."
    
    # Parse insertion timing data from OctreeVsTetreeBenchmark output
    # Expected format: "Octree: X.XXX ms", "Tetree: X.XXX ms"
    local octree_times=$(grep -E "Octree:.*[0-9]+\.[0-9]+.*ms" "$primary_log" | \
                        sed -E 's/.*Octree:.*([0-9]+\.[0-9]+).*ms.*/\1/')
    local tetree_times=$(grep -E "Tetree:.*[0-9]+\.[0-9]+.*ms" "$primary_log" | \
                        sed -E 's/.*Tetree:.*([0-9]+\.[0-9]+).*ms.*/\1/')
    
    # Extract entity counts from test output
    local entity_counts=$(grep -E "Testing with [0-9]+ entities" "$primary_log" | \
                         sed -E 's/.*Testing with ([0-9]+) entities.*/\1/')
    
    # Create CSV entries for insertion data
    if [ ! -z "$octree_times" ] && [ ! -z "$tetree_times" ] && [ ! -z "$entity_counts" ]; then
        local octree_array=($octree_times)
        local tetree_array=($tetree_times)
        local count_array=($entity_counts)
        
        for i in "${!count_array[@]}"; do
            if [ -n "${octree_array[$i]}" ] && [ -n "${tetree_array[$i]}" ]; then
                echo "insertion,${count_array[$i]},${octree_array[$i]},Octree,ms" >> "$OUTPUT_FILE"
                echo "insertion,${count_array[$i]},${tetree_array[$i]},Tetree,ms" >> "$OUTPUT_FILE"
            fi
        done
    fi
}

# Extract k-NN performance data
extract_knn_data() {
    local timestamp="$1"
    local primary_log="$RESULTS_DIR/primary_comparison_$timestamp.log"
    
    if [ ! -f "$primary_log" ]; then
        return 1
    fi
    
    log_info "Extracting k-NN performance data..."
    
    # Parse k-NN timing data
    # Expected format: "k-NN Search: Octree: X.XXX μs", "Tetree: X.XXX μs"
    local octree_knn=$(grep -E "k-NN.*Octree:.*[0-9]+\.[0-9]+.*μs" "$primary_log" | \
                      sed -E 's/.*Octree:.*([0-9]+\.[0-9]+).*μs.*/\1/')
    local tetree_knn=$(grep -E "k-NN.*Tetree:.*[0-9]+\.[0-9]+.*μs" "$primary_log" | \
                      sed -E 's/.*Tetree:.*([0-9]+\.[0-9]+).*μs.*/\1/')
    
    if [ ! -z "$octree_knn" ] && [ ! -z "$tetree_knn" ]; then
        local octree_array=($octree_knn)
        local tetree_array=($tetree_knn)
        
        # Assume same entity counts as insertion tests
        local entity_counts=$(grep -E "Testing with [0-9]+ entities" "$primary_log" | \
                             sed -E 's/.*Testing with ([0-9]+) entities.*/\1/')
        local count_array=($entity_counts)
        
        for i in "${!count_array[@]}"; do
            if [ -n "${octree_array[$i]}" ] && [ -n "${tetree_array[$i]}" ]; then
                echo "knn,${count_array[$i]},${octree_array[$i]},Octree,μs" >> "$OUTPUT_FILE"
                echo "knn,${count_array[$i]},${tetree_array[$i]},Tetree,μs" >> "$OUTPUT_FILE"
            fi
        done
    fi
}

# Extract memory usage data
extract_memory_data() {
    local timestamp="$1"
    
    log_info "Extracting memory usage data..."
    
    # Check memory-specific test logs
    for test_type in "memory_spatial" "memory_octree" "memory_tetree"; do
        local memory_log="$RESULTS_DIR/${test_type}_$timestamp.log"
        
        if [ -f "$memory_log" ]; then
            # Parse memory usage data
            # Expected format: "Memory usage: X.XX MB"
            local memory_values=$(grep -E "Memory.*[0-9]+\.[0-9]+.*MB" "$memory_log" | \
                                 sed -E 's/.*([0-9]+\.[0-9]+).*MB.*/\1/')
            
            if [ ! -z "$memory_values" ]; then
                local impl_type
                case "$test_type" in
                    *octree*) impl_type="Octree";;
                    *tetree*) impl_type="Tetree";;
                    *) impl_type="Unknown";;
                esac
                
                # For now, assume standard entity counts - would need parsing improvement
                echo "memory,1000,$memory_values,$impl_type,MB" >> "$OUTPUT_FILE"
            fi
        fi
    done
}

# Extract Prism performance data
extract_prism_data() {
    local timestamp="$1"
    
    log_info "Extracting Prism performance data..."
    
    # Check Prism-specific test logs
    for test_type in "prism_stress" "prism_vs_octree" "prism_vs_tetree"; do
        local prism_log="$RESULTS_DIR/${test_type}_$timestamp.log"
        
        if [ -f "$prism_log" ]; then
            # Parse Prism performance data
            local prism_times=$(grep -E "Prism:.*[0-9]+\.[0-9]+.*ms" "$prism_log" | \
                               sed -E 's/.*Prism:.*([0-9]+\.[0-9]+).*ms.*/\1/')
            
            if [ ! -z "$prism_times" ]; then
                # Extract entity counts if available
                local entity_counts=$(grep -E "entities:|count.*[0-9]+" "$prism_log" | \
                                     head -1 | sed -E 's/.*([0-9]+).*/\1/' || echo "1000")
                
                echo "insertion,$entity_counts,$prism_times,Prism,ms" >> "$OUTPUT_FILE"
            fi
        fi
    done
}

# Generate markdown tables from CSV data
generate_markdown_tables() {
    log_info "Generating markdown tables..."
    
    if [ ! -f "$OUTPUT_FILE" ]; then
        log_warning "No CSV data found to generate tables"
        return 1
    fi
    
    cat > "$MARKDOWN_FILE" << EOF
# Performance Metrics Tables

**Generated**: $(date)  
**Source**: Performance test results from $(find_latest_results)

## Insertion Performance

| Entity Count | Octree Time | Tetree Time | Prism Time | Tetree vs Octree | Prism vs Octree |
|-------------|-------------|-------------|------------|------------------|-----------------|
EOF

    # Generate insertion table
    local entity_counts=$(awk -F',' '$1=="insertion" {print $2}' "$OUTPUT_FILE" | sort -n | uniq)
    
    for count in $entity_counts; do
        local octree_time=$(awk -F',' -v cnt="$count" '$1=="insertion" && $2==cnt && $4=="Octree" {print $3}' "$OUTPUT_FILE")
        local tetree_time=$(awk -F',' -v cnt="$count" '$1=="insertion" && $2==cnt && $4=="Tetree" {print $3}' "$OUTPUT_FILE")
        local prism_time=$(awk -F',' -v cnt="$count" '$1=="insertion" && $2==cnt && $4=="Prism" {print $3}' "$OUTPUT_FILE")
        
        # Calculate ratios if data available
        local tetree_ratio=""
        local prism_ratio=""
        
        if [ ! -z "$octree_time" ] && [ ! -z "$tetree_time" ]; then
            tetree_ratio=$(echo "scale=2; $tetree_time / $octree_time" | bc -l 2>/dev/null || echo "N/A")
            if [ "$tetree_ratio" != "N/A" ]; then
                tetree_ratio="${tetree_ratio}x"
            fi
        fi
        
        if [ ! -z "$octree_time" ] && [ ! -z "$prism_time" ]; then
            prism_ratio=$(echo "scale=2; $prism_time / $octree_time" | bc -l 2>/dev/null || echo "N/A")
            if [ "$prism_ratio" != "N/A" ]; then
                prism_ratio="${prism_ratio}x"
            fi
        fi
        
        echo "| $count | ${octree_time:-N/A} ms | ${tetree_time:-N/A} ms | ${prism_time:-N/A} ms | ${tetree_ratio:-N/A} | ${prism_ratio:-N/A} |" >> "$MARKDOWN_FILE"
    done
    
    cat >> "$MARKDOWN_FILE" << EOF

## k-Nearest Neighbor Performance

| Entity Count | Octree Time | Tetree Time | Prism Time | Tetree vs Octree | Prism vs Octree |
|-------------|-------------|-------------|------------|------------------|-----------------|
EOF

    # Generate k-NN table
    local knn_counts=$(awk -F',' '$1=="knn" {print $2}' "$OUTPUT_FILE" | sort -n | uniq)
    
    for count in $knn_counts; do
        local octree_knn=$(awk -F',' -v cnt="$count" '$1=="knn" && $2==cnt && $4=="Octree" {print $3}' "$OUTPUT_FILE")
        local tetree_knn=$(awk -F',' -v cnt="$count" '$1=="knn" && $2==cnt && $4=="Tetree" {print $3}' "$OUTPUT_FILE")
        local prism_knn=$(awk -F',' -v cnt="$count" '$1=="knn" && $2==cnt && $4=="Prism" {print $3}' "$OUTPUT_FILE")
        
        # Calculate ratios
        local tetree_ratio=""
        local prism_ratio=""
        
        if [ ! -z "$octree_knn" ] && [ ! -z "$tetree_knn" ]; then
            tetree_ratio=$(echo "scale=2; $tetree_knn / $octree_knn" | bc -l 2>/dev/null || echo "N/A")
            if [ "$tetree_ratio" != "N/A" ]; then
                tetree_ratio="${tetree_ratio}x"
            fi
        fi
        
        if [ ! -z "$octree_knn" ] && [ ! -z "$prism_knn" ]; then
            prism_ratio=$(echo "scale=2; $prism_knn / $octree_knn" | bc -l 2>/dev/null || echo "N/A")
            if [ "$prism_ratio" != "N/A" ]; then
                prism_ratio="${prism_ratio}x"
            fi
        fi
        
        echo "| $count | ${octree_knn:-N/A} μs | ${tetree_knn:-N/A} μs | ${prism_knn:-N/A} μs | ${tetree_ratio:-N/A} | ${prism_ratio:-N/A} |" >> "$MARKDOWN_FILE"
    done
    
    cat >> "$MARKDOWN_FILE" << EOF

## Memory Usage

| Entity Count | Octree Memory | Tetree Memory | Prism Memory | Tetree vs Octree | Prism vs Octree |
|-------------|---------------|---------------|--------------|------------------|-----------------|
EOF

    # Generate memory table
    local memory_counts=$(awk -F',' '$1=="memory" {print $2}' "$OUTPUT_FILE" | sort -n | uniq)
    
    for count in $memory_counts; do
        local octree_mem=$(awk -F',' -v cnt="$count" '$1=="memory" && $2==cnt && $4=="Octree" {print $3}' "$OUTPUT_FILE")
        local tetree_mem=$(awk -F',' -v cnt="$count" '$1=="memory" && $2==cnt && $4=="Tetree" {print $3}' "$OUTPUT_FILE")
        local prism_mem=$(awk -F',' -v cnt="$count" '$1=="memory" && $2==cnt && $4=="Prism" {print $3}' "$OUTPUT_FILE")
        
        echo "| $count | ${octree_mem:-N/A} MB | ${tetree_mem:-N/A} MB | ${prism_mem:-N/A} MB | ${tetree_ratio:-N/A} | ${prism_ratio:-N/A} |" >> "$MARKDOWN_FILE"
    done
    
    cat >> "$MARKDOWN_FILE" << EOF

## Data Summary

**CSV File**: $(basename "$OUTPUT_FILE")  
**Total Data Points**: $(wc -l < "$OUTPUT_FILE")  
**Operations Measured**: $(awk -F',' '{print $1}' "$OUTPUT_FILE" | sort | uniq | wc -l)  
**Implementations**: $(awk -F',' '{print $4}' "$OUTPUT_FILE" | sort | uniq | tr '\n' ', ' | sed 's/,$//')

## Usage Instructions

1. Copy the tables above into PERFORMANCE_METRICS_MASTER.md
2. Update the "Last Updated" timestamp
3. Add any historical context for significant changes
4. Cross-reference with other performance documentation

EOF
}

# Main execution
main() {
    log_info "Performance Data Extraction Starting"
    
    # Find latest results
    local timestamp=$(find_latest_results)
    log_info "Using test results from: $timestamp"
    
    # Initialize CSV file
    echo "operation,entity_count,value,implementation,unit" > "$OUTPUT_FILE"
    
    # Extract data from different sources
    extract_insertion_data "$timestamp"
    extract_knn_data "$timestamp"
    extract_memory_data "$timestamp"
    extract_prism_data "$timestamp"
    
    # Generate markdown tables
    generate_markdown_tables
    
    # Summary
    local data_points=$(wc -l < "$OUTPUT_FILE")
    log_success "Extraction completed"
    log_info "Data points extracted: $((data_points - 1))"  # Subtract header row
    log_info "CSV output: $OUTPUT_FILE"
    log_info "Markdown tables: $MARKDOWN_FILE"
    
    if [ $((data_points - 1)) -eq 0 ]; then
        log_warning "No performance data was extracted"
        log_info "This may indicate:"
        log_info "  - Performance tests didn't run successfully"
        log_info "  - Output format has changed and parsing needs updates"
        log_info "  - Test logs are in a different location"
        return 1
    fi
    
    return 0
}

# Execute main function
main "$@"