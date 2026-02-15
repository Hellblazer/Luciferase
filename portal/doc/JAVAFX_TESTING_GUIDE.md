# JavaFX Testing Guide

**Date**: 2025-12-09
**Task**: Luciferase-c3l (Phase 0.5)
**Author**: Phase 0 Infrastructure - ESVO Inspector

## Overview

This guide explains how to write and run JavaFX UI tests in the Portal module using the TestFX framework and the custom `JavaFXTestBase` class.

## Quick Start

### 1. Extend JavaFXTestBase

```java
import com.hellblazer.luciferase.portal.JavaFXTestBase;
import org.junit.jupiter.api.Test;
import javafx.scene.shape.Box;
import static org.junit.jupiter.api.Assertions.*;

public class MyJavaFXTest extends JavaFXTestBase {
    
    @Test
    public void testCreateBox() throws Exception {
        runOnFxThreadAndWait(() -> {
            var box = new Box(1.0, 1.0, 1.0);
            assertNotNull(box);
            assertEquals(1.0, box.getWidth());
        });
    }
}
```text

### 2. Run Tests

```bash
# Run all portal tests
mvn test -pl portal

# Run specific test
mvn test -pl portal -Dtest=MyJavaFXTest

# Run in headless mode (for CI/CD)
mvn test -pl portal -Dtestfx.headless=true
```text

## JavaFXTestBase Features

### Automatic Initialization

The `JavaFXTestBase` class automatically:

- Initializes the JavaFX toolkit before tests run
- Ensures proper cleanup after tests complete
- Handles threading requirements
- Supports both headed and headless modes

### Key Methods

#### `runOnFxThreadAndWait(Runnable)`

Executes code on the JavaFX Application Thread and waits for completion:

```java
@Test
public void testSceneGraph() throws Exception {
    runOnFxThreadAndWait(() -> {
        var root = new Group();
        var box = new Box(1.0, 1.0, 1.0);
        root.getChildren().add(box);
        assertEquals(1, root.getChildren().size());
    });
}
```text

#### `isJavaFXInitialized()`

Checks if JavaFX toolkit is initialized:

```java
@Test
public void testInitialization() {
    assertTrue(isJavaFXInitialized());
}
```text

## Headless Mode (CI/CD)

### Configuration

To run tests in headless mode without a display:

```bash
mvn test -Dtestfx.headless=true
```text

This automatically configures:

- `java.awt.headless=true`
- `prism.order=sw` (software rendering)
- `prism.text=t2k`
- `glass.platform=Monocle`
- `monocle.platform=Headless`

### CI/CD Integration

For GitHub Actions, GitLab CI, or other CI systems:

```yaml
# .github/workflows/test.yml
- name: Run JavaFX Tests
  run: mvn test -pl portal -Dtestfx.headless=true
```text

### Maven Configuration

For persistent headless configuration, add to `portal/pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <systemPropertyVariables>
            <testfx.headless>true</testfx.headless>
        </systemPropertyVariables>
    </configuration>
</plugin>
```text

## Best Practices

### 1. Always Use runOnFxThreadAndWait()

```java
// ✓ GOOD
@Test
public void testGood() throws Exception {
    runOnFxThreadAndWait(() -> {
        var box = new Box(1.0, 1.0, 1.0);
        assertEquals(1.0, box.getWidth());
    });
}

// ✗ BAD - May fail with threading issues
@Test
public void testBad() {
    var box = new Box(1.0, 1.0, 1.0); // Not on FX thread!
    assertEquals(1.0, box.getWidth());
}
```text

### 2. Keep Tests Isolated

Each test should create its own nodes and not rely on state from other tests:

```java
@Test
public void testIsolated1() throws Exception {
    runOnFxThreadAndWait(() -> {
        var root = new Group(); // Create fresh for this test
        // ... test code
    });
}

@Test
public void testIsolated2() throws Exception {
    runOnFxThreadAndWait(() -> {
        var root = new Group(); // Create fresh for this test
        // ... test code
    });
}
```text

### 3. Test Rendering Logic, Not Rendering

Focus on testing scene graph structure, transforms, and properties rather than actual pixel rendering:

```java
@Test
public void testTransforms() throws Exception {
    runOnFxThreadAndWait(() -> {
        var box = new Box(1.0, 1.0, 1.0);
        box.setTranslateX(10.0);
        assertEquals(10.0, box.getTranslateX()); // ✓ Test property
        // Don't test actual pixels on screen
    });
}
```text

### 4. Use Descriptive Test Names

```java
@Test
public void testOctreeNodeRendererCreatesCorrectNumberOfChildren() {
    // Clear what this tests
}

@Test
public void testMaterialAppliedToAllNodes() {
    // Clear what this tests
}
```text

## Testing Patterns

### Testing Scene Graph Structure

