# Performance Testing & Documentation Maintenance Process

**Purpose**: Standardized process for collecting, validating, and updating spatial index performance data across the Luciferase codebase.

**Owner**: Development Team  
**Review Frequency**: Monthly or after significant performance-impacting changes  
**Last Updated**: 2025-12-08  
**Status**: Current

## Overview

This document defines the standardized process for maintaining accurate, consistent performance data across all Luciferase documentation. It eliminates ad-hoc performance data collection and prevents documentation inconsistencies.

## When to Execute This Process

### Mandatory Triggers

- **Before major releases** - Ensure release notes have current data
- **After performance optimizations** - Document improvements/regressions
- **After spatial index changes** - Validate impact on all indices
- **Monthly maintenance** - Keep data current with latest builds

### Optional Triggers  

- **Before performance presentations** - Ensure accuracy
- **When performance questions arise** - Provide authoritative answers
- **After JVM/infrastructure changes** - Validate consistent performance

## Process Workflow

### Phase 1: Pre-Test Validation

**Time Required**: 2 minutes

1. **Environment Check**

   ```bash

   # Verify Java version and system info
   mvn --version  # Shows both Java and Maven versions
   
   # Check available memory
   java -XX:+PrintFlagsFinal -version | grep MaxHeapSize  

```

2. **Clean Build** (Automatic)

   ```bash

   # The performance profile handles clean build automatically
   # No manual steps required

```

### Phase 2: Core Performance Data Collection

**Time Required**: 15-30 minutes (configured with 30-minute timeout for comprehensive tests)

**Option A: Quick Performance Testing** (Most Common)

```bash

# Run core benchmark for quick comparison data

mvn test -Dtest=OctreeVsTetreeBenchmark -DRUN_SPATIAL_INDEX_PERF_TESTS=true

# For full performance suite (may require 30+ minutes):

mvn clean test -Pperformance

# Results saved to: performance-results/
# Surefire reports: target/surefire-reports/

```

**Option B: Full Workflow** (Complete Process)

```bash

# Run tests + extract data + update documentation in one command

mvn clean verify -Pperformance-full

# This automatically:
# 1. Runs all performance benchmarks  
# 2. Extracts performance data to CSV/markdown
# 3. Updates documentation files
# 4. Creates summary report

```

**Option C: Individual Components** (For Debugging)

```bash

# Just run performance tests

mvn clean test -Pperformance

# Just extract data from existing test results

mvn compile -Pperformance-extract  

# Just update documentation from existing data

mvn compile -Pperformance-docs

```

### Supported Performance Tests (Automatically Included)

The Maven profiles automatically run all performance benchmarks:

- **OctreeVsTetreeBenchmark** - Primary comparison
- **SpatialIndexStressTest** - Large scale testing  
- **BaselinePerformanceBenchmark** - Optimization analysis
- **PrismStressTest** - Prism performance validation
- **PrismVsOctreeComparisonTest** - Cross-index comparison
- **PrismVsTetreeComparisonTest** - Cross-index comparison  
- **LockFreePerformanceTest** - Concurrent performance
- **All *PerformanceTest.java files** - Comprehensive coverage

### Phase 3: Data Extraction & Validation

**Time Required**: 10 minutes

1. **Extract Key Metrics**
   
   From benchmark outputs, extract these standardized metrics:
   
   **Insertion Performance**:

```

   Entity Count | Octree Time | Tetree Time | Prism Time | Units
   100         | X.XXX ms    | X.XXX ms    | X.XXX ms   | milliseconds
   1,000       | X.XXX ms    | X.XXX ms    | X.XXX ms   | milliseconds  
   10,000      | X.XXX ms    | X.XXX ms    | X.XXX ms   | milliseconds

```
   
   **k-NN Search Performance**:

```

   Entity Count | k=10 Octree | k=10 Tetree | k=10 Prism | Units
   100         | X.XXX μs    | X.XXX μs    | X.XXX μs   | microseconds
   1,000       | X.XXX μs    | X.XXX μs    | X.XXX μs   | microseconds
   10,000      | X.XXX μs    | X.XXX μs    | X.XXX μs   | microseconds

```
   
   **Memory Usage**:

