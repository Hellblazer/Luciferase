# Phase 5a.4: Live Metrics & Instrumentation - Revised Implementation Plan

**Bead**: Luciferase-vmjc
**Status**: in_progress
**Depends On**: Luciferase-90qr (Phase 5a.3 - completed)
**Blocks**: Luciferase-4i5z (Phase 5a.5 - Integration Testing)
**Last Updated**: 2026-01-22

## Executive Summary

This plan implements real-time metrics visualization for beam optimization using a **JavaFX-based overlay** in the portal module. The overlay displays coherence metrics, kernel dispatch statistics, frame timing, and GPU memory usage on top of the 3D rendering viewport.

**Key Decision**: Option B selected - JavaFX UI components instead of ByteBuffer rendering.

## Phase Structure

| Phase | Description | Location | Duration | Files |
|-------|-------------|----------|----------|-------|
| 1 | GPU Metrics Collection | render module | 0.5-1 day | 6 |
| 2 | BeamMetricsOverlay | portal module | 1.5-2 days | 7 |
| 3 | Integration | portal module | 0.5-1 day | 2-3 |
| **Total** | | | **3-4 days** | **15-16** |

---

## Phase 1: GPU Metrics Collection (APPROVED)

**Location**: `render/src/main/java/com/hellblazer/luciferase/esvo/gpu/beam/metrics/`

### Files (6)

#### 1. GPUMetricsCollector.java
```java
package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

import com.hellblazer.luciferase.esvo.gpu.beam.BeamTree;
import com.hellblazer.luciferase.esvo.gpu.beam.BeamKernelSelector;

/**
 * Collects GPU rendering metrics using CPU-side System.nanoTime() timing.
 * No OpenCL dependency - pure Java implementation.
 */
public class GPUMetricsCollector {
    private final MetricsAggregator aggregator;

    public GPUMetricsCollector();
    public GPUMetricsCollector(int windowSize);

    // Timing boundaries
    public void beginFrame();
    public void endFrame();

    // Record kernel execution timing
    public void recordKernelTiming(String kernelName, long startNanos, long endNanos);

    // Capture BeamTree statistics
    public void recordBeamTreeStats(BeamTree tree);

    // Capture kernel selection metrics
    public void recordKernelSelection(BeamKernelSelector.SelectionMetrics metrics);

    // Get current snapshot (thread-safe)
    public MetricsSnapshot getSnapshot();

    // Reset all metrics
    public void reset();
}
```

#### 2. KernelTimingMetrics.java
```java
package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

/**
 * Timing metrics for a single kernel execution.
 */
public record KernelTimingMetrics(
    String kernelName,
    long executionTimeNanos,
    long timestampNanos
) {
    public double executionTimeMs() {
        return executionTimeNanos / 1_000_000.0;
    }
}
```

#### 3. CoherenceSnapshot.java
```java
package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

/**
 * Snapshot of coherence state from BeamTree.
 */
public record CoherenceSnapshot(
    double averageCoherence,    // 0.0 to 1.0
    double minCoherence,
    double maxCoherence,
    int totalBeams,
    int maxDepth
) {
    public static CoherenceSnapshot empty() {
        return new CoherenceSnapshot(0.0, 0.0, 0.0, 0, 0);
    }
}
```

#### 4. DispatchMetrics.java
```java
package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

/**
 * Kernel dispatch statistics.
 */
public record DispatchMetrics(
    int totalDispatches,
    int batchDispatches,
    int singleRayDispatches,
    double batchPercentage
) {
    public static DispatchMetrics empty() {
        return new DispatchMetrics(0, 0, 0, 0.0);
    }

    public static DispatchMetrics from(int total, int batch, int singleRay) {
        double pct = total > 0 ? 100.0 * batch / total : 0.0;
        return new DispatchMetrics(total, batch, singleRay, pct);
    }
}
```

#### 5. MetricsAggregator.java
```java
package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Rolling window aggregator for metrics smoothing.
 * Thread-safe for concurrent read/write access.
 */
public class MetricsAggregator {
    public static final int DEFAULT_WINDOW_SIZE = 60;

    private final int windowSize;
    private final ReentrantReadWriteLock lock;

    public MetricsAggregator();
    public MetricsAggregator(int windowSize);

    // Add frame data to rolling window
    public void addFrame(long frameTimeNanos, CoherenceSnapshot coherence,
                         DispatchMetrics dispatch);

    // Get aggregated snapshot
    public MetricsSnapshot aggregate();

    // Clear all data
    public void clear();

    // Current window fill level
    public int getFrameCount();
}
```

