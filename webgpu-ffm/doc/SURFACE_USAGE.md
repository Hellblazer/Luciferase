# WebGPU Surface Usage Guide

## Overview

WebGPU surfaces are the bridge between GPU rendering and window systems. A surface represents a drawable area where WebGPU can present rendered images to the screen.

## Current State in Luciferase

The Luciferase project currently uses WebGPU primarily for **compute operations** (voxel processing, ESVO rendering pipeline) which don't require surfaces. The render module processes data on the GPU but doesn't display it directly to a window.

## How Surfaces Work

### 1. Window Creation
You need a real window from a native window system:
```java
// Using GLFW (via LWJGL bindings)
glfwInit();
glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API); // Important for WebGPU
long window = glfwCreateWindow(800, 600, "WebGPU Window", 0, 0);
```

### 2. Native Handle Extraction
Get the platform-specific window handle:
```java
// macOS
long nsWindow = glfwGetCocoaWindow(window);
// Windows  
long hwnd = glfwGetWin32Window(window);
// Linux
long x11Window = glfwGetX11Window(window);
```

### 3. Surface Creation
Create a WebGPU surface from the native handle:
```java
var surfaceDescriptor = SurfaceDescriptor.create(arena, nativeHandle);
var surface = instance.createSurface(surfaceDescriptor.getDescriptor());
```

### 4. Surface Configuration
Configure the surface for rendering:
```java
var config = new Surface.Configuration.Builder()
    .withDevice(device)
    .withSize(width, height)
    .withFormat(TEXTURE_FORMAT_BGRA8_UNORM)
    .withUsage(TEXTURE_USAGE_RENDER_ATTACHMENT)
    .withPresentMode(PRESENT_MODE_FIFO) // VSync
    .build();
surface.configure(config);
```

### 5. Render Loop
```java
while (!windowShouldClose) {
    // Get surface texture
    var surfaceTexture = surface.getCurrentTexture();
    
    // Render to texture
    var commandEncoder = device.createCommandEncoder();
    // ... render pass with surface texture as target ...
    var commandBuffer = commandEncoder.finish();
    queue.submit(commandBuffer);
    
    // Present to screen
    surface.present();
}
```

## Why Tests Don't Create Real Surfaces

The tests in `webgpu-ffm` don't create real surfaces because:

1. **No Window System**: Tests run in headless environments without window managers
2. **JavaFX Limitations**: JavaFX doesn't expose native window handles through public APIs
3. **Platform Dependencies**: Real surfaces require platform-specific code
4. **CI/CD Compatibility**: Tests must run on CI servers without displays

## Options for Real Surface Rendering

### Option 1: LWJGL + GLFW (Recommended)
```xml
<dependency>
    <groupId>org.lwjgl</groupId>
    <artifactId>lwjgl-glfw</artifactId>
    <version>3.3.3</version>
</dependency>
```

Pros:
- Cross-platform
- Well-documented
- Active community
- Java bindings available

### Option 2: JavaFX with JNI Bridge
Create a JNI library to extract native handles from JavaFX:
- Complex implementation
- Platform-specific code required
- Fragile across JavaFX versions

### Option 3: Native Window Libraries
Use FFM API to call native window APIs directly:
- Maximum control
- Complex implementation
- Platform-specific

## Example Projects Using WebGPU Surfaces

Most WebGPU examples use native languages (Rust, C++) with proper window libraries:

- **wgpu-rs examples**: Uses winit for window creation
- **Dawn samples**: Uses GLFW
- **WebGPU native demos**: Various window libraries

## Current Luciferase Architecture

```
┌─────────────────┐
│   Application   │
├─────────────────┤
│  Render Module  │ ← Compute-only operations
├─────────────────┤
│  WebGPU FFM     │ ← Surface API available but unused
├─────────────────┤
│  Native WebGPU  │
└─────────────────┘
```

## To Add Surface Rendering to Luciferase

1. **Choose a window library** (LWJGL recommended)
2. **Add dependencies** to the project
3. **Create window management layer**
4. **Implement platform-specific surface creation**
5. **Add render loop integration**
6. **Handle window events** (resize, close, etc.)

## Testing Surface Code

For testing surface-related code without real windows:

1. **Mock at the window level**: Create mock window handles (causes WebGPU panics)
2. **Mock at the surface level**: Skip surface creation in tests (current approach)
3. **Use offscreen rendering**: Render to textures instead of surfaces
4. **Integration tests only**: Test surfaces only in special integration test environments

## Conclusion

WebGPU surfaces require real windows from native window systems. The current Luciferase architecture focuses on compute operations and doesn't need surfaces. To add window rendering, you'd need to integrate a proper window library like GLFW via LWJGL bindings.