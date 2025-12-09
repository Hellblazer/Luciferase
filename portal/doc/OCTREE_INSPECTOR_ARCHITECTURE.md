# ESVO Octree Inspector - Architecture Design

**Task ID**: Luciferase-q65  
**Created**: 2025-12-09  
**Status**: Phase 1.1 Complete

## Executive Summary

This document defines the architecture for the ESVO Octree Inspector, a comprehensive JavaFX-based interactive visualization tool for exploring ESVO octree structures. The design follows established patterns from the TetreeInspector while integrating ESVO-specific components from the render module.

## Architecture Overview

### Component Hierarchy

```
OctreeInspectorApp
├── JavaFX Application Layer (extends Application)
│   ├── Stage (primary window)
│   ├── Scene (main scene)
│   └── BorderPane (root layout)
│       ├── Center: SubScene (3D visualization)
│       ├── Right: ControlPanel (UI controls)
│       └── Overlay: PerformanceOverlay (metrics)
│
├── 3D Scene Graph
│   ├── worldGroup (root 3D group)
│   │   ├── axisGroup (coordinate axes)
│   │   ├── gridGroup (adaptive grid)
│   │   ├── octreeGroup (octree visualization)
│   │   ├── rayGroup (ray casting visualization)
│   │   └── voxelGroup (voxel rendering)
│   └── Camera System
│       └── CameraView (portal.CameraView)
│
├── Data Pipeline
│   ├── ProceduralVoxelGenerator → VoxelData
│   ├── ESVOCPUBuilder → ESVOOctreeData
│   └── OctreeRenderer → JavaFX Geometry
│
└── Control Systems
    ├── InteractionController (mouse/keyboard)
    ├── AnimationController (camera paths)
    └── RebuildController (dynamic updates)
```

### Module Integration Map

```
┌─────────────────────────────────────────────────────────────┐
│                    PORTAL MODULE                             │
│  ┌────────────────────────────────────────────────────┐    │
│  │         OctreeInspectorApp                          │    │
│  │  (extends javafx.application.Application)           │    │
│  └───────────────┬────────────────────────────────────┘    │
│                  │                                           │
│  ┌───────────────▼──────────────┐  ┌──────────────────┐   │
│  │    UI Components              │  │  3D Rendering    │   │
│  │  - ControlPanel               │  │  - CameraView    │   │
│  │  - PerformanceOverlay         │  │  - OctreeRenderer│   │
│  │  - HelpOverlay                │  │  - RayVisualizer │   │
│  └───────────────┬───────────────┘  └────────┬─────────┘   │
│                  │                            │              │
│                  │         ┌──────────────────▼──────┐      │
│                  │         │  Visualization Utils     │      │
│                  │         │  - AdaptiveGrid          │      │
│                  │         │  - ScalingStrategy       │      │
│                  │         └──────────────────────────┘      │
└──────────────────┼────────────────────────────────────────┬─┘
                   │                                        │
┌──────────────────▼────────────────────────────────────┐  │
│                RENDER MODULE                           │  │
│  ┌─────────────────────────────────────────────────┐  │  │
│  │          ESVO Components                        │  │  │
│  │  - ESVOCPUBuilder (octree construction)        │  │  │
│  │  - ESVOOctreeData (data structure)             │  │  │
│  │  - StackBasedRayTraversal (ray casting)        │  │  │
│  │  - ESVOPerformanceMonitor (metrics)            │  │  │
│  │  - ESVONodeGeometry (node bounds)              │  │  │
│  │  - ESVOTopology (parent/child)                 │  │  │
│  └─────────────────────────────────────────────────┘  │  │
└────────────────────────────────────────────────────────┘  │
                                                             │
┌────────────────────────────────────────────────────────────▼┐
│                  COMMON MODULE                               │
│  - FloatArrayList, OaHashSet (optimized collections)        │
│  - Geometry utilities                                        │
└──────────────────────────────────────────────────────────────┘
```

## Component Specifications

### 1. OctreeInspectorApp (Main Application)

