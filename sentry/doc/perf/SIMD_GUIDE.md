# SIMD Vectorization Guide

**Last Updated**: 2026-02-08
**Version**: 2.0 (Consolidated from SIMD_PREVIEW_STRATEGY + SIMD_USAGE)

## Overview

Java's Vector API (JEP 448) for SIMD operations is in preview status as of Java 21-23. This guide covers the complete SIMD architecture, build configuration, usage patterns, and migration path for the Sentry module.

---

## Table of Contents

1. [Architecture & Strategy](#architecture--strategy)
2. [Quick Start](#quick-start)
3. [Build Configuration](#build-configuration)
4. [Runtime Configuration](#runtime-configuration)
5. [IDE Setup](#ide-setup)
6. [Development Guidelines](#development-guidelines)
7. [Performance Testing](#performance-testing)
8. [Troubleshooting](#troubleshooting)
9. [CI/CD Integration](#cicd-integration)
10. [Future Migration](#future-migration)

---

## Architecture & Strategy

### Multi-Profile Approach

#### 1. Default Build (No Preview Features)

The default build remains stable without preview features:
- All optimizations from Phases 1-2 are always active
- SIMD optimizations are disabled by default
- Code compiles and runs on standard Java 23+

#### 2. Preview Profile for SIMD

Create a Maven profile that enables preview features:
- Activated explicitly with `-Psimd-preview`
- Enables `--enable-preview` for compilation and runtime
- Only used for performance testing and benchmarking

#### 3. Runtime Detection

Use runtime checks to conditionally enable SIMD:

```java
public class SIMDSupport {
    private static final boolean VECTOR_API_AVAILABLE;

    static {
        boolean available = false;
        try {
            // Check if Vector API is available
            Class.forName("jdk.incubator.vector.Vector");
            available = true;
        } catch (ClassNotFoundException e) {
            // Vector API not available
        }
        VECTOR_API_AVAILABLE = available;
    }

    public static boolean isAvailable() {
        return VECTOR_API_AVAILABLE &&
               "true".equals(System.getProperty("sentry.enableSIMD"));
    }
}
```

### Abstraction Layer Design

```java
// GeometricPredicates.java
public interface GeometricPredicates {
    double orientation(double ax, double ay, double az,
                      double bx, double by, double bz,
                      double cx, double cy, double cz,
                      double dx, double dy, double dz);

    double inSphere(double ax, double ay, double az,
                   double bx, double by, double bz,
                   double cx, double cy, double cz,
                   double dx, double dy, double dz,
                   double ex, double ey, double ez);
}

// ScalarGeometricPredicates.java - Always available
public class ScalarGeometricPredicates implements GeometricPredicates {
    // Current implementation
}

// SIMDGeometricPredicates.java - Only compiled with preview
@PreviewFeature
public class SIMDGeometricPredicates implements GeometricPredicates {
    // SIMD implementation using Vector API
}

// GeometricPredicatesFactory.java
public class GeometricPredicatesFactory {
    public static GeometricPredicates create() {
        if (SIMDSupport.isAvailable()) {
            try {
                Class<?> simdClass = Class.forName(
                    "com.hellblazer.sentry.SIMDGeometricPredicates"
                );
                return (GeometricPredicates) simdClass
                    .getDeclaredConstructor()
                    .newInstance();
            } catch (Exception e) {
                // Fall back to scalar
            }
        }
        return new ScalarGeometricPredicates();
    }
}
```

---

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

---

## Build Configuration

### Maven Profile Configuration

Add to `pom.xml`:

```xml
<profiles>
    <!-- Standard profile - no preview features -->
    <profile>
        <id>default</id>
        <activation>
            <activeByDefault>true</activeByDefault>
        </activation>
    </profile>

    <!-- SIMD preview features profile -->
    <profile>
        <id>simd-preview</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                        <compilerArgs>
                            <arg>--enable-preview</arg>
                            <arg>--add-modules</arg>
                            <arg>jdk.incubator.vector</arg>
                        </compilerArgs>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <configuration>
                        <argLine>
                            --enable-preview
                            --add-modules jdk.incubator.vector
                            -Dsentry.enableSIMD=true
                            ${existing.argLine}
                        </argLine>
                    </configuration>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

---

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

---

## IDE Setup

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

---

## Development Guidelines

### Writing SIMD-Compatible Code

#### 1. Use the Abstraction Layer

```java
// Bad - direct SIMD usage
import jdk.incubator.vector.*;

// Good - use abstraction
GeometricPredicates predicates = GeometricPredicatesFactory.create();
```

#### 2. Batch Operations When Possible

```java
// Process multiple points together for better SIMD utilization
double[] results = predicates.batchOrientation(points, a, b, c);
```

#### 3. Align Data Structures

```java
// Align arrays for better SIMD performance
double[] coordinates = new double[((size + 7) / 8) * 8];
```

### Development Workflow

#### For Regular Development

```bash
# Standard build - no preview features
mvn clean install

# Run tests
mvn test
```

#### For SIMD Development

```bash
# Build with preview features
mvn clean install -Psimd-preview

# Run SIMD benchmarks
mvn test -Psimd-preview -Dtest=SIMDBenchmark

# Run with SIMD enabled
java --enable-preview --add-modules jdk.incubator.vector \
     -Dsentry.enableSIMD=true \
     -cp target/classes:... \
     MainClass
```

---

## Performance Testing

### Compare SIMD vs Scalar

```bash
# Run without SIMD
mvn test -Dtest=PerformanceBenchmark

# Run with SIMD
mvn test -Psimd-preview -Dtest=PerformanceBenchmark
```

### Expected Performance Gains

- Geometric predicates: 20-40% improvement (with batch operations)
- Batch operations: 30-50% improvement
- Overall flip operations: 15-25% improvement (when optimized)

### Current Performance Characteristics

**Note**: Current SIMD implementation shows overhead exceeding benefits for individual operations (-70% slower). Infrastructure is complete but requires batch operation optimization for production gains.

**Batch operation results** (more promising):
- Individual operations: 0.03x slowdown
- Batch operations: 0.25x slowdown
- Further optimization needed

---

## Troubleshooting

### Error: "Preview features are not enabled"

**Solution**: Add `--enable-preview` to both compile and runtime flags

### Error: "Module jdk.incubator.vector not found"

**Solution**: Ensure you're using Java 21+ and add `--add-modules jdk.incubator.vector`

### SIMD Not Detected at Runtime

**Check**:

```java
System.out.println(SIMDSupport.getStatus());
```

### Performance Not Improved with SIMD

**Verify**:
1. SIMD is actually enabled: `-Dsentry.enableSIMD=true`
2. Check CPU support: Modern x86_64 or ARM64 required
3. Data size is large enough (SIMD helps more with larger datasets)
4. Using batch operations (individual operations currently show overhead)

---

## CI/CD Integration

### GitHub Actions Configuration

```yaml
jobs:
  # Standard build - always runs
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '23'
      - run: mvn clean test

  # Preview features build - separate job
  build-preview:
    runs-on: ubuntu-latest
    continue-on-error: true  # Don't fail the build
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '23'
      - run: mvn clean test -Psimd-preview
```

### Benchmark Triggers

The CI pipeline automatically:

1. Builds and tests without SIMD (required to pass)
2. Builds and tests with SIMD (optional, won't fail build)
3. Runs benchmarks on demand

To trigger benchmarks, include `[benchmark]` in commit message:

```bash
git commit -m "Optimize geometric predicates [benchmark]"
```

---

## Future Migration

### When Vector API Becomes Stable (Java 25+)

1. Remove preview flags from SIMD profile
2. Make SIMD profile the default
3. Keep scalar implementation for older JVMs
4. Update minimum Java version when appropriate

### Migration Steps

1. **Phase 1**: Remove `--enable-preview` from pom.xml
2. **Phase 2**: Remove preview profile, make SIMD default
3. **Phase 3**: Update minimum Java version requirement
4. **Phase 4**: Keep scalar implementation for compatibility

---

## Benefits Summary

1. **Backward Compatibility**: Default build works without preview features
2. **Progressive Enhancement**: SIMD used when available, falls back gracefully
3. **CI Stability**: Main CI pipeline remains stable
4. **Future-Proof**: Easy to transition when Vector API becomes stable
5. **Testing**: Can test both implementations side-by-side

## Current Status

- ✅ **Infrastructure**: Complete and working
- ✅ **Runtime Detection**: Implemented with fallback
- ✅ **Maven Profiles**: Configured for preview features
- ✅ **Abstraction Layer**: Factory pattern with multiple implementations
- ⚠️ **Performance**: Requires batch operation optimization for production use
- ⚠️ **Adoption**: Disabled by default until batch optimizations complete

---

**Document Version**: 2.0 (Consolidated)
**Last Updated**: 2026-02-08
**Status**: Infrastructure complete, optimization in progress
