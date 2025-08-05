# WebGPU Stub Implementation

This package contains stub classes that allow the ESVO WebGPU code to compile without the actual WebGPU bindings.

## Why Stubs?

The MyWorldLLC WebGPU-Java library is published to GitHub Package Registry, which requires authentication to access. Rather than requiring special Maven configuration, these stubs allow the code to compile and be studied.

## Real WebGPU Options

To use actual WebGPU functionality, you have several options:

### 1. Configure GitHub Package Registry Access

Add to your `~/.m2/settings.xml`:
```xml
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>YOUR_GITHUB_TOKEN</password>
  </server>
</servers>
```

Then add the repository to pom.xml:
```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/MyWorldLLC/Packages</url>
  </repository>
</repositories>
```

### 2. Use LWJGL WebGPU

Add to pom.xml:
```xml
<dependency>
    <groupId>org.lwjgl</groupId>
    <artifactId>lwjgl-webgpu</artifactId>
    <version>3.3.3</version>
</dependency>
```

### 3. Use wgpu-java

Another WebGPU binding option:
```xml
<dependency>
    <groupId>com.github.kgpu</groupId>
    <artifactId>wgpu-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 4. Build MyWorldLLC WebGPU-Java Locally

1. Clone https://github.com/MyWorldLLC/webgpu-java
2. Build with Maven: `mvn clean install`
3. Use the local version in your project

## Important Notes

- All stub methods throw `UnsupportedOperationException`
- The stubs only exist to allow compilation
- For actual GPU functionality, use one of the real binding options above
- The WebGPU API design in our code remains valid regardless of binding choice