#### 6. MetricsSnapshot.java
```java
package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

/**
 * Combined metrics snapshot for overlay consumption.
 * Immutable, thread-safe to pass between threads.
 */
public record MetricsSnapshot(
    // Frame timing
    double currentFps,
    double avgFrameTimeMs,
    double minFrameTimeMs,
    double maxFrameTimeMs,

    // Coherence
    CoherenceSnapshot coherence,

    // Dispatch
    DispatchMetrics dispatch,

    // Memory (placeholder for Phase 5b)
    long gpuMemoryUsedBytes,
    long gpuMemoryTotalBytes,

    // Timestamp
    long timestampNanos
) {
    public static MetricsSnapshot empty() {
        return new MetricsSnapshot(
            0.0, 0.0, 0.0, 0.0,
            CoherenceSnapshot.empty(),
            DispatchMetrics.empty(),
            0, 0,
            System.nanoTime()
        );
    }

    public double memoryUsagePercent() {
        return gpuMemoryTotalBytes > 0
            ? 100.0 * gpuMemoryUsedBytes / gpuMemoryTotalBytes
            : 0.0;
    }
}
```

### Phase 1 Test Files

**Location**: `render/src/test/java/com/hellblazer/luciferase/esvo/gpu/beam/metrics/`

| Test File | Tests |
|-----------|-------|
| GPUMetricsCollectorTest.java | beginFrame/endFrame timing, kernel recording, thread-safety |
| MetricsAggregatorTest.java | Rolling window behavior, boundary conditions, aggregation accuracy |
| MetricsSnapshotTest.java | Record immutability, empty factory, memory percentage calculation |

### Phase 1 TDD Sequence

1. **Red**: Write `MetricsAggregatorTest.testRollingWindowCapacity()` - fails (no implementation)
2. **Green**: Implement `MetricsAggregator` with rolling window
3. **Red**: Write `GPUMetricsCollectorTest.testBeginEndFrame()` - fails
4. **Green**: Implement frame timing in `GPUMetricsCollector`
5. **Refactor**: Extract timing logic to helper methods
6. **Red**: Write `GPUMetricsCollectorTest.testThreadSafeSnapshot()` - fails
7. **Green**: Add synchronization to snapshot access
8. Continue pattern for remaining tests...

---

## Phase 2: BeamMetricsOverlay (REVISED - JavaFX)

**Location**: `portal/src/main/java/com/hellblazer/luciferase/portal/overlay/`

### Files (7)

#### 1. MetricsOverlayController.java
```java
package com.hellblazer.luciferase.portal.overlay;

import com.hellblazer.luciferase.esvo.gpu.beam.metrics.MetricsSnapshot;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.*;

import java.util.function.Supplier;

/**
 * Controller for metrics overlay lifecycle and state management.
 * Bridges render module metrics to JavaFX overlay components.
 */
public class MetricsOverlayController {

    // Observable properties for binding
    private final BooleanProperty visible = new SimpleBooleanProperty(true);
    private final ObjectProperty<MetricsSnapshot> currentSnapshot =
        new SimpleObjectProperty<>(MetricsSnapshot.empty());
    private final IntegerProperty updateIntervalMs = new SimpleIntegerProperty(100);

    private AnimationTimer updateTimer;
    private Supplier<MetricsSnapshot> snapshotSupplier;
    private long lastUpdateNanos = 0;

    public MetricsOverlayController();

    // Configuration
    public void setSnapshotSupplier(Supplier<MetricsSnapshot> supplier);
    public void setUpdateIntervalMs(int intervalMs);

    // Lifecycle
    public void start();
    public void stop();
    public boolean isRunning();

    // Visibility
    public BooleanProperty visibleProperty();
    public void setVisible(boolean visible);
    public boolean isVisible();
    public void toggleVisibility();

    // Metrics access
    public ObjectProperty<MetricsSnapshot> currentSnapshotProperty();
    public MetricsSnapshot getCurrentSnapshot();

    // Manual update (for testing)
    public void updateNow();
}
```

