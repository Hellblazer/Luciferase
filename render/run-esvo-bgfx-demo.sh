#!/bin/bash

# Run ESVO BGFX GPU Demo with proper JVM flags for macOS Metal backend
echo "=== Starting ESVO BGFX Metal GPU Demo ==="

# macOS requires -XstartOnFirstThread for GLFW/Metal
if [[ "$OSTYPE" == "darwin"* ]]; then
    PLATFORM_FLAGS="-XstartOnFirstThread"
    echo "macOS detected - using Metal backend"
else
    PLATFORM_FLAGS=""
    echo "WARNING: Non-macOS platform - Metal may not be available"
fi

# Add JVM flags for Java 24 and native access
JVM_FLAGS="$PLATFORM_FLAGS --enable-native-access=ALL-UNNAMED --enable-preview"

echo "JVM Flags: $JVM_FLAGS"
echo "Main Class: com.hellblazer.luciferase.render.voxel.esvo.gpu.ESVOBGFXGPUDemo"
echo ""

cd /Users/hal.hildebrand/git/Luciferase/render

mvn compile exec:java \
    -Dexec.mainClass="com.hellblazer.luciferase.render.voxel.esvo.gpu.ESVOBGFXGPUDemo" \
    -Dexec.classpathScope="compile" \
    -Djava.library.path="target/natives" \
    -Dorg.lwjgl.glfw.checkThread=false \
    -Dorg.lwjgl.util.Debug=true \
    -Dorg.lwjgl.util.DebugLoader=true \
    -Dexec.additionalClasspathElements="target/classes" \
    -Dexec.cleanupDaemonThreads=false \
    -Dexec.args="$JVM_FLAGS"

RESULT=$?

if [ $RESULT -eq 0 ]; then
    echo ""
    echo "🎉 ESVO BGFX Metal GPU Demo completed successfully!"
    echo "Real Metal compute shaders were executed on the GPU."
else
    echo ""
    echo "❌ Demo failed with exit code: $RESULT"
    echo "Check the output above for error details."
fi

exit $RESULT