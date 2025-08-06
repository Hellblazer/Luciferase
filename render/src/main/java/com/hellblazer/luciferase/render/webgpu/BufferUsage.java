package com.hellblazer.luciferase.render.webgpu;

/**
 * WebGPU buffer usage flags
 */
public class BufferUsage {
    public static final int MAP_READ = 0x0001;
    public static final int MAP_WRITE = 0x0002;
    public static final int COPY_SRC = 0x0004;
    public static final int COPY_DST = 0x0008;
    public static final int INDEX = 0x0010;
    public static final int VERTEX = 0x0020;
    public static final int UNIFORM = 0x0040;
    public static final int STORAGE = 0x0080;
    public static final int INDIRECT = 0x0100;
    public static final int QUERY_RESOLVE = 0x0200;
    
    private BufferUsage() {
        // Utility class
    }
}