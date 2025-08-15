#!/bin/bash

# Run LWJGL demo with proper JVM flags for macOS
echo "Starting LWJGL Demo..."

# macOS requires -XstartOnFirstThread for GLFW
if [[ "$OSTYPE" == "darwin"* ]]; then
    PLATFORM_FLAGS="-XstartOnFirstThread"
else
    PLATFORM_FLAGS=""
fi

mvn compile exec:java \
    -Dexec.mainClass="com.hellblazer.luciferase.render.lwjgl.demo.SimpleDemo" \
    -Dexec.classpathScope="compile" \
    -Djava.library.path="target/natives" \
    -Dorg.lwjgl.util.Debug=true \
    -Dorg.lwjgl.util.DebugLoader=true \
    -Dexec.additionalClasspathElements="target/classes" \
    -Dexec.cleanupDaemonThreads=false \
    -Dexec.args="$PLATFORM_FLAGS"