**Package**: `com.hellblazer.luciferase.portal.esvo`  
**Pattern**: JavaFX Application with Launcher inner class

```java
public class OctreeInspectorApp extends Application {
    
    // Core components
    private SubScene subScene;
    private CameraView cameraView;
    private ControlPanel controlPanel;
    private PerformanceOverlay performanceOverlay;
    
    // 3D scene groups
    private Group worldGroup;
    private Group axisGroup;
    private Group gridGroup;
    private Group octreeGroup;
    private Group rayGroup;
    private Group voxelGroup;
    
    // Data and state
    private ESVOOctreeData octreeData;
    private ProceduralVoxelGenerator voxelGenerator;
    private OctreeRenderer octreeRenderer;
    private RayVisualizer rayVisualizer;
    
    // Controllers
    private InteractionController interactionController;
    private AnimationController animationController;
    
    @Override
    public void start(Stage primaryStage) {
        // Setup layout and components
    }
    
    // Lifecycle methods
    private void buildScene() { }
    private void buildControls() { }
    private void setupEventHandlers() { }
    private void startRenderLoop() { }
    
    public static class Launcher {
        public static void main(String[] args) {
            Application.launch(OctreeInspectorApp.class, args);
        }
    }
}
```

**Key Design Decisions**:
- Extends `Application` directly (not Abstract3DApp) for more control
- Uses CameraView from portal module for camera management
- Follows TetreeInspector pattern for proven architecture
- Launcher inner class for IDE-friendly execution

### 2. ProceduralVoxelGenerator

**Package**: `com.hellblazer.luciferase.portal.esvo.generator`  
**Purpose**: Generate demo geometry for octree visualization

```java
public class ProceduralVoxelGenerator {
    
    public enum ShapeType {
        SPHERE,
        CUBE,
        TORUS,
        MENGER_SPONGE,
        SIERPINSKI_PYRAMID
    }
    
    /**
     * Generate voxel data for the specified shape.
     * 
     * @param shape Shape type to generate
     * @param resolution Grid resolution (8-256)
     * @param params Shape-specific parameters
     * @return List of voxel coordinates
     */
    public List<Point3i> generate(ShapeType shape, int resolution, 
                                   Map<String, Object> params);
    
    // Shape-specific generators
    private List<Point3i> generateSphere(int resolution, float radius, 
                                         boolean hollow);
    private List<Point3i> generateCube(int resolution, float size);
    private List<Point3i> generateTorus(int resolution, float majorRadius, 
                                        float minorRadius);
    private List<Point3i> generateMengerSponge(int resolution, int iterations);
    private List<Point3i> generateSierpinskiPyramid(int resolution, 
                                                    int iterations);
}
```

**Design Rationale**:
- Enum-based shape selection for type safety
- Configurable resolution for performance testing
- Returns voxel coordinates (not boolean array) for memory efficiency
- Extensible design for adding new shapes

### 3. ESVO Integration Layer

**Package**: `com.hellblazer.luciferase.portal.esvo.bridge`  
**Purpose**: Bridge between portal (JavaFX) and render (ESVO) modules

```java
public class ESVOBridge {
    
    /**
     * Build ESVO octree from voxel data.
     * 
     * @param voxels List of voxel coordinates
     * @param maxDepth Maximum octree depth (1-15)
     * @return ESVO octree data structure
     */
    public ESVOOctreeData buildOctree(List<Point3i> voxels, int maxDepth) {
        // Use ESVOCPUBuilder from render module
        var builder = new ESVOCPUBuilder(maxDepth);
        
        // Convert voxels to builder input format
        for (Point3i voxel : voxels) {
            builder.addVoxel(voxel.x, voxel.y, voxel.z);
        }
        
        // Build and return octree
        return builder.build();
    }
    
    /**
     * Cast ray through octree and return traversal data.
     */
    public RayTraversalResult castRay(ESVOOctreeData octree, 
                                      Ray3D ray) {
        var traversal = new StackBasedRayTraversal(octree);
        return traversal.traverse(ray.origin, ray.direction);
    }
    
    /**
     * Get performance metrics from ESVO monitor.
     */
    public ESVOMetrics getMetrics() {
        return ESVOPerformanceMonitor.getInstance().getMetrics();
    }
}
```