#### 2. CoherenceHeatmapPane.java
```java
package com.hellblazer.luciferase.portal.overlay;

import com.hellblazer.luciferase.esvo.gpu.beam.metrics.CoherenceSnapshot;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * Visualizes coherence metrics with gradient heatmap and pie chart.
 *
 * Gradient: Blue(0.0) -> Green(0.3) -> Yellow(0.6) -> Red(1.0)
 */
public class CoherenceHeatmapPane extends Pane {

    public static final double DEFAULT_WIDTH = 180;
    public static final double DEFAULT_HEIGHT = 100;

    // Color scheme
    public static final Color LOW_COHERENCE = Color.web("#3498db");    // Blue
    public static final Color MED_COHERENCE = Color.web("#2ecc71");    // Green
    public static final Color HIGH_COHERENCE = Color.web("#f1c40f");   // Yellow
    public static final Color MAX_COHERENCE = Color.web("#e74c3c");    // Red

    private final Canvas canvas;
    private final Text titleLabel;
    private final Text valueLabel;
    private final Text trendLabel;

    private CoherenceSnapshot currentSnapshot;
    private double previousCoherence = 0.0;

    public CoherenceHeatmapPane();
    public CoherenceHeatmapPane(double width, double height);

    // Update with new snapshot
    public void update(CoherenceSnapshot snapshot);

    // Rendering
    private void render();
    private void renderGradientBar(GraphicsContext gc);
    private void renderMiniPieChart(GraphicsContext gc);
    private Color getCoherenceColor(double coherence);
    private String getTrendIndicator();
}
```

#### 3. DispatchStatsPane.java
```java
package com.hellblazer.luciferase.portal.overlay;

import com.hellblazer.luciferase.esvo.gpu.beam.metrics.DispatchMetrics;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * Displays batch vs single-ray dispatch statistics.
 * Includes pie chart for visual breakdown.
 */
public class DispatchStatsPane extends VBox {

    public static final double DEFAULT_WIDTH = 160;
    public static final double DEFAULT_HEIGHT = 120;

    // Colors
    public static final Color BATCH_COLOR = Color.web("#27ae60");      // Green (good)
    public static final Color SINGLE_RAY_COLOR = Color.web("#e67e22"); // Orange

    private final Text titleLabel;
    private final Text totalLabel;
    private final Text batchLabel;
    private final Text singleRayLabel;
    private final Text percentageLabel;
    private final Canvas pieChart;

    public DispatchStatsPane();
    public DispatchStatsPane(double width, double height);

    // Update with new metrics
    public void update(DispatchMetrics metrics);

    // Rendering
    private void renderPieChart(DispatchMetrics metrics);
}
```

#### 4. FrameTimePane.java
```java
package com.hellblazer.luciferase.portal.overlay;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Displays FPS and frame time graph.
 * Rolling 60-frame history with min/max/avg indicators.
 */
public class FrameTimePane extends Pane {

    public static final double DEFAULT_WIDTH = 200;
    public static final double DEFAULT_HEIGHT = 80;
    public static final int HISTORY_SIZE = 60;

    // Target line at 60 FPS
    public static final double TARGET_FRAME_TIME_MS = 16.67;

    // Colors
    public static final Color GRAPH_COLOR = Color.web("#3498db");
    public static final Color TARGET_COLOR = Color.web("#2ecc71");
    public static final Color OVER_TARGET_COLOR = Color.web("#e74c3c");

    private final Canvas graphCanvas;
    private final Text fpsLabel;       // Large FPS display
    private final Text minLabel;
    private final Text maxLabel;
    private final Text avgLabel;

    private final Deque<Double> frameTimeHistory;

    public FrameTimePane();
    public FrameTimePane(double width, double height);

    // Update with new frame data
    public void update(double fps, double frameTimeMs);

    // Rendering
    private void renderGraph();
    private void updateLabels(double fps, double min, double max, double avg);
}
```

