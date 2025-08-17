#!/bin/bash

# Automated test runner with platform detection for LWJGL/OpenGL tests
# This script automatically handles the macOS -XstartOnFirstThread requirement

echo "========================================="
echo "Luciferase Render Test Runner"
echo "========================================="

# Detect the operating system
OS_TYPE="unknown"
if [[ "$OSTYPE" == "darwin"* ]]; then
    OS_TYPE="macos"
    echo "Detected: macOS"
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    OS_TYPE="linux"
    echo "Detected: Linux"
elif [[ "$OSTYPE" == "msys" ]] || [[ "$OSTYPE" == "cygwin" ]] || [[ "$OSTYPE" == "win32" ]]; then
    OS_TYPE="windows"
    echo "Detected: Windows"
else
    echo "Warning: Unknown OS type: $OSTYPE"
fi

# Set platform-specific options
MAVEN_OPTS_ORIGINAL="$MAVEN_OPTS"

if [[ "$OS_TYPE" == "macos" ]]; then
    echo ""
    echo "Setting macOS-specific JVM flags..."
    export MAVEN_OPTS="-XstartOnFirstThread $MAVEN_OPTS"
    echo "MAVEN_OPTS: $MAVEN_OPTS"
fi

# Parse command line arguments
TEST_PATTERN=""
SKIP_GPU_TESTS=false
VERBOSE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --test)
            TEST_PATTERN="$2"
            shift 2
            ;;
        --skip-gpu)
            SKIP_GPU_TESTS=true
            shift
            ;;
        --verbose)
            VERBOSE=true
            shift
            ;;
        --help)
            echo ""
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  --test PATTERN    Run only tests matching pattern"
            echo "  --skip-gpu        Skip GPU/OpenGL tests"
            echo "  --verbose         Enable verbose output"
            echo "  --help            Show this help message"
            echo ""
            echo "Examples:"
            echo "  $0                          # Run all tests"
            echo "  $0 --test ESVOTest          # Run specific test"
            echo "  $0 --skip-gpu               # Skip GPU tests"
            echo ""
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Build Maven command
MVN_CMD="mvn test"

if [[ -n "$TEST_PATTERN" ]]; then
    MVN_CMD="$MVN_CMD -Dtest=$TEST_PATTERN"
fi

if [[ "$SKIP_GPU_TESTS" == true ]]; then
    MVN_CMD="$MVN_CMD -Dtest='!*GPU*,!*OpenGL*,!*LWJGL*'"
fi

if [[ "$VERBOSE" == true ]]; then
    MVN_CMD="$MVN_CMD -X"
else
    MVN_CMD="$MVN_CMD -q"
fi

# Run tests
echo ""
echo "Running: $MVN_CMD"
echo "========================================="

$MVN_CMD
TEST_RESULT=$?

# Restore original MAVEN_OPTS
export MAVEN_OPTS="$MAVEN_OPTS_ORIGINAL"

# Report results
echo ""
echo "========================================="
if [[ $TEST_RESULT -eq 0 ]]; then
    echo "✅ Tests completed successfully"
else
    echo "❌ Tests failed with exit code: $TEST_RESULT"
    
    if [[ "$OS_TYPE" == "macos" ]] && [[ $TEST_RESULT -ne 0 ]]; then
        echo ""
        echo "Note: If tests failed due to GLFW initialization:"
        echo "  - The script already added -XstartOnFirstThread"
        echo "  - Check test output for other issues"
    fi
fi

echo "========================================="

exit $TEST_RESULT