# Visualization Framework Guide

## Overview

The Portal module's visualization framework provides a comprehensive system for creating interactive 3D applications using JavaFX. It includes camera controls, scene management, and specialized visualizations for spatial data structures.

## Core Architecture

### Base Classes

#### MagicMirror

The primary base class for 3D applications:

```java

public class MyApp extends MagicMirror {
    @Override
    protected void createScene() {
        // Add 3D content
        Box box = new Box(100, 100, 100);
        box.setMaterial(new PhongMaterial(Color.BLUE));
        root.getChildren().add(box);
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}

```text

Key features:

- Automatic camera setup with mouse controls
- Scene graph management
- Keyboard shortcuts for common operations
- Configurable background and lighting

#### SpatialIndexView

Generic base for visualizing spatial indices:

```java

public class MyTreeView extends SpatialIndexView<MortonKey, UUID, Entity> {
    @Override
    protected Node createNodeVisualization(MortonKey key, SpatialNode<UUID> node) {
        // Create visualization for spatial node
        Box box = new Box(size, size, size);
        box.setMaterial(getLevelMaterial(key.level()));
        return box;
    }
    
    @Override
    protected Node createEntityVisualization(UUID id, Entity entity) {
        // Create visualization for entity
        Sphere sphere = new Sphere(entitySize);
        sphere.setTranslateX(entity.getX());
        sphere.setTranslateY(entity.getY());
        sphere.setTranslateZ(entity.getZ());
        return sphere;
    }
}

```text

## Camera System

### Camera Controls

The framework provides multiple camera control systems:

#### Mouse Controls (MagicMirror)

- **Left Drag**: Rotate camera around scene
- **Right Drag**: Zoom in/out
- **Middle Drag**: Pan camera
- **Scroll**: Zoom with modifiers (Ctrl/Shift for speed)

#### First-Person Controls (CameraView)

```java

CameraView cameraView = new CameraView(scene);
cameraView.setPosition(0, 10, 50);
cameraView.lookAt(0, 0, 0);

// Enable WASD movement
scene.setOnKeyPressed(event -> {
    switch (event.getCode()) {
        case W: cameraView.moveForward(1.0); break;
        case S: cameraView.moveBackward(1.0); break;
        case A: cameraView.strafeLeft(1.0); break;
        case D: cameraView.strafeRight(1.0); break;
    }
});

```text

### Camera Boom

Advanced camera manipulation:

```java

CameraBoom boom = new CameraBoom();
boom.setDistance(100);
boom.setElevation(30); // degrees
boom.setAzimuth(45);   // degrees

// Animate camera
Timeline timeline = new Timeline(
    new KeyFrame(Duration.seconds(10), 
        new KeyValue(boom.azimuthProperty(), 360))
);
timeline.setCycleCount(Timeline.INDEFINITE);
timeline.play();

```text

## Transform System

### OrientedGroup and OrientedTxfm

Composable transform chains:

```java

OrientedGroup group = new OrientedGroup();
group.addTxfm(new Translate(10, 0, 0));
group.addTxfm(new Rotate(45, Rotate.Y_AXIS));
group.addTxfm(new Scale(2, 2, 2));

// Apply transforms in order
group.getChildren().add(myNode);

```text

### Transform Utilities

```java

// Xform utility class
Xform xform = new Xform();
xform.setTx(10);
xform.setRy(45);
xform.setScale(2);

// AutoScalingGroup
AutoScalingGroup autoScale = new AutoScalingGroup();
autoScale.setTargetSize(100);
autoScale.getChildren().add(variableSizeContent);

```text

## Grid Systems

### CubicGrid

Traditional 3D grid visualization:

```java

CubicGrid grid = new CubicGrid(100, 10); // size, divisions
grid.setAxisColor(Color.RED, Color.GREEN, Color.BLUE);
grid.showGrid(true);
grid.showAxes(true);
scene.getChildren().add(grid);

```text

### Tetrahedral Grid

Based on rhombic dodecahedron coordinates:

```java

Tetrahedral tetGrid = new Tetrahedral();
tetGrid.setScale(50);
tetGrid.showVertices(true);
tetGrid.showEdges(true);
tetGrid.showFaces(false);
scene.getChildren().add(tetGrid);

```text

## Visualization Properties

### Reactive Configuration

All visualization components use JavaFX properties:

```java

SpatialIndexView view = new MyTreeView();

// Configure with properties
view.showEmptyNodes.set(false);
view.showEntityPositions.set(true);
view.nodeOpacity.set(0.3);
view.entitySize.set(2.0);
view.minLevel.set(0);
view.maxLevel.set(10);

// Bind to UI controls
CheckBox showNodesCheck = new CheckBox("Show Nodes");
view.showNodes.bind(showNodesCheck.selectedProperty());

Slider opacitySlider = new Slider(0, 1, 0.3);
view.nodeOpacity.bind(opacitySlider.valueProperty());

```text