#### 5. GPUMemoryPane.java
```java
package com.hellblazer.luciferase.portal.overlay;

import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * Displays GPU memory usage as a bar chart.
 * Color changes based on memory pressure.
 */
public class GPUMemoryPane extends Pane {

    public static final double DEFAULT_WIDTH = 160;
    public static final double DEFAULT_HEIGHT = 60;

    // Memory pressure thresholds
    public static final double LOW_PRESSURE_THRESHOLD = 0.5;   // 50%
    public static final double HIGH_PRESSURE_THRESHOLD = 0.8;  // 80%

    // Colors
    public static final Color LOW_PRESSURE_COLOR = Color.web("#2ecc71");    // Green
    public static final Color MEDIUM_PRESSURE_COLOR = Color.web("#f1c40f"); // Yellow
    public static final Color HIGH_PRESSURE_COLOR = Color.web("#e74c3c");   // Red
    public static final Color BAR_BACKGROUND = Color.web("#34495e");

    private final Rectangle barBackground;
    private final Rectangle barFill;
    private final Text titleLabel;
    private final Text usageLabel;
    private final Text detailLabel;

    public GPUMemoryPane();
    public GPUMemoryPane(double width, double height);

    // Update with new memory data
    public void update(long usedBytes, long totalBytes);

    // Formatting
    private String formatBytes(long bytes);
    private Color getPressureColor(double usagePercent);
}
```

#### 6. BeamMetricsOverlay.java
```java
package com.hellblazer.luciferase.portal.overlay;

import com.hellblazer.luciferase.esvo.gpu.beam.metrics.MetricsSnapshot;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

/**
 * Main composite overlay component aggregating all metrics panes.
 * Designed to float on top of 3D viewport without blocking interaction.
 */
public class BeamMetricsOverlay extends StackPane {

    public static final Color BACKGROUND_COLOR = Color.rgb(0, 0, 0, 0.75);
    public static final double CORNER_RADIUS = 8.0;
    public static final Insets DEFAULT_PADDING = new Insets(10);
    public static final double GAP = 8.0;

    private final GridPane contentGrid;
    private final CoherenceHeatmapPane coherencePane;
    private final DispatchStatsPane dispatchPane;
    private final FrameTimePane frameTimePane;
    private final GPUMemoryPane memoryPane;

    private OverlayPosition position = OverlayPosition.TOP_LEFT;

    public BeamMetricsOverlay();

    // Configuration
    public void setPosition(OverlayPosition position);
    public OverlayPosition getPosition();

    // Update with snapshot
    public void update(MetricsSnapshot snapshot);

    // Sub-pane access (for testing)
    public CoherenceHeatmapPane getCoherencePane();
    public DispatchStatsPane getDispatchPane();
    public FrameTimePane getFrameTimePane();
    public GPUMemoryPane getMemoryPane();

    // Layout
    private void setupLayout();
    private void applyPosition();
}
```

#### 7. OverlayPosition.java
```java
package com.hellblazer.luciferase.portal.overlay;

import javafx.geometry.Pos;

/**
 * Position options for overlay placement.
 */
public enum OverlayPosition {
    TOP_LEFT(Pos.TOP_LEFT),
    TOP_RIGHT(Pos.TOP_RIGHT),
    BOTTOM_LEFT(Pos.BOTTOM_LEFT),
    BOTTOM_RIGHT(Pos.BOTTOM_RIGHT),
    CENTER(Pos.CENTER);

    private final Pos alignment;

    OverlayPosition(Pos alignment) {
        this.alignment = alignment;
    }

    public Pos getAlignment() {
        return alignment;
    }
}
```

### Phase 2 Test Files

**Location**: `portal/src/test/java/com/hellblazer/luciferase/portal/overlay/`

| Test File | Tests |
|-----------|-------|
| CoherenceHeatmapPaneTest.java | Gradient color calculation, update propagation, trend indicator |
| DispatchStatsPaneTest.java | Stats display values, pie chart proportions |
| FrameTimePaneTest.java | History rolling, graph rendering, FPS calculation |
| GPUMemoryPaneTest.java | Bar width calculation, pressure color thresholds |
| BeamMetricsOverlayTest.java | Composite update, positioning, mouse transparency |
| MetricsOverlayControllerTest.java | Start/stop lifecycle, visibility toggle, update throttling |

### Phase 2 TDD Sequence

