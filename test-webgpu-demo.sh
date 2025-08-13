#!/bin/bash

# Test script to run WebGPU demo with correct macOS flags

echo "Building project..."
mvn clean compile -q -DskipTests

echo "Running WebGPU demo with -XstartOnFirstThread..."
java -XstartOnFirstThread \
     -cp "render/target/classes:webgpu-ffm/target/classes:render/target/dependency/*" \
     com.hellblazer.luciferase.render.webgpu.WebGPUVoxelDemo