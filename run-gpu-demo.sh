#!/bin/bash

# ESVO BGFX GPU Demo Runner
# This script handles the complex JVM setup required for GPU execution

echo "=== ESVO BGFX Metal GPU Demo Runner ==="

# Check if we're on macOS
if [[ "$OSTYPE" != "darwin"* ]]; then
    echo "WARNING: This demo is optimized for macOS Metal backend"
fi

# Set up JVM flags for native access and GLFW
export MAVEN_OPTS="-XstartOnFirstThread --enable-native-access=ALL-UNNAMED --enable-preview"

# Add GLFW threading bypass (for headless GPU compute)
export JVM_ARGS="-Dorg.lwjgl.glfw.checkThread=false"

echo "JVM Configuration:"
echo "  MAVEN_OPTS: $MAVEN_OPTS"
echo "  JVM_ARGS: $JVM_ARGS"
echo ""

# Build the classpath manually to avoid Maven exec plugin issues
echo "Building classpath..."
mvn dependency:build-classpath -pl render -q -Dmdep.outputFile=classpath.txt
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to build classpath"
    exit 1
fi

CLASSPATH=$(cat classpath.txt):render/target/classes
rm -f classpath.txt

echo "Running ESVOBGFXGPUDemo with direct Java execution..."
echo ""

# Run the demo directly with Java to bypass Maven exec plugin
java \
    -XstartOnFirstThread \
    --enable-native-access=ALL-UNNAMED \
    --enable-preview \
    -Dorg.lwjgl.glfw.checkThread=false \
    -Djava.library.path=/opt/homebrew/lib \
    -cp "$CLASSPATH" \
    com.hellblazer.luciferase.render.voxel.esvo.gpu.ESVOBGFXGPUDemo

RESULT=$?

if [ $RESULT -eq 0 ]; then
    echo ""
    echo "🎉 GPU Demo completed successfully!"
else
    echo ""
    echo "❌ GPU Demo failed with exit code: $RESULT"
fi

exit $RESULT