```
Day 1: Core Panes
-----------------
1. Red:   CoherenceHeatmapPaneTest.testCoherenceColorGradient()
2. Green: Implement getCoherenceColor() in CoherenceHeatmapPane
3. Red:   CoherenceHeatmapPaneTest.testUpdatePropagation()
4. Green: Implement update() method
5. Refactor: Extract gradient constants

6. Red:   DispatchStatsPaneTest.testPercentageDisplay()
7. Green: Implement DispatchStatsPane with Text nodes
8. Red:   DispatchStatsPaneTest.testPieChartProportions()
9. Green: Implement pie chart rendering

10. Red:   FrameTimePaneTest.testRollingHistory()
11. Green: Implement frame time history Deque
12. Red:   FrameTimePaneTest.testFpsCalculation()
13. Green: Implement FPS display logic

Day 2: Memory, Composite, Controller
------------------------------------
14. Red:   GPUMemoryPaneTest.testPressureColorThresholds()
15. Green: Implement pressure color logic
16. Red:   GPUMemoryPaneTest.testBarWidthCalculation()
17. Green: Implement bar chart rendering

18. Red:   BeamMetricsOverlayTest.testCompositeUpdate()
19. Green: Wire all panes together in BeamMetricsOverlay
20. Red:   BeamMetricsOverlayTest.testMouseTransparency()
21. Green: setMouseTransparent(true) on construction

22. Red:   MetricsOverlayControllerTest.testStartStop()
23. Green: Implement AnimationTimer lifecycle
24. Red:   MetricsOverlayControllerTest.testUpdateThrottling()
25. Green: Implement interval-based update throttling
```

### Phase 2 JavaFX Test Pattern

```java
package com.hellblazer.luciferase.portal.overlay;

import com.hellblazer.luciferase.esvo.gpu.beam.metrics.CoherenceSnapshot;
import com.hellblazer.luciferase.portal.JavaFXTestBase;
import com.hellblazer.luciferase.portal.mesh.explorer.RequiresJavaFX;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@RequiresJavaFX
class CoherenceHeatmapPaneTest extends JavaFXTestBase {

    private CoherenceHeatmapPane pane;

    @BeforeEach
    void setUp() throws Exception {
        runOnFxThreadAndWait(() -> {
            pane = new CoherenceHeatmapPane();
        });
    }

    @Test
    void testCoherenceColorGradient() throws Exception {
        runOnFxThreadAndWait(() -> {
            // Low coherence = Blue
            assertEquals(
                CoherenceHeatmapPane.LOW_COHERENCE,
                pane.getCoherenceColor(0.0)
            );

            // Mid coherence = Green
            assertEquals(
                CoherenceHeatmapPane.MED_COHERENCE,
                pane.getCoherenceColor(0.3)
            );

            // High coherence = Red
            assertEquals(
                CoherenceHeatmapPane.MAX_COHERENCE,
                pane.getCoherenceColor(1.0)
            );
        });
    }

    @Test
    void testUpdatePropagation() throws Exception {
        var snapshot = new CoherenceSnapshot(0.65, 0.2, 0.9, 100, 5);

        runOnFxThreadAndWait(() -> {
            pane.update(snapshot);
        });

        // Verify pane state updated
        runOnFxThreadAndWait(() -> {
            assertEquals(snapshot, pane.getCurrentSnapshot());
        });
    }
}
```

---

## Phase 3: Integration (APPROVED)

**Location**: `portal/src/main/java/com/hellblazer/luciferase/portal/`

### Modified Files

#### 1. OctreeInspectorApp.java (Modifications)

