# Automated Performance Reporting Guide

## Overview

This guide describes how to automatically capture performance data from CI runs and feed it back into the documentation, eliminating manual updates while maintaining accurate, up-to-date performance metrics.

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌─────────────────┐     ┌──────────────────┐
│   CI/CD     │────▶│  Performance │────▶│  Data Storage   │────▶│  Documentation   │
│  Pipeline   │     │   Tests      │     │  (Git/Cloud)    │     │   Generator      │
└─────────────┘     └──────────────┘     └─────────────────┘     └──────────────────┘
                           │                      │                         │
                           ▼                      ▼                         ▼
                    JSON/CSV Results      Historical Data          Updated .md files
```

## Implementation

### 1. Performance Test Output Format

Standardize benchmark output to JSON for easy parsing:

```java
public class PerformanceResultSerializer {
    
    public static void saveResults(List<PerformanceResult> results, String outputPath) {
        PerformanceReport report = PerformanceReport.builder()
            .timestamp(Instant.now())
            .commitHash(GitUtils.getCurrentCommitHash())
            .branchName(GitUtils.getCurrentBranch())
            .results(results)
            .environment(captureEnvironment())
            .build();
            
        String json = new ObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(report);
            
        Files.write(Paths.get(outputPath), json.getBytes());
    }
    
    private static Environment captureEnvironment() {
        return Environment.builder()
            .javaVersion(System.getProperty("java.version"))
            .os(System.getProperty("os.name"))
            .cpuCores(Runtime.getRuntime().availableProcessors())
            .maxMemory(Runtime.getRuntime().maxMemory())
            .jvmArgs(ManagementFactory.getRuntimeMXBean().getInputArguments())
            .build();
    }
}
```

### 2. CI Pipeline Configuration

#### GitHub Actions Workflow

```yaml
name: Performance Benchmarks and Documentation Update

on:
  push:
    branches: [main]
  schedule:
    - cron: '0 2 * * *'  # Daily at 2 AM UTC

