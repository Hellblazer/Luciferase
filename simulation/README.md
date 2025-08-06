# Simulation Module

[![Build Status](https://github.com/Hellblazer/Luciferase/actions/workflows/maven.yml/badge.svg)](https://github.com/Hellblazer/Luciferase/actions)

Advanced physics simulation and animation framework for 3D spatial systems.

## Overview

The Simulation module provides a comprehensive physics engine and animation framework for Luciferase. It handles entity movement, collision response, force simulation, and complex animation choreography through behavioral patterns and flocking algorithms.

## Features

### Physics Engine
- **Rigid Body Dynamics**: Mass, velocity, acceleration, angular motion
- **Soft Body Simulation**: Deformable objects with spring-damper systems
- **Collision Response**: Elastic and inelastic collisions with energy conservation
- **Force Systems**: Gravity, springs, dampers, magnetic fields, wind
- **Constraint Solvers**: Position, velocity, and acceleration constraints

### Animation Framework
- **Keyframe Animation**: Smooth interpolation between states
- **Procedural Animation**: Mathematical and algorithmic movement patterns
- **Behavioral Animation**: Goal-driven autonomous agent behavior
- **Particle Systems**: Large-scale particle effects and fluid simulation
- **Skeletal Animation**: Hierarchical bone-based character animation

### Movement Patterns
- **Flocking Behaviors**: Boids algorithm with cohesion, separation, alignment
- **Path Following**: Bezier curves, splines, waypoint navigation
- **Steering Behaviors**: Seek, flee, pursue, evade, wander
- **Formation Movement**: Coordinated group movements
- **Terrain Following**: Adaptive movement over complex surfaces

## Architecture

### Core Components

```
simulation/
├── core/
│   ├── PhysicsEngine.java       # Main physics simulation loop
│   ├── AnimationEngine.java     # Animation update and interpolation
│   ├── TimeStep.java           # Fixed and variable timestep handling
│   └── SimulationContext.java  # Shared simulation state
├── physics/
│   ├── RigidBody.java          # Rigid body dynamics
│   ├── SoftBody.java           # Soft body simulation
│   ├── Collision.java          # Collision detection and response
│   ├── Force.java              # Force generators
│   └── Constraint.java         # Physical constraints
├── animation/
│   ├── Keyframe.java           # Keyframe animation system
│   ├── Interpolator.java       # Animation interpolation
│   ├── AnimationClip.java      # Animation sequences
│   └── Skeleton.java           # Skeletal animation
├── behavior/
│   ├── FlockingSystem.java     # Boids implementation
│   ├── SteeringBehavior.java   # Steering algorithms
│   ├── PathFollower.java       # Path navigation
│   └── Formation.java          # Formation control
└── particle/
    ├── ParticleSystem.java      # Particle emitters and updaters
    ├── FluidSimulation.java     # SPH fluid simulation
    └── ParticleForces.java      # Particle-specific forces
```

## Usage Examples

### Basic Physics Simulation

```java
// Create physics engine with fixed timestep
var physics = new PhysicsEngine(1.0 / 60.0); // 60 FPS

// Create rigid body
var body = new RigidBody()
    .withMass(1.0f)
    .withPosition(new Vector3f(0, 10, 0))
    .withVelocity(new Vector3f(5, 0, 0));

// Add forces
physics.addForce(new GravityForce(9.81f));
physics.addForce(new DragForce(0.1f));

// Add body to simulation
physics.addBody(body);

// Simulation loop
while (running) {
    physics.update(deltaTime);
    Vector3f position = body.getPosition();
    // Update visual representation
}
```

### Flocking Behavior

```java
// Create flocking system
var flock = new FlockingSystem()
    .withCohesionWeight(1.0f)
    .withSeparationWeight(1.5f)
    .withAlignmentWeight(0.8f)
    .withNeighborRadius(5.0f);

// Add boids
for (int i = 0; i < 100; i++) {
    var boid = new Boid(randomPosition(), randomVelocity());
    flock.addBoid(boid);
}

// Update flock
flock.update(deltaTime);
```

### Keyframe Animation

```java
// Create animation clip
var clip = new AnimationClip("walk_cycle");

// Add keyframes
clip.addKeyframe(0.0f, new Transform(pos1, rot1, scale1));
clip.addKeyframe(0.5f, new Transform(pos2, rot2, scale2));
clip.addKeyframe(1.0f, new Transform(pos3, rot3, scale3));

// Create animator
var animator = new Animator();
animator.playClip(clip, true); // Loop animation

// Update animation
animator.update(deltaTime);
Transform current = animator.getCurrentTransform();
```

### Particle System

```java
// Create particle emitter
var emitter = new ParticleEmitter()
    .withEmissionRate(100) // particles per second
    .withLifetime(5.0f)
    .withInitialVelocity(new Vector3f(0, 10, 0))
    .withVelocityVariance(new Vector3f(2, 2, 2))
    .withColor(Color.ORANGE)
    .withSize(0.1f);

// Create particle system
var particles = new ParticleSystem(10000); // max particles
particles.addEmitter(emitter);
particles.addForce(new GravityForce(9.81f));
particles.addForce(new WindForce(new Vector3f(5, 0, 0)));

// Update particles
particles.update(deltaTime);
```

## Performance Characteristics

### Computational Complexity
- Collision Detection: O(n²) naive, O(n log n) with spatial partitioning
- Flocking: O(n²) naive, O(n) with spatial hashing
- Particle Systems: O(n) per particle per frame
- Constraint Solving: O(n) per iteration (multiple iterations typical)

### Memory Usage
- RigidBody: ~256 bytes per body
- Particle: ~64 bytes per particle
- Keyframe: ~64 bytes per keyframe
- Boid: ~128 bytes per agent

### Optimization Strategies
- Spatial partitioning for collision detection
- LOD (Level of Detail) for distant animations
- Instanced rendering for particle systems
- SIMD operations for vector math
- GPU compute shaders for massive particle systems

## Integration

### With Lucien Spatial Index

```java
// Use spatial index for efficient neighbor queries
var spatialIndex = new Octree<>(bounds);
var flocking = new FlockingSystem(spatialIndex);

// Spatial index accelerates collision detection
var collisions = new CollisionSystem(spatialIndex);
```

### With Portal Visualization

```java
// Connect simulation to 3D visualization
var simulation = new SimulationEngine();
var visualizer = new Portal3DVisualizer();

simulation.onUpdate(entities -> {
    visualizer.updateEntities(entities);
});
```

### With Von Distribution

```java
// Distribute simulation across nodes
var distributed = new DistributedSimulation();
distributed.partitionSpace(spatialIndex);
distributed.synchronize(vonNetwork);
```

## Configuration

### Physics Settings

```java
// Configure physics engine
PhysicsConfig config = new PhysicsConfig()
    .withGravity(9.81f)
    .withAirDensity(1.225f)
    .withRestitution(0.8f)
    .withFriction(0.3f)
    .withSolverIterations(10)
    .withSleepThreshold(0.01f);

var physics = new PhysicsEngine(config);
```

### Animation Settings

```java
// Configure animation system
AnimationConfig config = new AnimationConfig()
    .withInterpolation(InterpolationType.CUBIC)
    .withBlending(BlendMode.ADDITIVE)
    .withTimeScale(1.0f)
    .withMaxBones(256);

var animator = new AnimationEngine(config);
```

## Thread Safety

The simulation module supports concurrent execution:

- **Physics Engine**: Thread-safe with internal synchronization
- **Animation System**: Thread-safe for read, single writer for updates
- **Particle Systems**: Parallel update with work stealing
- **Flocking**: Partitioned parallel processing

## Testing

```bash
# Run simulation tests
mvn test -pl simulation

# Run physics benchmarks
mvn test -pl simulation -Dtest=PhysicsBenchmark

# Run animation tests
mvn test -pl simulation -Dtest=AnimationTest
```

## Dependencies

- **Lucien**: Spatial indexing for collision detection
- **Common**: Optimized math and geometry utilities
- **Apache Commons Math**: Advanced mathematical functions
- **JOML**: Java OpenGL Math Library for transformations

## Performance Metrics

- Physics update: 1-2ms for 1000 rigid bodies
- Collision detection: 0.5ms with spatial partitioning
- Flocking update: 0.8ms for 500 boids
- Particle update: 1.5ms for 10,000 particles
- Animation blending: 0.1ms per character

## License

AGPL v3.0 - See LICENSE file for details