# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

- **Build the project**: `mvn clean install`
- **Run tests**: `mvn test`
- **Run a specific test**: `mvn test -Dtest=ClassName`
- **Skip tests during build**: `mvn clean install -DskipTests`

## Requirements

- Java 23+ (configured in maven.compiler.source/target)
- Maven 3.91+
- Project is licensed under AGPL v3.0

## Architecture Overview

Luciferase is a 3D spatial data structure and visualization library organized into several key modules:

### Core Modules

- **common**: Fundamental data structures (array lists, hash sets, geometric utilities)
  - Contains optimized collections like `FloatArrayList`, `IntArrayList`, `OaHashSet`
  - Geometry utilities including `KdTree`, `MortonCurve`, and `Rotor3f`

- **lucien**: Spatial indexing core using octrees and tetrahedral decomposition
  - `Octree<Content>`: Morton curve-based spatial indexing
  - `Tetree`: Tetrahedral spatial decomposition
  - `Spatial`: Core spatial interfaces and implementations

- **sentry**: Delaunay tetrahedralization implementation
  - `Grid`: Main tetrahedralization class optimized for kinetic point tracking
  - Uses float precision with double predicates for performance/accuracy balance
  - Topology-focused rather than precise mesh geometry

- **portal**: JavaFX-based 3D visualization and mesh handling
  - `GeometryViewer`: Main 3D visualization application
  - Polyhedra generation (Platonic, Archimedean solids)
  - Mesh loading and manipulation utilities
  - Scene graph management with `Abstract3DApp`

- **grpc**: Protocol buffer definitions for spatial data serialization
  - Defines `Tetrahedralization_` and `Vertex_` messages

- **von**: Distributed spatial perception and node management
  - `SphereOfInteraction`: Spatial interaction boundaries
  - `Node`: Distributed spatial node abstraction

- **simulation**: Animation and movement simulation framework
  - `VolumeAnimator`: Handles spatial animation
  - Integrates with spatial data structures for dynamic simulations

### Key Design Patterns

- **Spatial Abstraction**: Core spatial operations are abstracted through `Spatial` interface
- **Morton Encoding**: Uses Morton curves for efficient 3D space indexing
- **Tetrahedral Decomposition**: Primary spatial decomposition strategy
- **JavaFX Integration**: 3D visualization built on JavaFX scene graph
- **Float Optimization**: Uses float precision for performance while maintaining double precision for critical geometric predicates

### Dependencies

- **JavaFX 24**: For 3D visualization
- **javax.vecmath**: Vector mathematics
- **gRPC/Protobuf**: For distributed communication
- **JUnit 5**: Testing framework
- **PrimeMover**: Custom simulation framework dependency