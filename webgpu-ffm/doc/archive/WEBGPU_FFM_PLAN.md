# WebGPU FFM Module Implementation Plan

## Overview
Creating a dedicated WebGPU Foreign Function & Memory (FFM) module for Luciferase to provide proper Java bindings for the wgpu-native library. This module will support multiple platforms, provide maintainable code generation, and offer both low-level and high-level APIs.

## Problem Statement
- Current `com.myworldvw:webgpu-java` bindings don't match our `wgpu-native` library
- Version matching alone isn't sufficient - different WebGPU implementations have different APIs
- Need platform-specific native library management
- Require maintainable solution that can adapt to WebGPU API changes

## Solution Architecture

### Module Structure
```
Luciferase/
├── webgpu-ffm/                      # New WebGPU FFM module
│   ├── pom.xml                      # Maven configuration
│   ├── WEBGPU_FFM_PLAN.md          # This document
│   ├── IMPLEMENTATION_STATUS.md     # Progress tracking
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── com/hellblazer/luciferase/webgpu/
│   │   │   │       ├── WebGPU.java              # High-level API
│   │   │   │       ├── WebGPULoader.java        # Native library loader
│   │   │   │       ├── platform/                # Platform detection
│   │   │   │       ├── native/                  # Generated FFM bindings
│   │   │   │       ├── wrapper/                 # Type-safe wrappers
│   │   │   │       └── builder/                 # Builder pattern APIs
│   │   │   └── resources/
│   │   │       ├── natives/                     # Platform libraries
│   │   │       └── META-INF/
│   │   │           └── webgpu-version.properties
│   │   ├── test/
│   │   └── build/
│   │       ├── headers/                         # WebGPU C headers
│   │       ├── scripts/                         # Build scripts
│   │       └── jextract/                        # jextract configs
```

## Implementation Phases

### Phase 1: Module Setup (Current)
- [ ] Create webgpu-ffm module directory structure
- [ ] Add module to parent pom.xml
- [ ] Create module pom.xml with dependencies
- [ ] Set up basic package structure

### Phase 2: Platform Support
- [ ] Implement PlatformDetector class
- [ ] Create Platform enum with all supported platforms
- [ ] Implement native library extraction from JAR
- [ ] Add WebGPULoader for library loading

### Phase 3: Code Generation
- [ ] Configure jextract in Maven build
- [ ] Generate FFM bindings from webgpu.h
- [ ] Handle both webgpu.h and wgpu.h headers
- [ ] Set up automated regeneration

### Phase 4: API Layers
- [ ] Create low-level native wrapper
- [ ] Implement mid-level type-safe wrappers
- [ ] Build high-level builder pattern API
- [ ] Add resource management (AutoCloseable)

### Phase 5: Native Libraries
- [ ] Download wgpu-native for all platforms
- [ ] Package in resources/natives/
- [ ] Add version tracking
- [ ] Create update scripts

### Phase 6: Testing
- [ ] Unit tests for platform detection
- [ ] Integration tests with GPU
- [ ] Multi-platform CI/CD
- [ ] Performance benchmarks

### Phase 7: Integration
- [ ] Replace stub in render module
- [ ] Update existing WebGPU tests
- [ ] Migration guide
- [ ] Documentation

## Technical Details

### Platform Support Matrix
| Platform | Architecture | Library Name | Version | Status |
|----------|-------------|-------------|---------|--------|
| macOS | aarch64 | libwgpu_native.dylib | 25.0.2.1 | Planned |
| macOS | x86_64 | libwgpu_native.dylib | 25.0.2.1 | Planned |
| Linux | x86_64 | libwgpu_native.so | 25.0.2.1 | Planned |
| Windows | x86_64 | wgpu_native.dll | 25.0.2.1 | Planned |

### Dependencies
- Java 24 (for FFM API)
- jextract (for code generation)
- wgpu-native (native library)
- Maven exec plugin (for build automation)

### API Design

#### Low Level (Generated)
```java
// Raw FFM bindings
var instance = WebGPUNative.wgpuCreateInstance(MemorySegment.NULL);
```

#### Mid Level (Type-Safe)
```java
// Type-safe wrappers
Instance instance = new Instance();
Adapter adapter = instance.requestAdapter(new AdapterOptions());
```

#### High Level (Builder)
```java
// Fluent builder API
var device = WebGPU.createInstance()
    .withValidation(true)
    .build()
    .requestAdapter()
    .powerPreference(PowerPreference.HIGH_PERFORMANCE)
    .requestAsync()
    .get()
    .requestDevice()
    .withFeatures(Features.TIMESTAMP_QUERY)
    .requestAsync()
    .get();
```

## Maintainability

### Version Management
- Track WebGPU spec version
- Track wgpu-native version
- Generate version constants
- Runtime version checking

### Update Process
1. Run update script with new version
2. Download new natives and headers
3. Regenerate FFM bindings
4. Run compatibility tests
5. Generate migration report

### Error Handling
- Convert native error codes to exceptions
- Implement error callbacks
- Validation layer support
- Detailed error context

## Success Criteria
1. All tests pass with real WebGPU operations (not stubs)
2. Multi-platform support working
3. Clean API at multiple abstraction levels
4. Automated update process
5. Comprehensive documentation
6. Performance on par with native

## Current Status
- **Date**: August 6, 2025
- **Phase**: 1 - Module Setup
- **Blockers**: None
- **Next Steps**: Create module structure and Maven configuration

## Notes
- Headers available at: render/include/webgpu/
- Native library at: render/lib/libwgpu_native.dylib
- Existing stub implementation can be reference for API design