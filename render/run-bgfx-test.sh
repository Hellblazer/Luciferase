#!/bin/bash

echo "=== Running Minimal BGFX Metal Test ==="

cd /Users/hal.hildebrand/git/Luciferase/render

# Compile test classes first
mvn test-compile -q

# Run the test with proper JVM flags
mvn exec:java \
    -Dexec.mainClass="com.hellblazer.luciferase.render.gpu.bgfx.MinimalBGFXMetalTest" \
    -Dexec.classpathScope="test" \
    -Djava.library.path="target/natives" \
    -Dorg.lwjgl.glfw.checkThread=false \
    -Dorg.lwjgl.util.Debug=true \
    -Dexec.additionalClasspathElements="target/test-classes" \
    -Dexec.cleanupDaemonThreads=false \
    -Dexec.args="-XstartOnFirstThread --enable-native-access=ALL-UNNAMED --enable-preview"

echo "Test completed with exit code: $?"