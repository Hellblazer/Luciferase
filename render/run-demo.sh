#!/bin/bash

# Script to run the OpenGL Integration Demo with proper JVM flags for macOS

# Ensure we're in the render directory
cd "$(dirname "$0")"

# Build the project first
echo "Building the render module..."
mvn compile -q

# Get the classpath
echo "Preparing classpath..."
mvn dependency:build-classpath -Dmdep.outputFile=cp.txt -q

# Run the demo with the required JVM flags
echo "Starting OpenGL Integration Demo..."
echo "================================"

java -XstartOnFirstThread \
     -cp target/classes:$(cat cp.txt) \
     --enable-native-access=ALL-UNNAMED \
     com.hellblazer.luciferase.render.demo.OpenGLIntegrationDemo

# Clean up
rm -f cp.txt

echo ""
echo "Demo completed."