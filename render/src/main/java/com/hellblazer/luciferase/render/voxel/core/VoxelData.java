package com.hellblazer.luciferase.render.voxel.core;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

import javax.vecmath.Vector3f;

/**
 * VoxelData represents voxel attributes in a highly optimized bit-packed format
 * designed for efficient GPU access and memory usage. This implementation stores
 * color (RGB), compressed normal, material ID, and opacity in a single 64-bit value.
 * 
 * <h2>Memory Layout (8 bytes total)</h2>
 * <pre>
 * Bits:  | 63-56 | 55-48 | 47-40 | 39-32 | 31-24 | 23-16 | 15-8  | 7-0   |
 * Field: | Mat   | Opc   | NrmZ  | NrmY  | NrmX  | Blue  | Green | Red   |
 * Size:  | 8 bit | 8 bit | 8 bit | 8 bit | 8 bit | 8 bit | 8 bit | 8 bit |
 * </pre>
 * 
 * <h3>Field Descriptions:</h3>
 * <ul>
 * <li><b>Red (8 bits)</b>: Red color component (0-255)</li>
 * <li><b>Green (8 bits)</b>: Green color component (0-255)</li>
 * <li><b>Blue (8 bits)</b>: Blue color component (0-255)</li>
 * <li><b>Normal X (8 bits)</b>: Compressed normal X component (-1.0 to 1.0 mapped to 0-255)</li>
 * <li><b>Normal Y (8 bits)</b>: Compressed normal Y component (-1.0 to 1.0 mapped to 0-255)</li>
 * <li><b>Normal Z (8 bits)</b>: Compressed normal Z component (-1.0 to 1.0 mapped to 0-255)</li>
 * <li><b>Opacity (8 bits)</b>: Alpha/opacity value (0-255, where 255 = fully opaque)</li>
 * <li><b>Material ID (8 bits)</b>: Material identifier (0-255 material types)</li>
 * </ul>
 * 
 * <h2>Design Features</h2>
 * <ul>
 * <li><b>GPU Optimized</b>: 64-bit alignment for efficient GPU memory access</li>
 * <li><b>Bit-Packed</b>: Minimal memory footprint (8 bytes per voxel)</li>
 * <li><b>DXT Ready</b>: Color format compatible with DXT compression algorithms</li>
 * <li><b>Thread-Safe</b>: Atomic operations for concurrent access</li>
 * <li><b>FFM Compatible</b>: Direct memory mapping for zero-copy GPU operations</li>
 * </ul>
 * 
 * <h2>Normal Compression</h2>
 * Surface normals are compressed from floating-point (-1.0 to 1.0) to 8-bit integers
 * (0-255) using linear mapping. The compression formula is:
 * <code>compressed = (int)((normal + 1.0f) * 127.5f)</code>
 * 
 * Decompression reverses this: 
 * <code>normal = (compressed / 127.5f) - 1.0f</code>
 * 
 * <h2>DXT Compression Support</h2>
 * The RGB color components are stored in standard 8-bit format compatible with
 * DXT1/DXT5 compression. The layout enables direct extraction of color blocks
 * for hardware texture compression.
 * 
 * @author Claude (Generated)
 * @version 1.0
 * @since Luciferase 0.0.1
 */
public final class VoxelData {
    
    // ================================================================================
    // Constants and Memory Layout Definition
    // ================================================================================
    
    /**
     * Size of voxel data in bytes (matches VoxelOctreeNode alignment)
     */
    public static final int VOXEL_DATA_SIZE_BYTES = 8;
    
    /**
     * Maximum value for 8-bit components (255)
     */
    public static final int MAX_COMPONENT_VALUE = 255;
    
    /**
     * Scale factor for normal compression (127.5 for symmetric -1.0 to 1.0 mapping)
     */
    private static final float NORMAL_SCALE = 127.5f;
    
    /**
     * Offset for normal compression (1.0 to shift -1.0 to 1.0 range to 0.0 to 2.0)
     */
    private static final float NORMAL_OFFSET = 1.0f;
    