**Integration Points**:
1. **ESVOCPUBuilder**: Octree construction
2. **ESVOOctreeData**: Core data structure
3. **StackBasedRayTraversal**: Ray casting
4. **ESVOPerformanceMonitor**: Metrics collection
5. **ESVONodeGeometry**: Node bounds calculation
6. **ESVOTopology**: Parent/child relationships

### 4. OctreeRenderer

**Package**: `com.hellblazer.luciferase.portal.esvo.renderer`  
**Purpose**: Visualize octree nodes as JavaFX geometry

```java
public class OctreeRenderer {
    
    /**
     * Rendering strategy from Phase 0.4 prototype.
     */
    public enum Strategy {
        INSTANCING,  // Individual Box shapes
        BATCHED,     // Single merged TriangleMesh (recommended)
        HYBRID       // Reference mesh with MeshView instances
    }
    
    private final Strategy strategy;
    private final int maxDepth;
    private final ColorScheme colorScheme;
    
    /**
     * Render octree nodes to JavaFX Group.
     * 
     * @param octreeData ESVO octree data
     * @param levelRange Range of levels to visualize [min, max]
     * @param wireframeOnly True for wireframe, false for solid
     * @return Group containing rendered geometry
     */
    public Group render(ESVOOctreeData octreeData, 
                       int[] levelRange,
                       boolean wireframeOnly) {
        var nodes = collectVisibleNodes(octreeData, levelRange);
        return switch (strategy) {
            case BATCHED -> renderBatched(nodes, wireframeOnly);
            case INSTANCING -> renderInstanced(nodes, wireframeOnly);
            case HYBRID -> renderHybrid(nodes, wireframeOnly);
        };
    }
    
    private List<NodeInfo> collectVisibleNodes(ESVOOctreeData octree, 
                                               int[] levelRange) {
        // Use ESVONodeGeometry.getNodeBounds() for each node
        // Use ESVOTopology.getChildren() for traversal
    }
    
    /**
     * Color schemes for octree visualization.
     */
    public enum ColorScheme {
        DEPTH_GRADIENT,  // Red (root) → Blue (leaves)
        RANDOM,          // Random color per node
        SINGLE_COLOR,    // Uniform color
        LEVEL_BANDS      // Distinct color per level
    }
}
```

**Rendering Strategy**:
- Use **BATCHED** strategy by default (30-40% faster from Phase 0.4 analysis)
- Leverage `OctreeNodeMeshRenderer` from Phase 0.4 prototype
- Use `ESVONodeGeometry.getNodeBounds()` for accurate node positioning
- Implement frustum culling for deep octrees (10K+ nodes)

### 5. RayVisualizer

**Package**: `com.hellblazer.luciferase.portal.esvo.renderer`  
**Purpose**: Visualize ray casting and traversal

```java
public class RayVisualizer {
    
    /**
     * Visualize a ray and its traversal through the octree.
     * 
     * @param ray Ray to visualize
     * @param traversalResult Result from StackBasedRayTraversal
     * @return Group containing ray geometry and visited nodes
     */
    public Group visualizeRay(Ray3D ray, 
                             RayTraversalResult traversalResult) {
        var group = new Group();
        
        // Add ray line (cylinder or line strip)
        group.getChildren().add(createRayLine(ray));
        
        // Highlight visited nodes
        for (int nodeIndex : traversalResult.visitedNodes()) {
            var nodeBounds = ESVONodeGeometry.getNodeBounds(nodeIndex, maxDepth);
            group.getChildren().add(createHighlightBox(nodeBounds));
        }
        
        // Add hit point marker if ray hit
        if (traversalResult.hit()) {
            group.getChildren().add(createHitMarker(traversalResult.hitPoint()));
        }
        
        return group;
    }
    
    /**
     * Create statistics overlay for ray traversal.
     */
    public Text createStatisticsText(RayTraversalResult result) {
        return new Text(String.format("""
            Ray Statistics:
            - Hit: %s
            - Distance: %.2f
            - Nodes Visited: %d
            - Traversal Time: %.3f μs
            """, 
            result.hit(), 
            result.distance(),
            result.visitedNodes().size(),
            result.traversalTimeNanos() / 1000.0
        ));
    }
}
```

