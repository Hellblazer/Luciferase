# Luciferase

A bright spark - 3D spatial data structures and visualization library

## Overview

Luciferase is a high-performance 3D spatial indexing library that provides:
- **Octree**: Multi-entity spatial indexing with Morton curve encoding
- **Tetree**: Tetrahedral spatial decomposition 
- **Entity Management**: First-class entity support with IDs, bounds, and spanning policies
- **Visualization**: JavaFX-based 3D visualization tools

As of June 2025, the architecture has been significantly simplified to focus on core spatial indexing functionality.

## Build Status
![Build Status](https://github.com/hellblazer/Luciferase/actions/workflows/maven.yml/badge.svg)

___
This library is licensed under the AGPL v3.0, requires Java 23+ and is built with Maven 3.91+.  To build, cd into the root directory and do:

    mvn clean install