    /**
     * FFM memory layout for GPU-compatible 8-byte voxel data structure
     */
    public static final StructLayout MEMORY_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("voxelData")
    ).withByteAlignment(8);
    
    // Bit field constants for the 64-bit packed structure
    private static final long RED_MASK        = 0x00000000000000FFL; // Bits 0-7
    private static final long GREEN_MASK      = 0x000000000000FF00L; // Bits 8-15
    private static final long BLUE_MASK       = 0x0000000000FF0000L; // Bits 16-23
    private static final long NORMAL_X_MASK   = 0x00000000FF000000L; // Bits 24-31
    private static final long NORMAL_Y_MASK   = 0x000000FF00000000L; // Bits 32-39
    private static final long NORMAL_Z_MASK   = 0x0000FF0000000000L; // Bits 40-47
    private static final long OPACITY_MASK    = 0x00FF000000000000L; // Bits 48-55
    private static final long MATERIAL_MASK   = 0xFF00000000000000L; // Bits 56-63
    
    // Combined masks for efficient operations
    private static final long COLOR_MASK      = RED_MASK | GREEN_MASK | BLUE_MASK;
    private static final long NORMAL_MASK     = NORMAL_X_MASK | NORMAL_Y_MASK | NORMAL_Z_MASK;
    
    // Bit shift constants
    private static final int RED_SHIFT        = 0;
    private static final int GREEN_SHIFT      = 8;
    private static final int BLUE_SHIFT       = 16;
    private static final int NORMAL_X_SHIFT   = 24;
    private static final int NORMAL_Y_SHIFT   = 32;
    private static final int NORMAL_Z_SHIFT   = 40;
    private static final int OPACITY_SHIFT    = 48;
    private static final int MATERIAL_SHIFT   = 56;
    
    // VarHandle for atomic operations on memory segments
    private static final VarHandle VOXEL_DATA_HANDLE = MEMORY_LAYOUT.varHandle(
        MemoryLayout.PathElement.groupElement("voxelData"));
    
    // ================================================================================
    // Instance Fields
    // ================================================================================
    
    /**
     * Atomic reference to the 64-bit packed voxel data.
     * All voxel attributes are stored in this single long value for thread-safe operations.
     */
    private final AtomicLong voxelData;
    
    /**
     * Optional reference to native memory segment for zero-copy GPU operations.
     * When non-null, this segment contains the same data as voxelData but in native memory.
     */
    private volatile MemorySegment nativeSegment;
    
    // ================================================================================
    // Constructors
    // ================================================================================
    
    /**
     * Creates a new empty voxel data with all attributes set to zero.
     * This represents a transparent black voxel with no normal and default material.
     */
    public VoxelData() {
        this.voxelData = new AtomicLong(0L);
        this.nativeSegment = null;
    }
    
    /**
     * Creates a new voxel data with the specified packed data value.
     * 
     * @param packedData The 64-bit packed representation of voxel attributes
     */
    public VoxelData(long packedData) {
        this.voxelData = new AtomicLong(packedData);
        this.nativeSegment = null;
    }
    
    /**
     * Creates a new voxel data with specified color components.
     * Normal defaults to (0,0,1), opacity to 255 (opaque), material to 0.
     * 
     * @param red Red component (0-255)
     * @param green Green component (0-255)
     * @param blue Blue component (0-255)
     * @throws IllegalArgumentException if any color component is out of range
     */
    public VoxelData(int red, int green, int blue) {
        this(red, green, blue, 255, 0);
    }
    
    /**
     * Creates a new voxel data with specified color and opacity.
     * Normal defaults to (0,0,1), material to 0.
     * 
     * @param red Red component (0-255)
     * @param green Green component (0-255)
     * @param blue Blue component (0-255)
     * @param opacity Opacity value (0-255)
     * @param materialId Material identifier (0-255)
     * @throws IllegalArgumentException if any component is out of range
     */
    public VoxelData(int red, int green, int blue, int opacity, int materialId) {
        validateComponentRange(red, "red");
        validateComponentRange(green, "green");
        validateComponentRange(blue, "blue");
        validateComponentRange(opacity, "opacity");
        validateComponentRange(materialId, "materialId");
        
        long packed = packComponents(red, green, blue, 127, 127, 255, opacity, materialId);
        this.voxelData = new AtomicLong(packed);
        this.nativeSegment = null;
    }
    
    /**
     * Creates a new voxel data with all specified attributes.
     * 
     * @param red Red component (0-255)
     * @param green Green component (0-255)
     * @param blue Blue component (0-255)
     * @param normal Surface normal vector (will be compressed to 8-bit components)
     * @param opacity Opacity value (0-255)
     * @param materialId Material identifier (0-255)
     * @throws IllegalArgumentException if any component is out of range or normal is null
     */
    public VoxelData(int red, int green, int blue, Vector3f normal, int opacity, int materialId) {
        if (normal == null) {
            throw new IllegalArgumentException("Normal vector cannot be null");
        }
        
        validateComponentRange(red, "red");
        validateComponentRange(green, "green");
        validateComponentRange(blue, "blue");
        validateComponentRange(opacity, "opacity");
        validateComponentRange(materialId, "materialId");
        
        int normalX = compressNormal(normal.x);
        int normalY = compressNormal(normal.y);
        int normalZ = compressNormal(normal.z);
        
        long packed = packComponents(red, green, blue, normalX, normalY, normalZ, opacity, materialId);
        this.voxelData = new AtomicLong(packed);
        this.nativeSegment = null;
    }
    
    /**
     * Creates a new voxel data from a native memory segment.
     * This constructor enables zero-copy deserialization from GPU buffers.
     * 
     * @param segment The memory segment containing the voxel data
     * @param offset The byte offset within the segment where the data begins
     * @throws IllegalArgumentException if the segment is too small or null
     */
    public VoxelData(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new IllegalArgumentException("Memory segment cannot be null");
        }
        if (segment.byteSize() < offset + VOXEL_DATA_SIZE_BYTES) {
            throw new IllegalArgumentException("Memory segment too small for voxel data");
        }
        
        long packedData = (long) VOXEL_DATA_HANDLE.get(segment, offset);
        this.voxelData = new AtomicLong(packedData);
        this.nativeSegment = segment.asSlice(offset, VOXEL_DATA_SIZE_BYTES);
    }
    
    // ================================================================================
    // Color Operations
    // ================================================================================
    
    /**
     * Returns the red color component.
     * 
     * @return Red component value (0-255)
     */
    public int getRed() {
        long data = voxelData.get();
        return (int) ((data & RED_MASK) >>> RED_SHIFT);
    }
    
    /**
     * Sets the red color component.
     * This operation is performed atomically.
     * 
     * @param red Red component value (0-255)
     * @throws IllegalArgumentException if red is out of range
     */
    public void setRed(int red) {
        validateComponentRange(red, "red");
        long mask = ((long) red) << RED_SHIFT;
        updateBits(RED_MASK, mask);
    }
    
    /**
     * Returns the green color component.
     * 
     * @return Green component value (0-255)
     */
    public int getGreen() {
        long data = voxelData.get();
        return (int) ((data & GREEN_MASK) >>> GREEN_SHIFT);
    }
    
    /**
     * Sets the green color component.
     * This operation is performed atomically.
     * 
     * @param green Green component value (0-255)
     * @throws IllegalArgumentException if green is out of range
     */
    public void setGreen(int green) {
        validateComponentRange(green, "green");
        long mask = ((long) green) << GREEN_SHIFT;
        updateBits(GREEN_MASK, mask);
    }
    
    /**
     * Returns the blue color component.
     * 
     * @return Blue component value (0-255)
     */
    public int getBlue() {
        long data = voxelData.get();
        return (int) ((data & BLUE_MASK) >>> BLUE_SHIFT);
    }
    
    /**
     * Sets the blue color component.
     * This operation is performed atomically.
     * 
     * @param blue Blue component value (0-255)
     * @throws IllegalArgumentException if blue is out of range
     */
    public void setBlue(int blue) {
        validateComponentRange(blue, "blue");
        long mask = ((long) blue) << BLUE_SHIFT;
        updateBits(BLUE_MASK, mask);
    }
    
    /**
     * Sets all RGB color components atomically.
     * This is more efficient than setting components individually.
     * 
     * @param red Red component (0-255)
     * @param green Green component (0-255)
     * @param blue Blue component (0-255)
     * @throws IllegalArgumentException if any component is out of range
     */
    public void setColor(int red, int green, int blue) {
        validateComponentRange(red, "red");
        validateComponentRange(green, "green");
        validateComponentRange(blue, "blue");
        
        long colorBits = (((long) red) << RED_SHIFT) |
                        (((long) green) << GREEN_SHIFT) |
                        (((long) blue) << BLUE_SHIFT);
        
        updateBits(COLOR_MASK, colorBits);
    }
    
    /**
     * Returns the packed RGB color value in standard format (0xRRGGBB).
     * This format is compatible with most graphics APIs and DXT compression.
     * 
     * @return 24-bit RGB color value
     */
    public int getPackedColor() {
        long data = voxelData.get();
        int red = (int) ((data & RED_MASK) >>> RED_SHIFT);
        int green = (int) ((data & GREEN_MASK) >>> GREEN_SHIFT);
        int blue = (int) ((data & BLUE_MASK) >>> BLUE_SHIFT);
        return (red << 16) | (green << 8) | blue;
    }
    
    /**
     * Sets the color from a packed RGB value (0xRRGGBB format).
     * This operation is performed atomically.
     * 
     * @param packedColor 24-bit RGB color value
     */
    public void setPackedColor(int packedColor) {
        int red = (packedColor >>> 16) & 0xFF;
        int green = (packedColor >>> 8) & 0xFF;
        int blue = packedColor & 0xFF;
        setColor(red, green, blue);
    }
    
    // ================================================================================
    // Normal Vector Operations
    // ================================================================================
    
    /**
     * Returns the compressed normal X component.
     * 
     * @return Compressed normal X value (0-255)
     */
    public int getNormalX() {
        long data = voxelData.get();
        return (int) ((data & NORMAL_X_MASK) >>> NORMAL_X_SHIFT);
    }
    
    /**
     * Returns the compressed normal Y component.
     * 
     * @return Compressed normal Y value (0-255)
     */
    public int getNormalY() {
        long data = voxelData.get();
        return (int) ((data & NORMAL_Y_MASK) >>> NORMAL_Y_SHIFT);
    }
    
    /**
     * Returns the compressed normal Z component.
     * 
     * @return Compressed normal Z value (0-255)
     */
    public int getNormalZ() {
        long data = voxelData.get();
        return (int) ((data & NORMAL_Z_MASK) >>> NORMAL_Z_SHIFT);
    }
    
    /**
     * Returns the decompressed surface normal as a Vector3f.
     * The normal is reconstructed from the compressed 8-bit components.
     * 
     * @return Surface normal vector with components in range [-1.0, 1.0]
     */
    public Vector3f getNormal() {
        long data = voxelData.get();
        int normalX = (int) ((data & NORMAL_X_MASK) >>> NORMAL_X_SHIFT);
        int normalY = (int) ((data & NORMAL_Y_MASK) >>> NORMAL_Y_SHIFT);
        int normalZ = (int) ((data & NORMAL_Z_MASK) >>> NORMAL_Z_SHIFT);
        
        float x = decompressNormal(normalX);
        float y = decompressNormal(normalY);
        float z = decompressNormal(normalZ);
        
        return new Vector3f(x, y, z);
    }
    
    /**
     * Sets the surface normal from a Vector3f.
     * The normal is compressed to 8-bit components and stored atomically.
     * 
     * @param normal Surface normal vector (should be normalized)
     * @throws IllegalArgumentException if normal is null
     */
    public void setNormal(Vector3f normal) {
        if (normal == null) {
            throw new IllegalArgumentException("Normal vector cannot be null");
        }
        
        int normalX = compressNormal(normal.x);
        int normalY = compressNormal(normal.y);
        int normalZ = compressNormal(normal.z);
        
        long normalBits = (((long) normalX) << NORMAL_X_SHIFT) |
                         (((long) normalY) << NORMAL_Y_SHIFT) |
                         (((long) normalZ) << NORMAL_Z_SHIFT);
        
        updateBits(NORMAL_MASK, normalBits);
    }
    
    /**
     * Sets the surface normal from individual components.
     * The components are compressed to 8-bit values and stored atomically.
     * 
     * @param x Normal X component (-1.0 to 1.0)
     * @param y Normal Y component (-1.0 to 1.0)
     * @param z Normal Z component (-1.0 to 1.0)
     */
    public void setNormal(float x, float y, float z) {
        int normalX = compressNormal(x);
        int normalY = compressNormal(y);
        int normalZ = compressNormal(z);
        
        long normalBits = (((long) normalX) << NORMAL_X_SHIFT) |
                         (((long) normalY) << NORMAL_Y_SHIFT) |
                         (((long) normalZ) << NORMAL_Z_SHIFT);
        
        updateBits(NORMAL_MASK, normalBits);
    }
    
    // ================================================================================
    // Opacity and Material Operations
    // ================================================================================
    
    /**
     * Returns the opacity value.
     * 
     * @return Opacity value (0-255, where 255 is fully opaque)
     */
    public int getOpacity() {
        long data = voxelData.get();
        return (int) ((data & OPACITY_MASK) >>> OPACITY_SHIFT);
    }
    
    /**
     * Sets the opacity value.
     * This operation is performed atomically.
     * 
     * @param opacity Opacity value (0-255)
     * @throws IllegalArgumentException if opacity is out of range
     */
    public void setOpacity(int opacity) {
        validateComponentRange(opacity, "opacity");
        long mask = ((long) opacity) << OPACITY_SHIFT;
        updateBits(OPACITY_MASK, mask);
    }
    
    /**
     * Returns the opacity as a normalized float value.
     * 
     * @return Opacity value (0.0-1.0, where 1.0 is fully opaque)
     */
    public float getOpacityFloat() {
        return getOpacity() / 255.0f;
    }
    
    /**
     * Sets the opacity from a normalized float value.
     * This operation is performed atomically.
     * 
     * @param opacity Opacity value (0.0-1.0)
     * @throws IllegalArgumentException if opacity is out of range
     */
    public void setOpacityFloat(float opacity) {
        if (opacity < 0.0f || opacity > 1.0f) {
            throw new IllegalArgumentException("Opacity must be in range [0.0, 1.0], got: " + opacity);
        }
        setOpacity((int) (opacity * 255.0f));
    }
    
    /**
     * Returns the material identifier.
     * 
     * @return Material ID (0-255)
     */
    public int getMaterialId() {
        long data = voxelData.get();
        return (int) ((data & MATERIAL_MASK) >>> MATERIAL_SHIFT);
    }
    
    /**
     * Sets the material identifier.
     * This operation is performed atomically.
     * 
     * @param materialId Material ID (0-255)
     * @throws IllegalArgumentException if material ID is out of range
     */
    public void setMaterialId(int materialId) {
        validateComponentRange(materialId, "materialId");
        long mask = ((long) materialId) << MATERIAL_SHIFT;
        updateBits(MATERIAL_MASK, mask);
    }
    
    /**
     * Checks if this voxel is transparent (opacity less than 255).
     * 
     * @return true if the voxel has any transparency, false if fully opaque
     */
    public boolean isTransparent() {
        return getOpacity() < MAX_COMPONENT_VALUE;
    }
    
    /**
     * Checks if this voxel is fully opaque.
     * 
     * @return true if opacity is 255, false otherwise
     */
    public boolean isOpaque() {
        return getOpacity() == MAX_COMPONENT_VALUE;
    }
    
    // ================================================================================
    // DXT Compression Support
    // ================================================================================
    
    /**
     * Returns color data formatted for DXT compression.
     * This extracts the RGB components in the order expected by DXT algorithms.
     * 
     * @return Array containing [red, green, blue] values (0-255 each)
     */
    public int[] getDXTColorData() {
        long data = voxelData.get();
        return new int[] {
            (int) ((data & RED_MASK) >>> RED_SHIFT),
            (int) ((data & GREEN_MASK) >>> GREEN_SHIFT),
            (int) ((data & BLUE_MASK) >>> BLUE_SHIFT)
        };
    }
    
    /**
     * Returns RGBA data formatted for DXT5 compression (with alpha channel).
     * This includes the opacity value as the alpha component.
     * 
     * @return Array containing [red, green, blue, alpha] values (0-255 each)
     */
    public int[] getDXT5ColorData() {
        long data = voxelData.get();
        return new int[] {
            (int) ((data & RED_MASK) >>> RED_SHIFT),
            (int) ((data & GREEN_MASK) >>> GREEN_SHIFT),
            (int) ((data & BLUE_MASK) >>> BLUE_SHIFT),
            (int) ((data & OPACITY_MASK) >>> OPACITY_SHIFT)
        };
    }
    
    /**
     * Returns the color in 16-bit RGB565 format used by DXT1 compression.
     * This format packs RGB into 16 bits: 5 bits red, 6 bits green, 5 bits blue.
     * 
     * @return 16-bit RGB565 color value
     */
    public short getDXTRGB565() {
        long data = voxelData.get();
        int red = (int) ((data & RED_MASK) >>> RED_SHIFT);
        int green = (int) ((data & GREEN_MASK) >>> GREEN_SHIFT);
        int blue = (int) ((data & BLUE_MASK) >>> BLUE_SHIFT);
        
        // Convert 8-bit components to RGB565 format
        int r5 = (red * 31) / 255;
        int g6 = (green * 63) / 255;
        int b5 = (blue * 31) / 255;
        
        return (short) ((r5 << 11) | (g6 << 5) | b5);
    }
    
    // ================================================================================
    // Serialization and Memory Operations
    // ================================================================================
    
    /**
     * Returns the complete 64-bit packed representation of this voxel data.
     * This value can be stored directly or used for serialization.
     * 
     * @return The packed voxel data as a 64-bit long value
     */
    public long getPackedData() {
        return voxelData.get();
    }
    
    /**
     * Sets the complete voxel data from a 64-bit packed value.
     * This operation is performed atomically.
     * 
     * @param packedData The packed voxel data
     */
    public void setPackedData(long packedData) {
        voxelData.set(packedData);
        syncToNativeMemory();
    }
    
    /**
     * Serializes this voxel data to the specified memory segment at the given offset.
     * This enables zero-copy transfer to GPU buffers.
     * 
     * @param segment The target memory segment
     * @param offset The byte offset within the segment
     * @throws IllegalArgumentException if the segment is too small or null
     */
    public void serializeTo(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new IllegalArgumentException("Memory segment cannot be null");
        }
        if (segment.byteSize() < offset + VOXEL_DATA_SIZE_BYTES) {
            throw new IllegalArgumentException("Memory segment too small for voxel data");
        }
        
        long data = voxelData.get();
        VOXEL_DATA_HANDLE.set(segment, offset, data);
    }
    
    /**
     * Deserializes this voxel data from the specified memory segment at the given offset.
     * This enables zero-copy loading from GPU buffers or memory-mapped files.
     * 
     * @param segment The source memory segment
     * @param offset The byte offset within the segment
     * @throws IllegalArgumentException if the segment is too small or null
     */
    public void deserializeFrom(MemorySegment segment, long offset) {
        if (segment == null) {
            throw new IllegalArgumentException("Memory segment cannot be null");
        }
        if (segment.byteSize() < offset + VOXEL_DATA_SIZE_BYTES) {
            throw new IllegalArgumentException("Memory segment too small for voxel data");
        }
        
        long data = (long) VOXEL_DATA_HANDLE.get(segment, offset);
        voxelData.set(data);
        
        // Update native segment reference if applicable
        this.nativeSegment = segment.asSlice(offset, VOXEL_DATA_SIZE_BYTES);
    }
    
    /**
     * Creates a native memory segment containing this voxel data.
     * The returned segment is allocated in the specified arena.
     * 
     * @param arena The memory arena for allocation
     * @return A new memory segment containing this voxel data
     */
    public MemorySegment toNativeMemory(Arena arena) {
        MemorySegment segment = arena.allocate(MEMORY_LAYOUT);
        serializeTo(segment, 0);
        return segment;
    }
    
    /**
     * Returns a reference to the native memory segment if available.
     * This segment shares the same data as this voxel and can be used for GPU operations.
     * 
     * @return The native memory segment, or null if not available
     */
    public MemorySegment getNativeSegment() {
        return nativeSegment;
    }
    
    // ================================================================================
    // Utility and Helper Methods
    // ================================================================================
    
    /**
     * Creates a copy of this voxel data with identical attributes.
     * 
     * @return A new VoxelData instance with the same data
     */
    public VoxelData copy() {
        return new VoxelData(voxelData.get());
    }
    
    /**
     * Resets this voxel data to empty state (all attributes set to zero).
     * This operation is performed atomically.
     */
    public void clear() {
        voxelData.set(0L);
        syncToNativeMemory();
    }
    
    /**
     * Checks if this voxel data is completely empty (all attributes are zero).
     * 
     * @return true if the voxel is empty, false otherwise
     */
    public boolean isEmpty() {
        return voxelData.get() == 0L;
    }
    
    /**
     * Checks if this voxel has a valid color (any RGB component is non-zero).
     * 
     * @return true if the voxel has color data, false if pure black
     */
    public boolean hasColor() {
        long data = voxelData.get();
        return (data & COLOR_MASK) != 0L;
    }
    
    /**
     * Interpolates between this voxel data and another voxel data.
     * All components are interpolated linearly.
     * 
     * @param other The other voxel data to interpolate with
     * @param t Interpolation parameter (0.0 = this voxel, 1.0 = other voxel)
     * @return A new VoxelData representing the interpolated result
     * @throws IllegalArgumentException if other is null or t is out of range
     */
    public VoxelData interpolate(VoxelData other, float t) {
        if (other == null) {
            throw new IllegalArgumentException("Other voxel data cannot be null");
        }
        if (t < 0.0f || t > 1.0f) {
            throw new IllegalArgumentException("Interpolation parameter must be in range [0.0, 1.0], got: " + t);
        }
        
        float oneMinusT = 1.0f - t;
        
        int red = (int) (getRed() * oneMinusT + other.getRed() * t);
        int green = (int) (getGreen() * oneMinusT + other.getGreen() * t);
        int blue = (int) (getBlue() * oneMinusT + other.getBlue() * t);
        
        Vector3f thisNormal = getNormal();
        Vector3f otherNormal = other.getNormal();
        Vector3f interpNormal = new Vector3f(
            thisNormal.x * oneMinusT + otherNormal.x * t,
            thisNormal.y * oneMinusT + otherNormal.y * t,
            thisNormal.z * oneMinusT + otherNormal.z * t
        );
        interpNormal.normalize();
        
        int opacity = (int) (getOpacity() * oneMinusT + other.getOpacity() * t);
        
        // Material ID uses nearest neighbor (no interpolation)
        int materialId = (t < 0.5f) ? getMaterialId() : other.getMaterialId();
        
        return new VoxelData(red, green, blue, interpNormal, opacity, materialId);
    }
    
    // ================================================================================
    // Object Override Methods
    // ================================================================================
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        VoxelData other = (VoxelData) obj;
        return voxelData.get() == other.voxelData.get();
    }
    
    @Override
    public int hashCode() {
        return Long.hashCode(voxelData.get());
    }
    
    @Override
    public String toString() {
        long data = voxelData.get();
        return String.format("VoxelData{" +
            "color=(%d,%d,%d), normal=(%d,%d,%d), opacity=%d, material=%d, packed=0x%016X}",
            getRed(), getGreen(), getBlue(),
            getNormalX(), getNormalY(), getNormalZ(),
            getOpacity(), getMaterialId(), data);
    }
    
    // ================================================================================
    // Private Helper Methods
    // ================================================================================
    
    /**
     * Validates that a component value is in the valid range [0, 255].
     * 
     * @param value The component value to validate
     * @param componentName The name of the component for error messages
     * @throws IllegalArgumentException if the value is out of range
     */
    private static void validateComponentRange(int value, String componentName) {
        if (value < 0 || value > MAX_COMPONENT_VALUE) {
            throw new IllegalArgumentException(
                componentName + " must be in range [0, 255], got: " + value);
        }
    }
    
    /**
     * Compresses a normal component from float (-1.0 to 1.0) to int (0 to 255).
     * 
     * @param normal The normal component to compress
     * @return Compressed normal value (0-255)
     */
    private static int compressNormal(float normal) {
        // Clamp to valid range
        float clamped = Math.max(-1.0f, Math.min(1.0f, normal));
        return Math.round((clamped + NORMAL_OFFSET) * NORMAL_SCALE);
    }
    
    /**
     * Decompresses a normal component from int (0 to 255) to float (-1.0 to 1.0).
     * 
     * @param compressed The compressed normal value
     * @return Decompressed normal component
     */
    private static float decompressNormal(int compressed) {
        float decompressed = (compressed / NORMAL_SCALE) - NORMAL_OFFSET;
        // Clamp to valid range to handle rounding at boundaries
        return Math.max(-1.0f, Math.min(1.0f, decompressed));
    }
    
    /**
     * Packs all voxel components into a single 64-bit value.
     * 
     * @param red Red component (0-255)
     * @param green Green component (0-255)
     * @param blue Blue component (0-255)
     * @param normalX Compressed normal X component (0-255)
     * @param normalY Compressed normal Y component (0-255)
     * @param normalZ Compressed normal Z component (0-255)
     * @param opacity Opacity value (0-255)
     * @param materialId Material identifier (0-255)
     * @return Packed 64-bit representation
     */
    private static long packComponents(int red, int green, int blue, int normalX, int normalY, 
                                      int normalZ, int opacity, int materialId) {
        return (((long) red) << RED_SHIFT) |
               (((long) green) << GREEN_SHIFT) |
               (((long) blue) << BLUE_SHIFT) |
               (((long) normalX) << NORMAL_X_SHIFT) |
               (((long) normalY) << NORMAL_Y_SHIFT) |
               (((long) normalZ) << NORMAL_Z_SHIFT) |
               (((long) opacity) << OPACITY_SHIFT) |
               (((long) materialId) << MATERIAL_SHIFT);
    }
    
    /**
     * Atomically updates specific bits in the voxel data.
     * 
     * @param clearMask The mask of bits to clear
     * @param setBits The bits to set after clearing
     */
    private void updateBits(long clearMask, long setBits) {
        voxelData.updateAndGet(data -> (data & ~clearMask) | (setBits & clearMask));
        syncToNativeMemory();
    }
    
    /**
     * Synchronizes the voxel data to native memory if a native segment is available.
     * This ensures consistency between Java and native memory representations.
     */
    private void syncToNativeMemory() {
        MemorySegment segment = nativeSegment;
        if (segment != null) {
            VOXEL_DATA_HANDLE.set(segment, 0L, voxelData.get());
        }
    }
}