### Level-Based Coloring

```java

// Built-in gradient: blue (level 0) to red (level 20)
Material material = view.getLevelMaterial(level);

// Custom color mapping
view.setLevelColorMapper(level -> {
    double hue = 240 - (level * 12); // Blue to red
    return Color.hsb(hue, 1.0, 1.0);
});

```text

## Scene Organization

### Scene Graph Structure

```java

Group sceneRoot = new Group();

// Organized groups with view order
Group backgroundGroup = new Group();
backgroundGroup.setViewOrder(1.0); // Rendered first

Group nodeGroup = new Group();
nodeGroup.setViewOrder(0.5);

Group entityGroup = new Group(); 
entityGroup.setViewOrder(0.0); // Rendered last (on top)

Group overlayGroup = new Group();
overlayGroup.setViewOrder(-1.0); // Always on top

sceneRoot.getChildren().addAll(
    backgroundGroup, nodeGroup, entityGroup, overlayGroup
);

```text

### Lighting

```java

// Ambient light for overall illumination
AmbientLight ambient = new AmbientLight(Color.gray(0.3));

// Directional light for shading
DirectionalLight sun = new DirectionalLight(Color.WHITE);
sun.setDirection(new Point3D(-1, -1, -1));

// Point light for local illumination
PointLight point = new PointLight(Color.YELLOW);
point.setTranslateX(50);
point.setTranslateY(50);

scene.getChildren().addAll(ambient, sun, point);

```text

## Interactive Features

### Selection System

```java

view.setOnEntityClicked((id, entity, event) -> {
    if (event.isControlDown()) {
        // Multi-select
        view.toggleEntitySelection(id);
    } else {
        // Single select
        view.selectEntity(id);
    }
});

// React to selection changes
view.getSelectedEntities().addListener(
    (ListChangeListener<UUID>) change -> {
        System.out.println("Selected: " + view.getSelectedEntities());
    }
);

```text

### Highlighting

```java

// Highlight on hover
view.setOnEntityHovered((id, entity, entered) -> {
    if (entered) {
        view.highlightEntity(id, Color.YELLOW);
    } else {
        view.unhighlightEntity(id);
    }
});

```text

## Performance Optimization

### Visibility Culling

```java

// Only render nodes within level range
view.minLevel.set(5);
view.maxLevel.set(10);

// Distance-based culling
view.setCullDistance(1000);
view.setFarClip(2000);

```text

### Lazy Updates

```java

// Batch updates
view.beginUpdate();
try {
    // Multiple modifications
    for (Entity e : entities) {
        view.updateEntity(e);
    }
} finally {
    view.endUpdate(); // Single render update
}

```text

### Level of Detail

```java

// Adjust detail based on camera distance
cameraDistanceProperty.addListener((obs, old, dist) -> {
    if (dist.doubleValue() > 500) {
        view.setEntityLOD(LOD.LOW);
    } else if (dist.doubleValue() > 200) {
        view.setEntityLOD(LOD.MEDIUM);
    } else {
        view.setEntityLOD(LOD.HIGH);
    }
});

```text

## Common Patterns

### Custom Overlays

```java

// Add 2D overlay on 3D scene
VBox overlay = new VBox(10);
overlay.setAlignment(Pos.TOP_LEFT);
overlay.setPadding(new Insets(10));

Label info = new Label("Entities: 0");
CheckBox showGrid = new CheckBox("Show Grid");
Slider zoom = new Slider(0.1, 10, 1);

overlay.getChildren().addAll(info, showGrid, zoom);

// Add as 2D overlay
SubScene subScene = view.getSubScene();
StackPane stack = new StackPane(subScene, overlay);

```text

### Animation

```java

// Animate spatial index updates
Timeline updateTimeline = new Timeline(
    new KeyFrame(Duration.millis(100), e -> {
        view.updateFromSpatialIndex();
    })
);
updateTimeline.setCycleCount(Timeline.INDEFINITE);
updateTimeline.play();

// Smooth camera transitions
TranslateTransition pan = new TranslateTransition(
    Duration.seconds(2), camera
);
pan.setToX(100);
pan.setToY(50);
pan.setToZ(200);
pan.play();

```text

## Best Practices

1. **Use Properties**: Leverage JavaFX properties for reactive updates
2. **Organize Scene Graph**: Use groups and view order for proper layering
3. **Optimize Rendering**: Implement culling and LOD for large scenes
4. **Handle Events Efficiently**: Use event filters for performance
5. **Batch Updates**: Group modifications to minimize render calls
6. **Profile Performance**: Monitor frame rate and adjust quality settings