### 6. ControlPanel

**Package**: `com.hellblazer.luciferase.portal.esvo.ui`  
**Purpose**: UI controls for interactive manipulation

```java
public class ControlPanel extends VBox {
    
    // Control groups
    private TitledPane octreeControls;
    private TitledPane renderingControls;
    private TitledPane visualizationControls;
    private TitledPane cameraControls;
    
    // Main controls
    private Slider depthSlider;          // 1-15
    private Slider resolutionSlider;     // 8-256
    private ComboBox<ShapeType> shapeSelector;
    private Slider levelMinSlider;       // Min visible level
    private Slider levelMaxSlider;       // Max visible level
    
    // Checkboxes
    private CheckBox showAxes;
    private CheckBox showGrid;
    private CheckBox showOctree;
    private CheckBox showVoxels;
    private CheckBox showRays;
    private CheckBox wireframeMode;
    
    // Event handlers
    private Consumer<Integer> onDepthChanged;
    private Consumer<Integer> onResolutionChanged;
    private Consumer<ShapeType> onShapeChanged;
    private Consumer<int[]> onLevelRangeChanged;
    private Runnable onResetCamera;
    
    public ControlPanel() {
        super(10); // 10px spacing
        buildControls();
        setupListeners();
    }
    
    private void buildControls() {
        // Create organized control groups
        octreeControls = createOctreeControls();
        renderingControls = createRenderingControls();
        visualizationControls = createVisualizationControls();
        cameraControls = createCameraControls();
        
        getChildren().addAll(
            octreeControls,
            renderingControls,
            visualizationControls,
            cameraControls
        );
        
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #f4f4f4;");
    }
}
```

**UI Layout**:
```
┌─────────────────────────────┐
│ Octree Parameters           │
│  Depth: [====●=====] 10     │
│  Resolution: [==●======] 64 │
│  Shape: [Sphere ▼]          │
├─────────────────────────────┤
│ Rendering                   │
│  ☑ Wireframe Mode           │
│  Strategy: [Batched ▼]      │
│  Color: [Depth Gradient ▼]  │
├─────────────────────────────┤
│ Visualization               │
│  ☑ Show Axes                │
│  ☑ Show Grid                │
│  ☑ Show Octree              │
│  ☑ Show Voxels              │
│  ☐ Show Rays                │
│  Level Range: [2] to [10]   │
├─────────────────────────────┤
│ Camera                      │
│  [Reset Camera]             │
│  ☐ Animation                │
│  Speed: [====●===] 1.0      │
└─────────────────────────────┘
```

### 7. CameraView Integration

**Existing Component**: `com.hellblazer.luciferase.portal.CameraView`  
**Usage Pattern** (from TetreeInspector):

```java
// Create SubScene for 3D content
SubScene subScene = new SubScene(worldGroup, 800, 600, true, 
                                 SceneAntialiasing.BALANCED);
subScene.setFill(Color.LIGHTGRAY);

// Create and configure CameraView
cameraView = new CameraView(subScene);
cameraView.setFirstPersonNavigationEabled(true);
cameraView.startViewing();

// Bind size to window
cameraView.fitWidthProperty().bind(
    root.widthProperty().subtract(CONTROL_PANEL_WIDTH)
);
cameraView.fitHeightProperty().bind(root.heightProperty());

// Add to layout
root.setCenter(cameraView);
```

**Benefits**:
- Proven camera control system
- Mouse + keyboard navigation
- First-person mode support
- Smooth camera interpolation
- Reset camera functionality

