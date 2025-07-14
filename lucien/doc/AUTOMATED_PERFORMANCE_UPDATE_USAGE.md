# Automated Performance Update Usage Guide

## Overview

The automated performance update system is now fully integrated and ready to use. This guide explains how to update the performance documentation with new benchmark results.

## Quick Start

To update performance metrics after running benchmarks:

```bash
# 1. Run the performance benchmarks
mvn clean test -Dtest=OctreeVsTetreeVsPrismBenchmark -DRun_SPATIAL_INDEX_PERF_TESTS=true

# 2. Extract and update documentation 
mvn clean verify -Pperformance-extract,performance-docs
```

## Manual Testing

To test the update system with sample data:

```bash
./scripts/test-performance-update-fixed.sh
```

## How It Works

### 1. Data Format

The system expects CSV files with the following format:

```csv
operation,throughput,latency
insert-prism-100,0.8 ms,
insert-prism-1000,12.5 ms,
knn-prism-100,,0.025 ms
range-prism-1000,,0.018 ms
memory-prism-100,45000,
```

- Operation names must include entity count: `{operation}-prism-{entityCount}`
- Throughput column for insert/update/remove operations
- Latency column for knn/range operations
- Memory values in bytes (will be converted to MB)

### 2. Update Process

The `RobustPerformanceUpdater` class:
1. Parses the CSV file
2. Matches operations to specific table sections
3. Updates only the Prism columns that contain `*pending*`
4. Preserves all other data and formatting

### 3. Table Mapping

| Operation Type | Table Section | Value Used |
|----------------|---------------|------------|
| insert-prism-* | Insertion Performance | throughput |
| knn-prism-* | k-NN Search Performance | latency |
| range-prism-* | Range Query Performance | latency |
| memory-prism-* | Memory Usage | throughput (converted to MB) |
| update-prism-* | Update Performance | throughput |
| remove-prism-* | Removal Performance | throughput |

## CI/CD Integration

The system is integrated with GitHub Actions:

1. Performance tests run on push/PR
2. Results are extracted to CSV
3. Documentation is automatically updated
4. Changes are committed back to the repository

## Troubleshooting

### Issue: Wrong values in wrong columns

If you see incorrect values being placed in the wrong columns, check:
1. The CSV file format matches expected structure
2. Operation names include entity counts
3. Values are in the correct column (throughput vs latency)

### Issue: Values not updating

If values aren't updating:
1. Ensure the target cell contains `*pending*`
2. Check that operation names match the pattern
3. Verify the CSV file is being read correctly

### Manual Restoration

If something goes wrong:
```bash
# Restore from backup
cp doc/PERFORMANCE_METRICS_MASTER.md.test-backup doc/PERFORMANCE_METRICS_MASTER.md
```

## Adding New Benchmarks

To add support for new spatial indices:

1. Update the benchmark to output results in the expected format
2. Add new operation types to `RobustPerformanceUpdater.parseResults()`
3. Add new table sections to `RobustPerformanceUpdater.applyUpdates()`
4. Update the Maven profiles if needed

## Implementation Details

Key classes:
- `RobustPerformanceUpdater` - Main update logic
- `TestResultExtractor` - Extracts data from test results
- `PerformanceReportGenerator` - Generates performance reports

Maven profiles:
- `performance` - Runs benchmarks
- `performance-extract` - Extracts results
- `performance-docs` - Updates documentation
- `performance-full` - Complete workflow

## Next Steps

1. Run the actual OctreeVsTetreeVsPrismBenchmark
2. Use the automated system to update documentation
3. Monitor CI/CD runs for automatic updates
4. Extend the system as new benchmarks are added