#!/bin/bash

# Run the Enhanced Voxel Visualization Demo
# This demo shows GPU-accelerated sparse voxel octree visualization

cd "$(dirname "$0")"

echo "Starting Enhanced Voxel Visualization Demo..."
echo "Features:"
echo "  - Dense vs Sparse voxel comparison"
echo "  - Real-time performance metrics"
echo "  - LOD (Level-of-Detail) controls"
echo "  - GPU-accelerated voxelization"
echo "  - Interactive 3D visualization"
echo ""

# Run with Maven
mvn -pl render test-compile exec:java \
    -Dexec.mainClass="com.hellblazer.luciferase.render.demo.EnhancedVoxelVisualizationDemo\$Launcher" \
    -Dexec.classpathScope="test" \
    -q