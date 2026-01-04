# Simulation Module

**Last Updated**: 2026-01-03
**Status**: Work in Progress

Event-driven simulation framework for 3D spatial systems, built on PrimeMover discrete event simulation.

## Current Implementation

### VolumeAnimator

Frame-rate controlled animation loop using PrimeMover's discrete event simulation:

```java
var animator = new VolumeAnimator("scene");
animator.start(); // Begins 100 FPS frame loop

// Track entities
Cursor cursor = animator.track(new Point3f(x, y, z));
cursor.moveTo(newPosition);
```

- Uses Lucien's `Tetree` for O(log n) spatial indexing (replaced MutableGrid)
- Uses `Kronos.sleep()` for precise timing control
- Tracks frame statistics (count, cumulative duration, delay)

### Facets

Basic interfaces for entity positioning:

- **Locatable**: `position()` - query entity location
- **Moveable**: `moveBy(delta)`, `moveTo(position)` - entity movement

## Architecture

See [doc/SIMULATION_BUBBLES.md](doc/SIMULATION_BUBBLES.md) for the distributed simulation design based on player-centric "simulation bubbles".

Key concepts:

- **Simulation Bubble**: Connected component of entities that can interact
- **Interaction Graph**: Edges between entities within Area of Interest
- **Server Assignment**: Bubbles (not spatial regions) assigned to servers

## Dependencies

- **PrimeMover**: Discrete event simulation framework (`RealTimeController`, `Kronos`, `@Entity`)
- **Lucien**: `Tetree` for spatial indexing, `Entity` for tracked objects

## Usage

```bash
# Run tests
mvn test -pl simulation
```

## License

AGPL v3.0