jobs:
  benchmark:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v3
      with:
        token: ${{ secrets.GITHUB_TOKEN }}
        
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
        
    - name: Run Performance Benchmarks
      run: |
        mvn clean test -P performance
        
    - name: Upload Raw Results
      uses: actions/upload-artifact@v3
      with:
        name: performance-results
        path: target/performance-results.json
        
    - name: Update Documentation
      run: |
        mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.lucien.performance.DocUpdater" \
          -Dexec.args="target/performance-results.json"
          
    - name: Commit Documentation Updates
      run: |
        git config --local user.email "action@github.com"
        git config --local user.name "GitHub Action"
        git add lucien/doc/PERFORMANCE_METRICS_MASTER.md
        git add lucien/doc/performance-data/*.csv
        git diff --staged --quiet || git commit -m "Auto-update performance metrics [skip ci]"
        
    - name: Push Changes
      uses: ad-m/github-push-action@master
      with:
        github_token: ${{ secrets.GITHUB_TOKEN }}
        branch: ${{ github.ref }}
```

### 3. Documentation Update Tool

```java
package com.hellblazer.luciferase.lucien.performance;

public class DocumentationUpdater {
    
    private static final String METRICS_DOC_PATH = "lucien/doc/PERFORMANCE_METRICS_MASTER.md";
    private static final String CSV_DATA_DIR = "lucien/doc/performance-data/";
    
    public static void main(String[] args) throws Exception {
        String resultsPath = args[0];
        
        // Load latest results
        PerformanceReport report = loadResults(resultsPath);
        
        // Update CSV files
        updateCSVFiles(report);
        
        // Update markdown documentation
        updateMarkdownDoc(report);
        
        // Generate trend analysis
        generateTrendReport(report);
    }
    
    private static void updateMarkdownDoc(PerformanceReport report) throws IOException {
        String markdown = Files.readString(Paths.get(METRICS_DOC_PATH));
        
        // Update operation performance table
        markdown = updateOperationTable(markdown, report);
        
        // Update scalability metrics
        markdown = updateScalabilityTable(markdown, report);
        
        // Update timestamp
        markdown = updateTimestamp(markdown, report.getTimestamp());
        
        // Update commit reference
        markdown = updateCommitInfo(markdown, report.getCommitHash());
        
        Files.writeString(Paths.get(METRICS_DOC_PATH), markdown);
    }
    
    private static String updateOperationTable(String markdown, PerformanceReport report) {
        // Parse existing table
        MarkdownTable table = MarkdownTable.parse(markdown, "Individual Operation Performance");
        
        // Update values
        for (PerformanceResult result : report.getResults()) {
            String operation = result.getOperationName();
            String indexType = result.getIndexType();
            
            // Find and update cell
            table.updateCell(operation, indexType, formatMetric(result));
        }
        
        // Regenerate table markdown
        return table.toMarkdown();
    }
    
    private static void updateCSVFiles(PerformanceReport report) throws IOException {
        // Append to historical data
        for (String indexType : Arrays.asList("octree", "tetree", "prism")) {
            Path csvPath = Paths.get(CSV_DATA_DIR, indexType + "-performance.csv");
            
            // Create CSV if doesn't exist
            if (!Files.exists(csvPath)) {
                createCSVWithHeaders(csvPath);
            }
            
            // Append new data
            try (CSVWriter writer = new CSVWriter(new FileWriter(csvPath.toFile(), true))) {
                for (PerformanceResult result : report.getResults()) {
                    if (result.getIndexType().equalsIgnoreCase(indexType)) {
                        writer.writeNext(new String[]{
                            report.getTimestamp().toString(),
                            report.getCommitHash(),
                            result.getOperationName(),
                            String.valueOf(result.getEntityCount()),
                            String.valueOf(result.getMeanLatencyMicros()),
                            String.valueOf(result.getP95LatencyMicros()),
                            String.valueOf(result.getP99LatencyMicros()),
                            String.valueOf(result.getThroughputOpsPerSec()),
                            String.valueOf(result.getMemoryBytesPerEntity())
                        });
                    }
                }
            }
        }
    }
}
```

### 4. Markdown Table Parser/Updater

```java
public class MarkdownTableUpdater {
    
    public static String updatePerformanceTable(String markdown, Map<String, Map<String, String>> data) {
        // Find table by header
        Pattern tablePattern = Pattern.compile(
            "(\\| Operation.*\\|.*\\n)(\\|[-: ]+\\|[-: ]+\\|[-: ]+\\|.*\\n)((?:\\|.*\\|.*\\n)*)",
            Pattern.MULTILINE
        );
        
        Matcher matcher = tablePattern.matcher(markdown);
        
        if (matcher.find()) {
            String header = matcher.group(1);
            String separator = matcher.group(2);
            String oldRows = matcher.group(3);
            
            // Parse existing rows
            List<TableRow> rows = parseTableRows(oldRows);
            
            // Update with new data
            for (TableRow row : rows) {
                String operation = row.getOperation();
                
                if (data.containsKey(operation)) {
                    Map<String, String> metrics = data.get(operation);
                    row.updateMetrics(metrics);
                }
            }
            
            // Rebuild table
            StringBuilder newTable = new StringBuilder();
            newTable.append(header);
            newTable.append(separator);
            
            for (TableRow row : rows) {
                newTable.append(row.toMarkdown()).append("\n");
            }
            
            // Replace in original markdown
            return markdown.replace(matcher.group(0), newTable.toString());
        }
        
        return markdown;
    }
}
```

### 5. Performance Data Storage

#### Option A: Git-Based Storage (Simple)

Store performance data directly in the repository:

```
lucien/doc/performance-data/
├── octree-performance.csv
├── tetree-performance.csv
├── prism-performance.csv
├── performance-history.json
└── regression-baselines.json
```

Pros:
- Simple, no external dependencies
- Version controlled with code
- Easy to access

Cons:
- Repository size grows over time
- Limited query capabilities

#### Option B: Cloud Storage (Scalable)

Use cloud storage with API access:

```java
public class CloudPerformanceStorage {
    private final S3Client s3Client;
    private final String bucketName = "lucien-performance-data";
    
    public void storeResults(PerformanceReport report) {
        String key = String.format("results/%s/%s.json", 
            report.getTimestamp().toString(), 
            report.getCommitHash());
            
        s3Client.putObject(PutObjectRequest.builder()
            .bucket(bucketName)
            .key(key)
            .build(),
            RequestBody.fromString(toJson(report)));
    }
    
    public List<PerformanceReport> getHistoricalData(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        
        ListObjectsV2Response response = s3Client.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix("results/")
                .build());
                
        return response.contents().stream()
            .filter(obj -> parseTimestamp(obj.key()).isAfter(cutoff))
            .map(this::loadReport)
            .collect(Collectors.toList());
    }
}
```

### 6. Automated Trend Analysis

```java
public class PerformanceTrendAnalyzer {
    
    public TrendReport analyzeTrends(List<PerformanceReport> history) {
        TrendReport.Builder report = TrendReport.builder();
        
        // Group by operation and index type
        Map<String, List<PerformanceResult>> grouped = history.stream()
            .flatMap(r -> r.getResults().stream())
            .collect(Collectors.groupingBy(
                r -> r.getOperationName() + ":" + r.getIndexType()
            ));
            
        for (Map.Entry<String, List<PerformanceResult>> entry : grouped.entrySet()) {
            String key = entry.getKey();
            List<PerformanceResult> results = entry.getValue();
            
            // Sort by timestamp
            results.sort(Comparator.comparing(PerformanceResult::getTimestamp));
            
            // Calculate trend
            Trend trend = calculateTrend(results);
            report.addTrend(key, trend);
            
            // Detect anomalies
            List<Anomaly> anomalies = detectAnomalies(results);
            report.addAnomalies(key, anomalies);
        }
        
        return report.build();
    }
    
    private Trend calculateTrend(List<PerformanceResult> results) {
        if (results.size() < 2) {
            return Trend.STABLE;
        }
        
        // Simple linear regression on latency
        double[] x = IntStream.range(0, results.size())
            .mapToDouble(i -> i)
            .toArray();
        double[] y = results.stream()
            .mapToDouble(r -> r.getMeanLatencyMicros())
            .toArray();
            
        SimpleRegression regression = new SimpleRegression();
        for (int i = 0; i < x.length; i++) {
            regression.addData(x[i], y[i]);
        }
        
        double slope = regression.getSlope();
        double rSquared = regression.getRSquare();
        
        // Determine trend based on slope and R²
        if (rSquared < 0.5) {
            return Trend.STABLE; // No clear trend
        } else if (slope > 0.05) {
            return Trend.DEGRADING;
        } else if (slope < -0.05) {
            return Trend.IMPROVING;
        } else {
            return Trend.STABLE;
        }
    }
}
```

### 7. Documentation Templates

#### Performance Summary Section

```markdown
## Performance Summary

**Last Updated**: {{ timestamp }}  
**Commit**: {{ commit_hash }}  
**Environment**: {{ environment }}

### Recent Trends

{{ for trend in trends }}
- **{{ trend.operation }}**: {{ trend.direction }} ({{ trend.change_percent }}% over last 7 days)
{{ endfor }}

### Alerts

{{ if regressions }}
⚠️ **Performance Regressions Detected:**
{{ for regression in regressions }}
- {{ regression.operation }}: {{ regression.details }}
{{ endfor }}
{{ endif }}
```

### 8. Integration with Existing Documentation

Update the existing `PERFORMANCE_METRICS_MASTER.md` to include placeholders:

```markdown
# Performance Metrics Master

<!-- AUTOMATED SECTION START -->
<!-- DO NOT EDIT BELOW THIS LINE - AUTOMATICALLY GENERATED -->

## Current Performance Metrics

**Last Updated**: <!-- AUTO:timestamp -->  
**Commit**: <!-- AUTO:commit -->  
**CI Run**: <!-- AUTO:ci_run_url -->

### Individual Operation Performance

<!-- AUTO:operation_table -->

### Memory Usage

<!-- AUTO:memory_table -->

### Scalability Metrics

<!-- AUTO:scalability_table -->

<!-- AUTOMATED SECTION END -->

## Historical Trends

See [Performance Dashboard](<!-- AUTO:dashboard_url -->) for interactive charts.

<!-- AUTO:trend_charts -->
```

### 9. Dashboard Generation

```java
public class PerformanceDashboardGenerator {
    
    public void generateDashboard(List<PerformanceReport> history) {
        // Generate HTML dashboard
        String html = generateHTML(history);
        Files.writeString(Paths.get("lucien/doc/performance-dashboard.html"), html);
        
        // Generate Markdown with embedded charts
        String markdown = generateMarkdownWithCharts(history);
        Files.writeString(Paths.get("lucien/doc/PERFORMANCE_DASHBOARD.md"), markdown);
    }
    
    private String generateMarkdownWithCharts(List<PerformanceReport> history) {
        StringBuilder md = new StringBuilder();
        
        md.append("# Performance Dashboard\n\n");
        md.append("## Operation Latency Trends\n\n");
        
        // Generate Chart.js charts as base64 images
        for (String operation : getOperations(history)) {
            String chartImage = generateChartImage(history, operation);
            md.append(String.format("### %s\n\n", operation));
            md.append(String.format("![%s Performance](data:image/png;base64,%s)\n\n", 
                operation, chartImage));
        }
        
        return md.toString();
    }
}
```

### 10. Notification System

```java
public class PerformanceNotifier {
    
    public void checkAndNotify(PerformanceReport current, List<PerformanceReport> history) {
        List<PerformanceIssue> issues = new ArrayList<>();
        
        // Check for regressions
        RegressionDetector detector = new RegressionDetector();
        issues.addAll(detector.detectRegressions(current, history));
        
        // Check for anomalies
        AnomalyDetector anomalyDetector = new AnomalyDetector();
        issues.addAll(anomalyDetector.detectAnomalies(current, history));
        
        if (!issues.isEmpty()) {
            // Create GitHub issue
            createGitHubIssue(issues);
            
            // Send Slack notification
            sendSlackNotification(issues);
            
            // Update PR status
            updatePullRequestStatus(issues);
        }
    }
    
    private void createGitHubIssue(List<PerformanceIssue> issues) {
        GitHubClient github = new GitHubClient(System.getenv("GITHUB_TOKEN"));
        
        String title = "Performance Regression Detected";
        String body = formatIssueBody(issues);
        
        github.createIssue("Luciferase", "lucien", title, body, 
            Arrays.asList("performance", "regression"));
    }
}
```

## Complete Automation Flow

### 1. Developer Workflow

```bash
# Developer makes changes
git commit -m "Optimize k-NN search"
git push

# CI automatically:
# 1. Runs performance tests
# 2. Updates documentation
# 3. Commits changes
# 4. Notifies if regression detected
```

### 2. Scheduled Updates

```yaml
# .github/workflows/scheduled-performance.yml
name: Weekly Performance Report

on:
  schedule:
    - cron: '0 0 * * 0'  # Weekly on Sunday

jobs:
  weekly-report:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v3
    
    - name: Generate Weekly Report
      run: |
        mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.lucien.performance.WeeklyReportGenerator"
        
    - name: Create Pull Request
      uses: peter-evans/create-pull-request@v5
      with:
        title: "Weekly Performance Report"
        body: "Automated weekly performance report with trends and analysis"
        branch: "performance-report-${{ github.run_number }}"
```

### 3. Manual Trigger

```yaml
# Allow manual performance runs
on:
  workflow_dispatch:
    inputs:
      benchmark_suite:
        description: 'Benchmark suite to run'
        required: true
        default: 'all'
        type: choice
        options:
        - all
        - core
        - scalability
        - memory
```

## Benefits

1. **Always Current**: Documentation automatically reflects latest performance data
2. **Historical Tracking**: Full history preserved for trend analysis
3. **Regression Detection**: Automated alerts for performance degradation
4. **Zero Manual Work**: Completely automated from test to documentation
5. **Visibility**: Performance data visible in PRs and dashboards
6. **Reproducibility**: All data includes environment and commit info

## Configuration

### Environment Variables

```bash
# Required for CI
GITHUB_TOKEN=xxx
SLACK_WEBHOOK_URL=xxx
PERFORMANCE_BASELINE_BRANCH=main

# Optional cloud storage
AWS_ACCESS_KEY_ID=xxx
AWS_SECRET_ACCESS_KEY=xxx
PERFORMANCE_BUCKET=lucien-performance-data
```

### Maven Profile

```xml
<profile>
    <id>performance-ci</id>
    <properties>
        <performance.output.format>json</performance.output.format>
        <performance.output.path>target/performance-results.json</performance.output.path>
        <performance.update.docs>true</performance.update.docs>
    </properties>
</profile>
```

## Summary

This automated system eliminates manual performance reporting by:

1. Running benchmarks in CI
2. Parsing results into structured data
3. Updating markdown documentation automatically
4. Maintaining historical data for trends
5. Detecting and alerting on regressions
6. Generating dashboards and reports

The documentation stays current with zero manual intervention while providing rich performance insights and trend analysis.