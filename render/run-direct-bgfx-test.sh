#!/bin/bash

echo "=== Direct Java Execution - Minimal BGFX Metal Test ==="

cd /Users/hal.hildebrand/git/Luciferase/render

# Build classpath manually using dependency plugin
echo "Building classpath..."
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/render-classpath.txt -q

if [ ! -f /tmp/render-classpath.txt ]; then
    echo "ERROR: Failed to build classpath"
    exit 1
fi

CLASSPATH=$(cat /tmp/render-classpath.txt):target/classes:target/test-classes
rm -f /tmp/render-classpath.txt

echo "Classpath built successfully"
echo "Running MinimalBGFXMetalTest with direct Java execution..."
echo ""

# Run with all required JVM flags
java \
    -XstartOnFirstThread \
    --enable-native-access=ALL-UNNAMED \
    --enable-preview \
    -Dorg.lwjgl.glfw.checkThread=false \
    -Dorg.lwjgl.util.Debug=true \
    -Djava.library.path=/opt/homebrew/lib:/usr/local/lib \
    -cp "$CLASSPATH" \
    com.hellblazer.luciferase.render.gpu.bgfx.MinimalBGFXMetalTest

RESULT=$?
echo ""
echo "Direct execution completed with exit code: $RESULT"
exit $RESULT