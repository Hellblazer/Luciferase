package com.hellblazer.luciferase.resource;

/**
 * Enumeration of GPU resource types across different APIs
 */
public enum GPUResourceType {
    // OpenGL Buffer Resources
    BUFFER("OpenGL Buffer", "Generic Buffer Object"),
    VERTEX_BUFFER("Vertex Buffer", "VBO - Vertex Data"),
    UNIFORM_BUFFER("Uniform Buffer", "UBO - Uniform Data"),
    STORAGE_BUFFER("Storage Buffer", "SSBO - Shader Storage"),
    
    // OpenGL Texture Resources
    TEXTURE("OpenGL Texture", "Generic Texture"),
    TEXTURE_1D("1D Texture", "1D Texture Object"),
    TEXTURE_2D("2D Texture", "2D Texture Object"),
    TEXTURE_3D("3D Texture", "3D Texture Object"),
    TEXTURE_CUBE("Cube Texture", "Cube Map Texture"),
    
    // OpenGL Shader Resources
    SHADER("OpenGL Shader", "Generic Shader"),
    VERTEX_SHADER("Vertex Shader", "Vertex Shader Object"),
    FRAGMENT_SHADER("Fragment Shader", "Fragment Shader Object"),
    COMPUTE_SHADER("Compute Shader", "Compute Shader Object"),
    GEOMETRY_SHADER("Geometry Shader", "Geometry Shader Object"),
    TESSELLATION_CONTROL_SHADER("Tess Control Shader", "Tessellation Control Shader"),
    TESSELLATION_EVALUATION_SHADER("Tess Eval Shader", "Tessellation Evaluation Shader"),
    SHADER_PROGRAM("Shader Program", "Linked Shader Program"),
    PROGRAM("Program", "GPU Program Object"),
    
    // OpenGL Other Resources
    MESH("Mesh", "GPU Mesh Resource"),
    FRAMEBUFFER("OpenGL Framebuffer", "FBO"),
    RENDERBUFFER("OpenGL Renderbuffer", "RBO"),
    SAMPLER("OpenGL Sampler", "Texture Sampler"),
    QUERY("OpenGL Query", "Timer/Occlusion Query"),
    SYNC("OpenGL Sync", "Fence Sync Object"),
    
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
        return !isOpenCL() && !isNative();
    }
    
    public boolean isOpenCL() {
        return name().startsWith("CL_");
    }
    
    public boolean isNative() {
        return this == NATIVE_MEMORY || this == MEMORY_POOL;
    }
}