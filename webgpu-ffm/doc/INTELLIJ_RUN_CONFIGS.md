# IntelliJ Run Configurations for WebGPU

## Configured Run Configurations

The following IntelliJ run configurations have been created in `.idea/runConfigurations/` with the necessary VM options for macOS:

### Test Configurations
- **RealSurfaceTest** - Tests real surface creation with native Metal layers
- **WorkingRealSurfaceTest** - Tests working real surface implementation  
- **SurfaceCapabilitiesValidationTest** - Validates Dawn surface capabilities
- **JavaFXSurfaceTest** - Tests JavaFX integration (no -XstartOnFirstThread needed)
- **All WebGPU Tests** - Runs all tests in the webgpu package

### Application Configurations
- **SurfaceRenderingExample** - Demo application showing surface rendering
- **JavaFXWebGPUDemo** - JavaFX integration demo (uses Launcher pattern)
- **ComprehensiveSurfaceDebugTest** - Comprehensive surface debugging application

### Template Configuration
- **WebGPU Test Template** - Default template for new JUnit tests

## Required VM Options

All configurations include the following VM options:
```
--enable-preview --enable-native-access=ALL-UNNAMED -XstartOnFirstThread
```

JavaFX configurations also include:
```
--add-modules javafx.controls,javafx.graphics
```

## Important Notes

1. **-XstartOnFirstThread** is required on macOS for GLFW/Metal layer creation
2. JavaFX applications use the `$Launcher` inner class pattern and don't need -XstartOnFirstThread
3. The configurations are stored in `.idea/runConfigurations/` and will be automatically loaded by IntelliJ

## Using the Configurations

1. Open IntelliJ IDEA
2. The configurations will appear in the Run Configuration dropdown
3. Select the desired configuration and click Run/Debug
4. No additional setup needed - all VM options are pre-configured

## Troubleshooting

If a configuration doesn't appear:
1. File → Reload from Disk
2. Or restart IntelliJ IDEA
3. Check that the `.idea/runConfigurations/` directory exists

If tests still skip with "requires -XstartOnFirstThread":
1. Verify the VM options are set in Run → Edit Configurations
2. Ensure you're running from IntelliJ, not Maven