### 8. AdaptiveGrid Integration

**Existing Component**: `com.hellblazer.luciferase.portal.mesh.explorer.grid.AdaptiveGrid`  
**Usage Pattern** (from TetreeInspector):

```java
// Initialize scaling strategy and adaptive grid
scalingStrategy = new ScalingStrategy();
adaptiveGrid = new AdaptiveGrid(scalingStrategy);

// Create grid materials
PhongMaterial xMaterial = new PhongMaterial(Color.RED.darker());
PhongMaterial yMaterial = new PhongMaterial(Color.GREEN.darker());
PhongMaterial zMaterial = new PhongMaterial(Color.BLUE.darker());

// Build grid for specific level
Group grid = adaptiveGrid.constructForLevel(
    level, 
    xMaterial, 
    yMaterial, 
    zMaterial, 
    null  // viewFrustum (optional)
);
gridGroup.getChildren().add(grid);
```

**Grid Behavior**:
- Automatically scales to match octree level
- Updates when level changes
- Color-coded axes (X=red, Y=green, Z=blue)
- Optional frustum culling for performance

## Data Flow Architecture

### 1. Initialization Flow

```
Application Start
    ↓
OctreeInspectorApp.start()
    ↓
┌───────────────────────────────────┐
│ 1. Create JavaFX Scene            │
│    - Stage, Scene, BorderPane     │
│    - SubScene for 3D content      │
└──────────────┬────────────────────┘
               ↓
┌───────────────────────────────────┐
│ 2. Initialize 3D Scene Graph      │
│    - worldGroup (root)            │
│    - axisGroup, gridGroup, etc.   │
│    - Add lighting (AmbientLight)  │
└──────────────┬────────────────────┘
               ↓
┌───────────────────────────────────┐
│ 3. Setup Camera System            │
│    - Create CameraView            │
│    - Enable first-person nav      │
│    - Bind to window size          │
└──────────────┬────────────────────┘
               ↓
┌───────────────────────────────────┐
│ 4. Create Control Panel           │
│    - Build UI controls            │
│    - Set default values           │
│    - Wire event handlers          │
└──────────────┬────────────────────┘
               ↓
┌───────────────────────────────────┐
│ 5. Generate Initial Octree        │
│    - ProceduralVoxelGenerator     │
│    - ESVOBridge.buildOctree()     │
│    - Initial rendering            │
└──────────────┬────────────────────┘
               ↓
┌───────────────────────────────────┐
│ 6. Start Render Loop              │
│    - JavaFX AnimationTimer        │
│    - Update performance overlay   │
└───────────────────────────────────┘
```

### 2. Octree Rebuild Flow

```
User Changes Parameter (depth/resolution/shape)
    ↓
ControlPanel fires event
    ↓
┌────────────────────────────────────┐
│ 1. Clear Existing Visualization    │
│    - octreeGroup.getChildren().clear() │
│    - voxelGroup.getChildren().clear()  │
└──────────────┬─────────────────────┘
               ↓
┌────────────────────────────────────┐
│ 2. Show Progress Indicator         │
│    - Disable controls              │
│    - Show "Building..." message    │
└──────────────┬─────────────────────┘
               ↓
┌────────────────────────────────────┐
│ 3. Generate New Voxel Data         │
│    (Background Thread)             │
│    - ProceduralVoxelGenerator      │
│    - New shape with new params     │
└──────────────┬─────────────────────┘
               ↓
┌────────────────────────────────────┐
│ 4. Build ESVO Octree               │
│    (Background Thread)             │
│    - ESVOBridge.buildOctree()      │
│    - Measure build time            │
└──────────────┬─────────────────────┘
               ↓
┌────────────────────────────────────┐
│ 5. Render New Octree               │
│    (JavaFX Application Thread)     │
│    - OctreeRenderer.render()       │
│    - Add to octreeGroup            │
└──────────────┬─────────────────────┘
               ↓
┌────────────────────────────────────┐
│ 6. Update UI                       │
│    - Hide progress indicator       │
│    - Enable controls               │
│    - Update statistics             │
└────────────────────────────────────┘
```