```java
@Test
public void testSceneGraphHierarchy() throws Exception {
    runOnFxThreadAndWait(() -> {
        var root = new Group();
        var child1 = new Group();
        var child2 = new Group();
        
        root.getChildren().addAll(child1, child2);
        
        assertEquals(2, root.getChildren().size());
        assertTrue(root.getChildren().contains(child1));
        assertTrue(root.getChildren().contains(child2));
    });
}
```text

### Testing Custom Renderers

```java
@Test
public void testOctreeNodeRenderer() throws Exception {
    runOnFxThreadAndWait(() -> {
        var renderer = new OctreeNodeMeshRenderer(10, Strategy.BATCHED);
        var nodes = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8);
        
        var result = renderer.render(nodes);
        
        assertNotNull(result);
        assertTrue(result instanceof Group);
        // Verify structure
    });
}
```text

### Testing Transforms

```java
@Test
public void testNodeTransforms() throws Exception {
    runOnFxThreadAndWait(() -> {
        var box = new Box(1.0, 1.0, 1.0);
        
        box.setTranslateX(10.0);
        box.setScaleY(2.0);
        box.setRotate(45.0);
        
        assertEquals(10.0, box.getTranslateX());
        assertEquals(2.0, box.getScaleY());
        assertEquals(45.0, box.getRotate());
    });
}
```text

### Testing Materials

```java
@Test
public void testMaterialProperties() throws Exception {
    runOnFxThreadAndWait(() -> {
        var material = new PhongMaterial(Color.BLUE);
        material.setSpecularColor(Color.WHITE);
        
        var box = new Box(1.0, 1.0, 1.0);
        box.setMaterial(material);
        
        assertEquals(material, box.getMaterial());
        assertEquals(Color.BLUE, material.getDiffuseColor());
        assertEquals(Color.WHITE, material.getSpecularColor());
    });
}
```text

## Example Tests

See `JavaFXTestBaseExample.java` for comprehensive examples of:

- Basic node creation
- Scene graph operations
- Material properties
- Transform manipulation
- Hierarchical structures
- Batch node creation

## Troubleshooting

### Test Hangs or Times Out

If tests hang, check:

1. All JavaFX operations are inside `runOnFxThreadAndWait()`
2. No infinite loops in test code
3. Timeout values are sufficient (default 5 seconds)

### "Not on FX application thread" Error

This means you're creating JavaFX nodes outside of `runOnFxThreadAndWait()`:

```java
// ✗ WRONG
@Test
public void test() {
    var box = new Box(1.0, 1.0, 1.0); // Error!
}

// ✓ CORRECT
@Test
public void test() throws Exception {
    runOnFxThreadAndWait(() -> {
        var box = new Box(1.0, 1.0, 1.0); // OK
    });
}
```text

### Headless Mode Fails

If headless tests fail, ensure:

1. Using `-Dtestfx.headless=true` flag
2. Not trying to display actual windows
3. Not using features requiring GPU (use software rendering)

## Dependencies

The following dependencies are configured in `portal/pom.xml`:

```xml
<dependency>
    <groupId>org.testfx</groupId>
    <artifactId>testfx-core</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testfx</groupId>
    <artifactId>testfx-junit5</artifactId>
    <scope>test</scope>
</dependency>
```text

TestFX version is managed in the root `pom.xml`: **4.0.18**

## Architecture

### Test Lifecycle

1. `@BeforeAll` - Initialize JavaFX toolkit once per test class
2. `@Test` - Run individual tests on FX thread
3. `@AfterAll` - Shutdown JavaFX toolkit after all tests

### Thread Safety

- JavaFX toolkit runs on a dedicated Application Thread
- All scene graph operations must occur on this thread
- `runOnFxThreadAndWait()` handles thread marshalling automatically
- Test assertions can run on either thread

### Initialization

JavaFX initialization happens once per JVM using:

- `AtomicBoolean` flag to prevent re-initialization
- `CountDownLatch` for synchronization
- Separate thread for `Application.launch()`
- 10-second timeout for initialization

## Performance Notes

- JavaFX initialization adds ~500ms overhead per test class
- Each test execution is fast (<10ms typically)
- Headless mode is faster than headed mode
- Consider grouping related tests in one class to amortize init cost

## Future Enhancements

Potential improvements for Phase 1+:

- Add TestFX robot support for interaction testing
- Screenshot capture for visual regression testing
- Performance benchmarking helpers
- Custom assertions for JavaFX properties

## References

- **TestFX Documentation**: https://github.com/TestFX/TestFX
- **JavaFX Documentation**: https://openjfx.io/
- **JavaFXTestBase**: `portal/src/test/java/com/hellblazer/luciferase/portal/JavaFXTestBase.java`
- **Example Tests**: `portal/src/test/java/com/hellblazer/luciferase/portal/JavaFXTestBaseExample.java`

---

*This guide completes Luciferase-c3l (Phase 0.5: Set up JavaFX testing infrastructure)*