```java
// Add imports
import com.hellblazer.luciferase.esvo.gpu.beam.metrics.GPUMetricsCollector;
import com.hellblazer.luciferase.portal.overlay.BeamMetricsOverlay;
import com.hellblazer.luciferase.portal.overlay.MetricsOverlayController;

public class OctreeInspectorApp extends SpatialInspectorApp<ESVOOctreeData, ESVOBridge> {

    // Add fields
    private BeamMetricsOverlay metricsOverlay;
    private MetricsOverlayController metricsController;
    private GPUMetricsCollector metricsCollector;

    @Override
    protected void initializeComponents() {
        super.initializeComponents();

        // Initialize metrics overlay
        metricsCollector = new GPUMetricsCollector();
        metricsOverlay = new BeamMetricsOverlay();
        metricsController = new MetricsOverlayController();
        metricsController.setSnapshotSupplier(metricsCollector::getSnapshot);
    }

    @Override
    protected void setupSceneGraph() {
        super.setupSceneGraph();

        // Add metrics overlay on top of 3D viewport
        var viewportStack = new StackPane(cameraView, metricsOverlay);
        StackPane.setAlignment(metricsOverlay, metricsOverlay.getPosition().getAlignment());
        root.setCenter(viewportStack);

        // Wire controller to overlay
        metricsController.currentSnapshotProperty().addListener((obs, old, snapshot) -> {
            metricsOverlay.update(snapshot);
        });
    }

    @Override
    protected VBox createDataSpecificControls() {
        var box = super.createDataSpecificControls();

        // Add metrics overlay toggle
        var metricsCheck = new CheckBox("Show Beam Metrics (M)");
        metricsCheck.setSelected(true);
        metricsCheck.selectedProperty().bindBidirectional(
            metricsController.visibleProperty()
        );
        metricsOverlay.visibleProperty().bind(metricsController.visibleProperty());

        box.getChildren().add(metricsCheck);
        return box;
    }

    @Override
    protected void setupKeyboardShortcuts(Scene scene) {
        super.setupKeyboardShortcuts(scene);

        // Add 'M' key for metrics toggle
        scene.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.M) {
                metricsController.toggleVisibility();
            }
        });
    }

    @Override
    protected void enableGPUMode() {
        super.enableGPUMode();

        // Start metrics collection
        metricsController.start();
    }

    @Override
    protected void disableGPUMode() {
        super.disableGPUMode();

        // Stop metrics collection
        metricsController.stop();
    }

    // In GPU render loop, record metrics
    private void startGpuRenderTimer() {
        // ... existing code ...

        gpuRenderTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // Record frame start
                metricsCollector.beginFrame();

                // ... existing render code ...

                // Record BeamTree statistics if available
                if (beamTree != null) {
                    metricsCollector.recordBeamTreeStats(beamTree);
                }

                // Record kernel selection
                if (kernelSelector != null) {
                    metricsCollector.recordKernelSelection(
                        kernelSelector.getMetrics()
                    );
                }

                // Record frame end
                metricsCollector.endFrame();
            }
        };
    }

    @Override
    protected void shutdown() {
        metricsController.stop();
        super.shutdown();
    }
}
```

#### 2. ESVTInspectorApp.java (Similar modifications)

Apply same integration pattern as OctreeInspectorApp.

### Phase 3 Test Files

**Location**: `portal/src/test/java/com/hellblazer/luciferase/portal/overlay/`

#### BeamMetricsIntegrationTest.java
```java
package com.hellblazer.luciferase.portal.overlay;

import com.hellblazer.luciferase.esvo.gpu.beam.metrics.*;
import com.hellblazer.luciferase.portal.JavaFXTestBase;
import com.hellblazer.luciferase.portal.mesh.explorer.RequiresJavaFX;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@RequiresJavaFX
class BeamMetricsIntegrationTest extends JavaFXTestBase {

    @Test
    void testEndToEndMetricsFlow() throws Exception {
        var collector = new GPUMetricsCollector();
        var overlay = new BeamMetricsOverlay();
        var controller = new MetricsOverlayController();

        // Wire up
        controller.setSnapshotSupplier(collector::getSnapshot);

        var updateLatch = new CountDownLatch(1);

        runOnFxThreadAndWait(() -> {
            controller.currentSnapshotProperty().addListener((obs, old, snapshot) -> {
                overlay.update(snapshot);
                updateLatch.countDown();
            });
            controller.start();
        });

        // Simulate frame with metrics
        collector.beginFrame();
        collector.recordKernelTiming("batch", 0, 1_000_000);
        collector.endFrame();

        // Force update
        runOnFxThreadAndWait(() -> controller.updateNow());

        // Verify overlay received metrics
        assertTrue(updateLatch.await(1, TimeUnit.SECONDS));

        runOnFxThreadAndWait(() -> {
            assertNotNull(overlay.getFrameTimePane());
            controller.stop();
        });
    }

    @Test
    void testOverlayDoesNotBlockViewportInteraction() throws Exception {
        var overlay = new BeamMetricsOverlay();

        runOnFxThreadAndWait(() -> {
            assertTrue(overlay.isMouseTransparent());
        });
    }
}
```

---

## Dependency Graph

```
Phase 1 (render module)
    GPUMetricsCollector
           |
           +-- KernelTimingMetrics
           +-- CoherenceSnapshot
           +-- DispatchMetrics
           +-- MetricsAggregator
                    |
                    v
              MetricsSnapshot
                    |
                    v
Phase 2 (portal module)
    MetricsOverlayController
           |
           v
    BeamMetricsOverlay (composite)
           |
           +-- CoherenceHeatmapPane
           +-- DispatchStatsPane
           +-- FrameTimePane
           +-- GPUMemoryPane
           +-- OverlayPosition
                    |
                    v
Phase 3 (portal module)
    OctreeInspectorApp (integration)
    ESVTInspectorApp (integration)
```

