# Simple Local Demo (Single Process)

**Purpose**: Baseline single-process animation with 3D visualization

**Last Updated**: 2026-02-10

---

## Overview

The Simple Local Demo demonstrates Luciferase's core spatial indexing and entity management capabilities without network communication. This serves as a baseline for understanding the framework before exploring distributed features.

**Example**: `PredatorPreyGridDemo`
**Location**: `simulation/src/main/java/com/hellblazer/luciferase/simulation/viz/PredatorPreyGridDemo.java`

---

## Running the Demo

### Quick Start

```bash
cd simulation
mvn process-classes exec:java \
  -Dexec.mainClass="com.hellblazer.luciferase.simulation.viz.PredatorPreyGridDemo"
```

**Time**: ~5-10 seconds to start

**Note**: `process-classes` phase is **required** for PrimeMover bytecode transformation.

### What You'll See

**JavaFX 3D Visualization Window**:
- Grid of spatial cells (tetrahedral subdivision)
- Entities moving according to predator-prey dynamics
- Real-time FPS counter
- Entity count display
- Spatial bounds indicators

**Console Output**:
```
Starting PredatorPreyGridDemo...
Bubble created with 100 entities
Simulation running at 60 FPS
Press Ctrl+C to stop
```

### Controls

- **Mouse Drag**: Rotate view
- **Scroll**: Zoom in/out
- **Ctrl+C**: Stop simulation and exit

---

## What This Demonstrates

### Core Concepts

1. **Spatial Indexing** (`EnhancedBubble` with Tetree)
   - Tetrahedral subdivision of 3D space
   - O(log n) entity queries
   - Automatic bounds management

2. **Entity Behavior** (`EntityBehavior` interface)
   - Predator-prey dynamics
   - Velocity updates based on neighbors
   - Boundary collision detection

3. **Real-Time Simulation** (`RealTimeController`)
   - 60 Hz tick loop
   - Deterministic time advancement
   - Frame rate monitoring

4. **3D Visualization** (JavaFX `Group` and `Sphere`)
   - Real-time entity rendering
   - Spatial bounds visualization
   - Performance-optimized rendering

### Architecture Highlights

**Single Process** (No Network):
```
┌──────────────────────────────┐
│   PredatorPreyGridDemo       │
│                              │
│  ┌────────────────────────┐  │
│  │   EnhancedBubble       │  │
│  │                        │  │
│  │  Tetree<StringID>      │  │
│  │  + 100 entities        │  │
│  │  + Behavior updates    │  │
│  └────────────────────────┘  │
│                              │
│  ┌────────────────────────┐  │
│  │   JavaFX Renderer      │  │
│  │  + 3D Sphere meshes    │  │
│  │  + Camera controls     │  │
│  └────────────────────────┘  │
└──────────────────────────────┘
```

**No Network Communication**: Everything runs in a single JVM.

---

## Code Walkthrough

### Main Method

```java
public static void main(String[] args) {
    launch(args);  // JavaFX application launch
}
```

### Initialization

```java
@Override
public void start(Stage primaryStage) {
    // 1. Create bubble with spatial indexing
    var bubble = new EnhancedBubble(
        UUID.randomUUID(),
        (byte) 10,          // Spatial level
        50                  // Target frame time (ms)
    );

    // 2. Spawn entities
    for (int i = 0; i < 100; i++) {
        var position = randomPosition();
        bubble.addEntity("entity-" + i, position, entityData);
    }

    // 3. Start simulation tick loop
    bubble.start();

    // 4. Set up JavaFX rendering
    setupVisualization(primaryStage, bubble);
}
```

### Entity Behavior (Predator-Prey)

```java
@Override
public Vector3f computeVelocity(EntityRecord entity, List<EntityRecord> neighbors) {
    var velocity = new Vector3f();

    for (var neighbor : neighbors) {
        if (neighbor.isPredator()) {
            // Flee from predators
            velocity.sub(directionTo(neighbor));
        } else {
            // Attract to prey
            velocity.add(directionTo(neighbor));
        }
    }

    velocity.normalize();
    velocity.scale(5.0f);  // Speed = 5 units/sec
    return velocity;
}
```

---

## Customization

### Change Entity Count

Edit `PredatorPreyGridDemo.java`:
```java
private static final int ENTITY_COUNT = 200;  // Change from 100
```

### Change Spatial Level

```java
var bubble = new EnhancedBubble(
    id,
    (byte) 12,  // Higher = finer spatial subdivision
    50
);
```

### Change Simulation Speed

```java
var bubble = new EnhancedBubble(
    id,
    (byte) 10,
    16  // 16ms = ~60 FPS, 33ms = ~30 FPS
);
```

---

## Performance

**Typical Performance** (MacBook Pro M1, 100 entities):
- FPS: 58-60
- Tick latency: 5-10ms (P99: 15ms)
- Memory: ~200MB heap

**Scaling** (based on entity count):
| Entities | FPS | P99 Latency | Memory |
|----------|-----|-------------|--------|
| 100      | 60  | 15ms        | 200MB  |
| 1000     | 60  | 25ms        | 400MB  |
| 10000    | 55  | 50ms        | 1.2GB  |

---

## Next Steps

1. **Run TwoNode Demo**: See distributed entity migration ([../distributed/README.md](../distributed/README.md))
2. **Modify Behavior**: Experiment with custom `EntityBehavior` implementations
3. **Explore Spatial Indexing**: Read Tetree architecture docs (`lucien/doc/LUCIEN_ARCHITECTURE.md`)
4. **Review Source Code**: Study `PredatorPreyGridDemo.java` for implementation details

---

## Troubleshooting

### JavaFX Not Found
```bash
# Ensure Java 24+ with JavaFX included
java --version
```

### PrimeMover Transformation Error
```bash
# Use process-classes, not compile:
mvn process-classes exec:java -Dexec.mainClass=...
```

### Low FPS
```bash
# Reduce entity count or spatial level
# Check CPU usage (should be <50% for 100 entities)
```

### Window Doesn't Appear
```bash
# Check if running in headless environment
# JavaFX requires display/window system
```

---

**Document Version**: 1.0
**Last Updated**: 2026-02-10
**Source**: `simulation/src/main/java/.../viz/PredatorPreyGridDemo.java`