### 3. Ray Casting Flow

```
User Clicks on 3D Scene
    ↓
Mouse Event → 2D Screen Coordinates
    ↓
┌────────────────────────────────────┐
│ 1. Convert to 3D Ray               │
│    - Camera position = ray origin  │
│    - Screen → World transform      │
│    - Normalize direction           │
└──────────────┬─────────────────────┘
               ↓
┌────────────────────────────────────┐
│ 2. Cast Ray Through Octree         │
│    - ESVOBridge.castRay()          │
│    - StackBasedRayTraversal        │
│    - Collect visited nodes         │
└──────────────┬─────────────────────┘
               ↓
┌────────────────────────────────────┐
│ 3. Visualize Ray Traversal         │
│    - RayVisualizer.visualizeRay()  │
│    - Create ray line geometry      │
│    - Highlight visited nodes       │
└──────────────┬─────────────────────┘
               ↓
┌────────────────────────────────────┐
│ 4. Display Statistics              │
│    - Hit point                     │
│    - Traversal path                │
│    - Performance metrics           │
└────────────────────────────────────┘
```

## Event Handling Architecture

### Keyboard Shortcuts

```java
public class InteractionController {
    
    public void setupKeyboardShortcuts(Scene scene) {
        scene.setOnKeyPressed(event -> {
            switch (event.getCode()) {
                // Octree depth (1-9 = levels 1-9, 0 = level 10)
                case DIGIT1 -> setOctreeDepth(1);
                case DIGIT2 -> setOctreeDepth(2);
                // ... through DIGIT9, DIGIT0
                
                // Level range adjustment
                case W -> adjustLevelRange(+1);  // Increase max
                case S -> adjustLevelRange(-1);  // Decrease max
                
                // Camera controls
                case R -> resetCamera();
                case SPACE -> toggleAnimation();
                
                // Visibility toggles
                case X -> toggleAxes();
                case G -> toggleGrid();
                case O -> toggleOctree();
                case V -> toggleVoxels();
                
                // Overlays
                case F1 -> togglePerformanceOverlay();
                case F2 -> toggleControlPanel();
                case H -> toggleHelpOverlay();
                
                // Utilities
                case F11 -> toggleFullscreen();
                case F12 -> captureScreenshot();
                
                default -> { }
            }
        });
    }
}
```

### Mouse Interaction

```java
public class InteractionController {
    
    public void setupMouseHandlers(SubScene subScene) {
        // Ray casting on click
        subScene.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                var ray = createRayFromMouse(event.getX(), event.getY());
                castAndVisualizeRay(ray);
            }
        });
        
        // Continuous ray update on drag
        subScene.setOnMouseDragged(event -> {
            if (event.isControlDown()) {
                var ray = createRayFromMouse(event.getX(), event.getY());
                updateRayVisualization(ray);
            }
        });
    }
    
    private Ray3D createRayFromMouse(double screenX, double screenY) {
        // Convert 2D screen coords to 3D ray
        // Use camera view matrix and projection
    }
}
```

## Performance Considerations

### 1. Rendering Optimization