---

## JavaFX-Specific Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| **Threading**: All UI updates must be on FX thread | Use `Platform.runLater()` in controller; AnimationTimer already runs on FX thread |
| **Layout Performance**: Complex Canvas redraws could cause stutter | Throttle updates to 10-30 FPS; use dirty flag to skip redundant redraws |
| **Z-Order**: Overlay must stay on top of 3D content | Use StackPane ordering; `setMouseTransparent(true)` allows clicks through |
| **CI Environment**: JavaFX tests may fail in headless CI | Use `@RequiresJavaFX` annotation; skip initialization in CI mode |
| **Memory Leaks**: AnimationTimer not stopped | Ensure `controller.stop()` in shutdown; use WeakReference for listeners |

---

## Success Criteria

| Criterion | Validation Method |
|-----------|-------------------|
| Overlay displays without blocking 3D viewport mouse interaction | `BeamMetricsIntegrationTest.testOverlayDoesNotBlockViewportInteraction()` |
| Coherence heatmap shows Blue -> Green -> Yellow -> Red gradient | `CoherenceHeatmapPaneTest.testCoherenceColorGradient()` |
| Frame rate display updates smoothly (no visual stutter) | Manual visual verification + FPS stays > 55 with overlay enabled |
| Batch vs single-ray stats update in real-time | `DispatchStatsPaneTest.testUpdatePropagation()` |
| Memory pane reflects actual GPU buffer allocation | `GPUMemoryPaneTest.testBarWidthCalculation()` |
| Toggle visibility works (keyboard 'M' or checkbox) | `MetricsOverlayControllerTest.testVisibilityToggle()` |
| No performance regression (< 1ms overhead) | Benchmark: overlay update + render < 1ms average |
| All tests pass locally and in CI | CI pipeline green with `@RequiresJavaFX` skips |

---

## File Summary

### Phase 1 (render module) - 6 files
| File | Lines (est.) |
|------|--------------|
| GPUMetricsCollector.java | 120 |
| KernelTimingMetrics.java | 20 |
| CoherenceSnapshot.java | 25 |
| DispatchMetrics.java | 30 |
| MetricsAggregator.java | 100 |
| MetricsSnapshot.java | 40 |

### Phase 2 (portal module) - 7 files
| File | Lines (est.) |
|------|--------------|
| MetricsOverlayController.java | 120 |
| CoherenceHeatmapPane.java | 180 |
| DispatchStatsPane.java | 140 |
| FrameTimePane.java | 160 |
| GPUMemoryPane.java | 100 |
| BeamMetricsOverlay.java | 120 |
| OverlayPosition.java | 25 |

### Phase 3 (portal module) - 2-3 modified files
| File | Changes |
|------|---------|
| OctreeInspectorApp.java | +80 lines |
| ESVTInspectorApp.java | +80 lines |
| SpatialInspectorApp.java | +20 lines (optional) |

### Test Files - 10 files
| Location | File Count |
|----------|------------|
| render/src/test/.../metrics/ | 3 |
| portal/src/test/.../overlay/ | 7 |

**Total**: 15-16 new/modified files + 10 test files

---

## Acceptance Checklist

Before marking Phase 5a.4 complete:

- [ ] All Phase 1 tests pass (render module)
- [ ] All Phase 2 tests pass (portal module, local run)
- [ ] CI tests pass (with `@RequiresJavaFX` skips)
- [ ] Manual verification: overlay displays on OctreeInspectorApp
- [ ] Manual verification: 'M' key toggles overlay
- [ ] Manual verification: mouse clicks through overlay to 3D viewport
- [ ] Manual verification: metrics update during GPU rendering
- [ ] Performance verification: < 1ms overlay overhead
- [ ] Code review completed
- [ ] Documentation updated (CLAUDE.md if needed)

---

## References

- Bead: Luciferase-vmjc (Phase 5a.4)
- Dependency: Luciferase-90qr (Phase 5a.3 - completed)
- Blocker: Luciferase-4i5z (Phase 5a.5)
- Existing Code: `/portal/src/main/java/com/hellblazer/luciferase/portal/inspector/PerformanceOverlay.java`
- Test Pattern: `/portal/src/test/java/com/hellblazer/luciferase/portal/JavaFXTestBase.java`
