# Simulation Module

**Last Updated**: 2025-12-31
**Status**: Work in Progress

Event-driven simulation framework for 3D spatial systems, built on PrimeMover discrete event simulation.

## Current Implementation

### VolumeAnimator

Frame-rate controlled animation loop using PrimeMover's discrete event simulation:

```java
var animator = new VolumeAnimator("scene", tetCell, random);
animator.start(); // Begins 100 FPS frame loop
```

- Integrates with Sentry's `MutableGrid` for Delaunay tetrahedralization
- Uses `Kronos.sleep()` for precise timing control
- Tracks frame statistics (count, cumulative duration, delay)

### Facets

Basic interfaces for entity positioning:

- **Locatable**: `position()` - query entity location
- **Moveable**: `moveBy(delta)`, `moveTo(position)` - entity movement

## Dependencies

- **PrimeMover**: Discrete event simulation framework (`RealTimeController`, `Kronos`, `@Entity`)
- **Sentry**: `MutableGrid` for spatial tracking via Delaunay tetrahedralization
- **Lucien**: `Tet` for tetrahedral cell definitions

## Future Directions

The following are aspirational goals, not current features:

- Physics simulation (rigid body dynamics, collision response)
- Animation framework (keyframe interpolation, skeletal animation)
- Behavioral patterns (flocking, steering, path following)
- Particle systems

## Usage

```bash
# Run tests
mvn test -pl simulation
```

## License

AGPL v3.0
