# SIMD Preview Features Strategy

## Overview

Java's Vector API (JEP 448) for SIMD operations is in preview status as of Java 21-23. This document outlines how to handle preview features in both development and CI environments while maintaining backward compatibility.

## Strategy: Multi-Profile Approach

### 1. Default Build (No Preview Features)
The default build remains stable without preview features:
- All optimizations from Phases 1-2 are always active
- SIMD optimizations are disabled by default
- Code compiles and runs on standard Java 23+

### 2. Preview Profile for SIMD
Create a Maven profile that enables preview features:
- Activated explicitly with `-Psimd-preview`
- Enables `--enable-preview` for compilation and runtime
- Only used for performance testing and benchmarking

### 3. Runtime Detection
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

## Implementation Plan

### 1. Add Preview Profile to pom.xml

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

### 2. Create Abstraction Layer

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

### 3. CI Configuration

#### GitHub Actions
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

### 4. Development Workflow

#### For regular development:
```bash
# Standard build - no preview features
mvn clean install

# Run tests
mvn test
```

#### For SIMD development:
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

## Benefits

1. **Backward Compatibility**: Default build works without preview features
2. **Progressive Enhancement**: SIMD used when available, falls back gracefully
3. **CI Stability**: Main CI pipeline remains stable
4. **Future-Proof**: Easy to transition when Vector API becomes stable
5. **Testing**: Can test both implementations side-by-side

## Migration Path

When Vector API becomes stable (likely Java 25+):
1. Remove preview flags from SIMD profile
2. Make SIMD profile the default
3. Keep scalar implementation for older JVMs
4. Update minimum Java version when appropriate

## Alternatives Considered

1. **Conditional Compilation**: Too complex with Maven
2. **Separate Module**: Adds complexity for small benefit
3. **JNI/Native**: Platform-specific, harder to maintain
4. **Wait for Stable API**: Misses current performance opportunities

## Recommendation

Proceed with the multi-profile approach. It provides the best balance of:
- Performance gains for those who want them
- Stability for production use
- Easy migration when API stabilizes
- Clear separation of concerns