package com.hellblazer.luciferase.resource;

/**
 * Enumeration of GPU resource types across different APIs
 */
public enum GPUResourceType {
    // OpenGL Resources
    GL_BUFFER("OpenGL Buffer", "VBO/IBO/UBO/SSBO"),
    GL_TEXTURE("OpenGL Texture", "1D/2D/3D/Cube/Array"),
    GL_PROGRAM("OpenGL Program", "Shader Program"),
    GL_FRAMEBUFFER("OpenGL Framebuffer", "FBO"),
    GL_RENDERBUFFER("OpenGL Renderbuffer", "RBO"),
    GL_SAMPLER("OpenGL Sampler", "Texture Sampler"),
    GL_QUERY("OpenGL Query", "Timer/Occlusion Query"),
    GL_SYNC("OpenGL Sync", "Fence Sync Object"),
    
    // OpenCL Resources
    CL_BUFFER("OpenCL Buffer", "Device Memory Buffer"),
    CL_IMAGE("OpenCL Image", "2D/3D Image"),
    CL_PROGRAM("OpenCL Program", "Kernel Program"),
    CL_KERNEL("OpenCL Kernel", "Compute Kernel"),
    CL_COMMAND_QUEUE("OpenCL Queue", "Command Queue"),
    CL_EVENT("OpenCL Event", "Synchronization Event"),
    CL_SAMPLER("OpenCL Sampler", "Image Sampler"),
    
    // Native Memory
    NATIVE_MEMORY("Native Memory", "Off-heap Memory"),
    MEMORY_POOL("Memory Pool", "Pooled Buffer");
    
    private final String displayName;
    private final String description;
    
    GPUResourceType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
    
    public boolean isOpenGL() {
        return name().startsWith("GL_");
    }
    
    public boolean isOpenCL() {
        return name().startsWith("CL_");
    }
    
    public boolean isNative() {
        return this == NATIVE_MEMORY || this == MEMORY_POOL;
    }
}