```

   Entity Count | Octree MB | Tetree MB | Prism MB | Tetree/Octree | Prism/Octree
   100         | X.XX MB   | X.XX MB   | X.XX MB  | XX%           | XX%
   1,000       | X.XX MB   | X.XX MB   | X.XX MB  | XX%           | XX%  
   10,000      | X.XX MB   | X.XX MB   | X.XX MB  | XX%           | XX%

```

2. **Data Validation**
   
   **Sanity Checks**:
   - ✅ All timing values > 0
   - ✅ Memory usage increases with entity count
   - ✅ No unexpected 10x+ performance changes from previous run
   - ✅ Performance ratios are reasonable (< 10x difference between indices)
   
   **Historical Comparison**:
   - Compare with previous month's data
   - Flag significant changes (>25% difference) for investigation
   - Document known performance changes (optimizations, regressions)

### Phase 4: Document Updates

**Time Required**: 15 minutes

Update documents in this specific order to maintain consistency:

1. **PERFORMANCE_METRICS_MASTER.md** (Primary Source of Truth)

   ```bash

   # Update header with current date and environment

   **Last Updated**: $(date '+%B %d, %Y')
   **Environment**: $(uname -s) $(uname -m), Java $(java -version 2>&1 | head -n1 | cut -d'"' -f2)
   **Benchmark Version**: OctreeVsTetreeBenchmark v$(date +%Y.%m)
   
   # Replace all performance tables with extracted data
   # Update "Historical Context" section if significant changes occurred

```

2. **SPATIAL_INDEX_PERFORMANCE_COMPARISON.md**

   ```bash

   # Update cross-reference tables
   # Ensure all numbers match PERFORMANCE_METRICS_MASTER.md exactly
   # Update "Key Insights" section with current performance characteristics

```

3. **PERFORMANCE_INDEX.md**

   ```bash

   # Update "Quick Reference" section
   # Verify all document links are current
   # Update "Last Validation" timestamp

```

4. **Update Cross-References**

   ```bash

   # Find and update all files referencing performance data
   grep -r "faster\|slower\|ms\|μs\|MB" lucien/doc/ | grep -v PERFORMANCE_METRICS_MASTER.md
   
   # Replace hardcoded numbers with references to master document:
   # "See PERFORMANCE_METRICS_MASTER.md for current performance data"

```

### Phase 5: Quality Assurance

**Time Required**: 5 minutes

1. **Cross-Reference Validation**

   ```bash

   # Ensure no conflicting performance numbers exist
   ./scripts/validate_performance_consistency.sh  # Create this script
   
   # Manual check: Search for hardcoded performance numbers
   grep -r "\d+\.\d+.*ms\|\d+x.*faster" lucien/doc/ --exclude="*PERFORMANCE_METRICS_MASTER.md"

```

2. **Documentation Review**

   ```bash

   # Verify document timestamps are current
   # Check that all performance claims reference the master document
   # Ensure README.md and high-level docs have current performance summaries

```

## Automation Opportunities

### Short-term Automation (Low-hanging fruit)

1. **Performance Test Runner Script**

   ```bash

   #!/bin/bash
   # scripts/run_performance_suite.sh
   # Executes all performance tests in correct order
   # Captures output to timestamped files
   # Validates basic sanity checks

```

2. **Data Extraction Script**

   ```bash

   #!/bin/bash  
   # scripts/extract_performance_data.sh
   # Parses benchmark outputs
   # Generates CSV/JSON with standardized metrics
   # Compares with historical data

```

### Medium-term Automation

1. **Maven Plugin Integration**

   ```xml

   <!-- Add to pom.xml -->
   <plugin>
     <groupId>com.hellblazer.luciferase</groupId>
     <artifactId>performance-tracking-maven-plugin</artifactId>
     <version>1.0</version>
     <executions>
       <execution>
         <phase>verify</phase>
         <goals>
           <goal>benchmark</goal>
           <goal>update-docs</goal>
         </goals>
       </execution>
     </executions>
   </plugin>

```

