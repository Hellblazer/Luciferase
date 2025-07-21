# SIMD Usage Guide

## Quick Start

### Building without SIMD (Default)
```bash
# Standard build - works on any Java 23+
mvn clean install

# Run tests
mvn test
```

### Building with SIMD Preview Features
```bash
# Build with SIMD support
mvn clean install -Psimd-preview

# Run tests with SIMD
mvn test -Psimd-preview

# Run specific SIMD benchmarks
mvn test -Psimd-preview -Dtest=SIMDBenchmark
```

## Runtime Configuration

### Enable SIMD at Runtime
```bash
# Via system property
java -Dsentry.enableSIMD=true -jar your-app.jar

# With preview features
java --enable-preview \
     --add-modules jdk.incubator.vector \
     -Dsentry.enableSIMD=true \
     -jar your-app.jar
```

### Check SIMD Status in Code
```java
// Check if SIMD is available and enabled
if (SIMDSupport.isAvailable()) {
    System.out.println("Using SIMD optimizations");
} else {
    System.out.println("Status: " + SIMDSupport.getStatus());
}

// Enable SIMD programmatically
SIMDSupport.setEnabled(true);
```

## IDE Configuration

### IntelliJ IDEA
1. Open Project Structure (⌘+;)
2. Go to Project Settings → Modules → sentry
3. Under "Language level", select "23 (Preview)"
4. In VM options for run configurations, add:
   ```
   --enable-preview --add-modules jdk.incubator.vector
   ```

### VS Code
Add to `.vscode/settings.json`:
```json
{
  "java.configuration.runtimes": [
    {
      "name": "JavaSE-23",
      "path": "/path/to/jdk-23",
      "default": true
    }
  ],
  "java.jdt.ls.vmargs": "--enable-preview --add-modules jdk.incubator.vector"
}
```

### Eclipse
1. Right-click project → Properties
2. Java Compiler → Enable preview features
3. Run Configurations → Arguments → VM arguments:
   ```
   --enable-preview --add-modules jdk.incubator.vector
   ```

## Troubleshooting

### Error: "Preview features are not enabled"
**Solution**: Add `--enable-preview` to both compile and runtime flags

### Error: "Module jdk.incubator.vector not found"
**Solution**: Ensure you're using Java 21+ and add `--add-modules jdk.incubator.vector`

### SIMD not detected at runtime
**Check**:
```java
System.out.println(SIMDSupport.getStatus());
```

### Performance not improved with SIMD
**Verify**:
1. SIMD is actually enabled: `-Dsentry.enableSIMD=true`
2. Check CPU support: Modern x86_64 or ARM64 required
3. Data size is large enough (SIMD helps more with larger datasets)

## Performance Testing

### Compare SIMD vs Scalar
```bash
# Run without SIMD
mvn test -Dtest=PerformanceBenchmark

# Run with SIMD
mvn test -Psimd-preview -Dtest=PerformanceBenchmark
```

### Expected Performance Gains
- Geometric predicates: 20-40% improvement
- Batch operations: 30-50% improvement
- Overall flip operations: 15-25% improvement

## Development Guidelines

### Writing SIMD-Compatible Code

1. **Use the abstraction layer**:
```java
// Bad - direct SIMD usage
import jdk.incubator.vector.*;

// Good - use abstraction
GeometricPredicates predicates = GeometricPredicatesFactory.create();
```

2. **Batch operations when possible**:
```java
// Process multiple points together for better SIMD utilization
double[] results = predicates.batchOrientation(points, a, b, c);
```

3. **Align data structures**:
```java
// Align arrays for better SIMD performance
double[] coordinates = new double[((size + 7) / 8) * 8];
```

## CI/CD Integration

The CI pipeline automatically:
1. Builds and tests without SIMD (required to pass)
2. Builds and tests with SIMD (optional, won't fail build)
3. Runs benchmarks on demand

To trigger benchmarks, include `[benchmark]` in commit message:
```bash
git commit -m "Optimize geometric predicates [benchmark]"
```

## Future Migration

When Vector API becomes stable (Java 25+):
1. Remove `-Psimd-preview` profile usage
2. Update minimum Java version
3. Make SIMD the default
4. Keep scalar fallback for compatibility