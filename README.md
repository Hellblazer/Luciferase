# Luciferase

3D spatial data structures and visualization library

## Overview

Luciferase provides spatial indexing for 3D applications:
- **Octree**: Cubic spatial subdivision using Morton curves (21 levels)
- **Tetree**: Tetrahedral spatial subdivision with 21-level support matching Octree capacity
- **Entity Management**: Support for entities with IDs, bounds, and spanning
- **Visualization**: JavaFX-based 3D visualization tools

## Build Status
![Build Status](https://github.com/hellblazer/Luciferase/actions/workflows/maven.yml/badge.svg)

___
This library is licensed under the AGPL v3.0, requires Java 23+ and is built with Maven 3.91+.  To build, cd into the root directory and do:

    ./mvnw clean install