2. **GitHub Actions Integration**

   ```yaml

   # .github/workflows/performance-tracking.yml
   name: Monthly Performance Update
   on:
     schedule:

       - cron: '0 0 1 * *'  # First day of each month

     workflow_dispatch:  # Manual trigger
   
   jobs:
     performance-update:
       steps:

         - name: Run Performance Suite
         - name: Update Documentation  
         - name: Create Pull Request

```

### Long-term Automation

1. **Performance Regression Detection** - Automatic alerting for performance changes
2. **Historical Trend Tracking** - Database of performance metrics over time  
3. **Release Integration** - Automatic performance validation before releases

## File Inventory & Responsibilities

### Primary Files (Must Update Every Time)

- ✅ `PERFORMANCE_METRICS_MASTER.md` - Single source of truth for all metrics
- ✅ `SPATIAL_INDEX_PERFORMANCE_COMPARISON.md` - Cross-index comparison tables
- ✅ `PERFORMANCE_INDEX.md` - Quick reference and navigation

### Secondary Files (Update As Needed)

- `README.md` - High-level performance summary (if present)
- Release notes - Performance highlights for releases
- Architecture docs - Performance considerations for design decisions

### Files to Audit (Check for Hardcoded Numbers)

- All `doc/*.md` files
- Code comments referencing specific performance numbers  
- Test class comments with performance expectations

## Success Criteria

**Process is successful when**:
- ✅ All performance numbers across documentation are consistent
- ✅ Performance data is current (< 30 days old)
- ✅ No conflicting performance claims exist
- ✅ Clear historical context for any performance changes
- ✅ Reproducible benchmark results (< 10% variation between runs)

**Process needs improvement when**:
- ❌ Conflicting numbers found in documentation
- ❌ Performance data is stale (> 60 days old)
- ❌ Large unexplained performance changes (> 25% without documented cause)
- ❌ Benchmark failures or inconsistent results

## Troubleshooting

### Common Issues

**"Performance tests are being skipped"**

```bash

# Solution: Check CI detection

export RUN_SPATIAL_INDEX_PERF_TESTS=true
unset CI  # If incorrectly detected as CI environment

```

**"Wildly different results between runs"**

```bash

# Solution: Ensure consistent test environment
# Close other applications consuming CPU/memory
# Run tests multiple times and average results
# Check for thermal throttling on laptops

```

**"Out of memory errors during large tests"**

```bash

# Solution: Increase JVM heap size

export MAVEN_OPTS="-Xmx8g -XX:MaxMetaspaceSize=512m"

```

**"Tests take too long"**

```bash

# Solution: Use smaller test configurations for routine updates
# Reserve full test suite for major releases
# Run subsets: mvn test -Dtest="*Performance*" -Dtest.include="Basic*"
# Note: Full performance suite is configured with 30-minute timeout (1800 seconds)

```

### Emergency Procedures

**If benchmarks completely fail**:
1. Use previous month's data with notation: "Data from [DATE] - current benchmarks under investigation"
2. Document the failure and investigation status
3. Set timeline for resolution

**If major performance regression discovered**:
1. Immediately document in PERFORMANCE_METRICS_MASTER.md with red flag notation
2. Create GitHub issue for investigation
3. Bisect recent commits to identify cause
4. Consider reverting if regression is severe

## Process Maintenance

### Monthly Review

- Review process effectiveness
- Update automation scripts if needed  
- Check for new performance tests that should be included
- Validate that all team members can execute the process

### Quarterly Review  

- Evaluate automation opportunities
- Review historical trends for insights
- Update benchmarking methodology if needed
- Consider new metrics or comparisons to track

### Annual Review

- Major process improvements
- Tool/infrastructure updates
- Historical data archival
- Performance target setting for next year

---

**Next Steps**: Create the automation scripts referenced in this document and integrate this process into the development workflow.