**Problem**: Deep octrees (15 levels) can have 100K+ nodes  
**Solutions**:
- Use **BATCHED** rendering strategy (single TriangleMesh)
- Implement frustum culling (don't render off-screen nodes)
- Level-of-detail: Show only visible level range
- Object pooling for geometry reuse

```java
public class OctreeRenderer {
    
    private final ObjectPool<Box> boxPool;
    private final ObjectPool<TriangleMesh> meshPool;
    
    private List<NodeInfo> cullByFrustum(List<NodeInfo> nodes, 
                                        Camera camera) {
        // Use camera frustum to filter visible nodes
        // Only render nodes within view
    }
    
    private TriangleMesh renderBatchedWithCulling(List<NodeInfo> nodes,
                                                  Camera camera) {
        var visibleNodes = cullByFrustum(nodes, camera);
        return createBatchedMesh(visibleNodes);
    }
}
```

### 2. Background Processing

**Problem**: Octree building can block UI thread  
**Solution**: Use background threads with proper synchronization

```java
public class RebuildController {
    
    private final ExecutorService buildExecutor = 
        Executors.newSingleThreadExecutor();
    
    public CompletableFuture<ESVOOctreeData> rebuildAsync(
            List<Point3i> voxels, int depth) {
        
        return CompletableFuture.supplyAsync(() -> {
            // Build octree on background thread
            return esvo Bridge.buildOctree(voxels, depth);
        }, buildExecutor);
    }
    
    public void onParameterChanged() {
        showProgressIndicator();
        
        rebuildAsync(voxels, depth)
            .thenAcceptAsync(octree -> {
                // Update visualization on JavaFX thread
                Platform.runLater(() -> {
                    updateVisualization(octree);
                    hideProgressIndicator();
                });
            }, buildExecutor);
    }
}
```

### 3. Memory Management

**Problem**: Large octrees consume significant memory  
**Solutions**:
- Reuse geometry objects (pooling)
- Clear unused groups promptly
- Lazy loading for voxel rendering
- Streaming for very large datasets

```java
public class MemoryManager {
    
    public void clearVisualization() {
        // Clear all groups
        octreeGroup.getChildren().clear();
        voxelGroup.getChildren().clear();
        rayGroup.getChildren().clear();
        
        // Suggest GC (hint only)
        System.gc();
    }
    
    public void enableLazyVoxelLoading() {
        // Only load voxels that are visible
        // Stream from disk for very large datasets
    }
}
```

## Testing Strategy

### Unit Tests

```java
// Test voxel generation
public class ProceduralVoxelGeneratorTest {
    @Test
    public void testSphereGeneration() {
        var generator = new ProceduralVoxelGenerator();
        var voxels = generator.generate(ShapeType.SPHERE, 32, 
                                       Map.of("radius", 10.0f));
        
        assertFalse(voxels.isEmpty());
        // Verify voxels form approximate sphere
    }
}

// Test ESVO bridge
public class ESVOBridgeTest {
    @Test
    public void testOctreeBuilding() {
        var voxels = List.of(new Point3i(0, 0, 0), 
                            new Point3i(1, 1, 1));
        var bridge = new ESVOBridge();
        var octree = bridge.buildOctree(voxels, 10);
        
        assertNotNull(octree);
        assertTrue(octree.getNodeCount() > 0);
    }
}
```

### Integration Tests

```java
// Test full pipeline
public class OctreeInspectorIntegrationTest extends JavaFXTestBase {
    
    @Test
    public void testCompleteRenderingPipeline() throws Exception {
        runOnFxThreadAndWait(() -> {
            // Generate voxels
            var generator = new ProceduralVoxelGenerator();
            var voxels = generator.generate(ShapeType.CUBE, 16, Map.of());
            
            // Build octree
            var bridge = new ESVOBridge();
            var octree = bridge.buildOctree(voxels, 8);
            
            // Render
            var renderer = new OctreeRenderer(Strategy.BATCHED, 8, 
                                             ColorScheme.DEPTH_GRADIENT);
            var group = renderer.render(octree, new int[]{0, 8}, false);
            
            assertNotNull(group);
            assertFalse(group.getChildren().isEmpty());
        });
    }
}
```

### Visual Tests

Manual verification checklist:
- [ ] Application launches without errors
- [ ] 3D scene renders with correct perspective
- [ ] Camera controls respond smoothly
- [ ] Octree visualization shows colored nodes
- [ ] Ray casting highlights correct nodes
- [ ] UI controls update visualization
- [ ] Performance overlay shows accurate metrics
- [ ] No visual artifacts or flickering

## File Structure

```
portal/src/main/java/com/hellblazer/luciferase/portal/esvo/
├── OctreeInspectorApp.java              # Main application
├── bridge/
│   └── ESVOBridge.java                  # ESVO integration
├── generator/
│   └── ProceduralVoxelGenerator.java    # Shape generation
├── renderer/
│   ├── OctreeRenderer.java              # Node visualization
│   └── RayVisualizer.java               # Ray path rendering
├── ui/
│   ├── ControlPanel.java                # UI controls
│   ├── PerformanceOverlay.java          # Metrics display
│   └── HelpOverlay.java                 # Keyboard shortcuts
└── controller/
    ├── InteractionController.java       # Input handling
    ├── AnimationController.java         # Camera animation
    └── RebuildController.java           # Dynamic updates

portal/src/test/java/com/hellblazer/luciferase/portal/esvo/
├── OctreeInspectorAppTest.java
├── ProceduralVoxelGeneratorTest.java
├── ESVOBridgeTest.java
├── OctreeRendererTest.java
└── OctreeInspectorIntegrationTest.java

portal/doc/
├── OCTREE_INSPECTOR_ARCHITECTURE.md     # This document
└── ESVO_OCTREE_INSPECTOR_PLAN.md        # Implementation plan
```

## Dependencies Summary

### Direct Dependencies (Existing)
- **portal → render**: For ESVO components
- **portal → common**: For utilities
- **JavaFX 24**: UI framework
- **javax.vecmath**: Vector mathematics

### New Dependencies (Phase 0 Complete)
- ✅ **TestFX 4.0.18**: UI testing (Phase 0.5)
- ✅ **ESVONodeGeometry**: Node bounds (Phase 0.2)
- ✅ **ESVOTopology**: Parent/child relationships (Phase 0.3)
- ✅ **OctreeNodeMeshRenderer**: Rendering strategy (Phase 0.4)
- ✅ **JavaFXTestBase**: Testing infrastructure (Phase 0.5)

## Risk Mitigation

### High-Risk Areas

1. **Performance with Deep Octrees**
   - Risk: 100K+ nodes may cause low FPS
   - Mitigation: BATCHED rendering, frustum culling, LOD
   - Fallback: Limit visible nodes, warn user

2. **Complex UI Interactions**
   - Risk: UI freeze during octree rebuild
   - Mitigation: Background threads, progress indicators
   - Fallback: Disable controls during rebuild

3. **Memory Consumption**
   - Risk: Large octrees consume excessive memory
   - Mitigation: Object pooling, lazy loading, streaming
   - Fallback: Limit octree depth, reduce resolution

### Medium-Risk Areas

1. **Cross-Module Integration**
   - Risk: API mismatches between portal and render
   - Mitigation: Clear interfaces, integration tests
   - Fallback: Adapter pattern for compatibility

2. **Ray Casting Accuracy**
   - Risk: Ray-to-screen conversion errors
   - Mitigation: Test with known hit points
   - Fallback: Visual debugging mode

## Success Criteria

### Phase 1 Completion (This Task)

- [x] Architecture document created
- [x] Component hierarchy defined
- [x] Data flow documented
- [x] Integration points identified
- [x] File structure planned
- [x] Testing strategy outlined
- [ ] Plan reviewed by user
- [ ] Implementation ready to begin

### Overall Project Success

- Application launches reliably
- Octrees up to 15 levels render correctly
- Maintains 60 FPS with 10K visible nodes
- Ray casting shows correct traversal
- UI controls work intuitively
- Performance metrics are accurate
- Documentation is comprehensive

## Next Steps

After this architecture is approved:

1. **Luciferase-otq** (Phase 1.2): Implement ProceduralVoxelGenerator
2. **Luciferase-1lw** (Phase 1.3): Implement ESVOBridge
3. **Luciferase-1os** (Phase 1.4): Create basic JavaFX window

All three can be worked in parallel since they have no mutual dependencies.

---

**Document Version**: 1.0  
**Last Updated**: 2025-12-09  
**Author**: Claude Code (Plan-based Design)  
**Status**: Ready for Review
