package com.hellblazer.luciferase.render.io;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Unified voxel file format specification.
 * 
 * File Structure:
 * - Header (64 bytes)
 * - Metadata section (variable)
 * - LOD hierarchy table
 * - Compressed voxel data chunks
 * - Material/texture data
 * - Index/offset tables
 * 
 * Supports:
 * - Multiple compression methods
 * - Streaming and memory-mapped access
 * - Level-of-detail hierarchies
 * - Material properties
 */
public class VoxelFileFormat {
    
    // Magic number: "VOXL"
    public static final int MAGIC = 0x564F584C;
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 72; // 4 + 4 + 4 + 8 + 4 + 8 + 24 + 4 + 4 + 4 + 4
    
    public enum ChunkType {
        METADATA(0x4D455441),      // "META"
        VOXEL_DATA(0x564F5845),    // "VOXE"
        MATERIAL(0x4D41544C),      // "MATL"
        LOD_TABLE(0x4C4F4454),     // "LODT"
        INDEX(0x494E4458),         // "INDX"
        TEXTURE(0x54455854),       // "TEXT"
        ANIMATION(0x414E494D);     // "ANIM"
        
        private final int id;
        
        ChunkType(int id) {
            this.id = id;
        }
        
        public int getId() { return id; }
        
        public static ChunkType fromId(int id) {
            for (ChunkType type : values()) {
                if (type.id == id) return type;
            }
            return null;
        }
    }
    
    public enum CompressionType {
        NONE(0),
        ZLIB(1),
        LZ4(2),
        ZSTD(3),
        DXT1(4),
        DXT5(5),
        CUSTOM_SVO(6);
        
        private final int code;
        
        CompressionType(int code) {
            this.code = code;
        }
        
        public int getCode() { return code; }
        
        public static CompressionType fromCode(int code) {
            for (CompressionType type : values()) {
                if (type.code == code) return type;
            }
            return NONE;
        }
    }
    
    public static class Header {
        public int magic;
        public int version;
        public int flags;
        public long timestamp;
        public int chunkCount;
        public long totalSize;
        public float[] bounds = new float[6]; // min xyz, max xyz
        public int voxelResolution;
        public int lodLevels;
        public int compressionType;
        public int reserved;
        
        public void write(ByteBuffer buffer) {
            buffer.putInt(magic);
            buffer.putInt(version);
            buffer.putInt(flags);
            buffer.putLong(timestamp);
            buffer.putInt(chunkCount);
            buffer.putLong(totalSize);
            for (float b : bounds) {
                buffer.putFloat(b);
            }
            buffer.putInt(voxelResolution);
            buffer.putInt(lodLevels);
            buffer.putInt(compressionType);
            buffer.putInt(reserved);
        }
        
        public static Header read(ByteBuffer buffer) {
            Header header = new Header();
            header.magic = buffer.getInt();
            header.version = buffer.getInt();
            header.flags = buffer.getInt();
            header.timestamp = buffer.getLong();
            header.chunkCount = buffer.getInt();
            header.totalSize = buffer.getLong();
            for (int i = 0; i < 6; i++) {
                header.bounds[i] = buffer.getFloat();
            }
            header.voxelResolution = buffer.getInt();
            header.lodLevels = buffer.getInt();
            header.compressionType = buffer.getInt();
            header.reserved = buffer.getInt();
            return header;
        }
        
        public boolean isValid() {
            return magic == MAGIC && version <= VERSION;
        }
    }
    
    public static class Chunk {
        public ChunkType type;
        public int size;
        public int compressedSize;
        public int compressionType;
        public long offset;
        public ByteBuffer data;
        
        public void writeHeader(ByteBuffer buffer) {
            buffer.putInt(type.getId());
            buffer.putInt(size);
            buffer.putInt(compressedSize);
            buffer.putInt(compressionType);
            buffer.putLong(offset);
        }
        
        public static Chunk readHeader(ByteBuffer buffer) {
            Chunk chunk = new Chunk();
            chunk.type = ChunkType.fromId(buffer.getInt());
            chunk.size = buffer.getInt();
            chunk.compressedSize = buffer.getInt();
            chunk.compressionType = buffer.getInt();
            chunk.offset = buffer.getLong();
            return chunk;
        }
    }
    
    public static class Metadata {
        public String name;
        public String description;
        public String author;
        public Map<String, String> properties = new HashMap<>();
        
        public ByteBuffer serialize() {
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            writeString(buffer, name);
            writeString(buffer, description);
            writeString(buffer, author);
            
            buffer.putInt(properties.size());
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                writeString(buffer, entry.getKey());
                writeString(buffer, entry.getValue());
            }
            
            buffer.flip();
            return buffer;
        }
        
        public static Metadata deserialize(ByteBuffer buffer) {
            Metadata meta = new Metadata();
            meta.name = readString(buffer);
            meta.description = readString(buffer);
            meta.author = readString(buffer);
            
            int propCount = buffer.getInt();
            for (int i = 0; i < propCount; i++) {
                String key = readString(buffer);
                String value = readString(buffer);
                meta.properties.put(key, value);
            }
            
            return meta;
        }
        
        private static void writeString(ByteBuffer buffer, String str) {
            if (str == null) {
                buffer.putInt(0);
            } else {
                byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
                buffer.putInt(bytes.length);
                buffer.put(bytes);
            }
        }
        
        private static String readString(ByteBuffer buffer) {
            int length = buffer.getInt();
            if (length == 0) return null;
            
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    
    public static class LODEntry {
        public int level;
        public int resolution;
        public long dataOffset;
        public int dataSize;
        public float errorMetric;
        
        public void write(ByteBuffer buffer) {
            buffer.putInt(level);
            buffer.putInt(resolution);
            buffer.putLong(dataOffset);
            buffer.putInt(dataSize);
            buffer.putFloat(errorMetric);
        }
        
        public static LODEntry read(ByteBuffer buffer) {
            LODEntry entry = new LODEntry();
            entry.level = buffer.getInt();
            entry.resolution = buffer.getInt();
            entry.dataOffset = buffer.getLong();
            entry.dataSize = buffer.getInt();
            entry.errorMetric = buffer.getFloat();
            return entry;
        }
    }
    
    public static class MaterialData {
        public int id;
        public String name;
        public float[] diffuseColor = new float[4];
        public float[] specularColor = new float[4];
        public float roughness;
        public float metallic;
        public float transparency;
        public int textureId;
        
        public ByteBuffer serialize() {
            ByteBuffer buffer = ByteBuffer.allocate(256);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            buffer.putInt(id);
            writeString(buffer, name);
            for (float c : diffuseColor) buffer.putFloat(c);
            for (float c : specularColor) buffer.putFloat(c);
            buffer.putFloat(roughness);
            buffer.putFloat(metallic);
            buffer.putFloat(transparency);
            buffer.putInt(textureId);
            
            buffer.flip();
            return buffer;
        }
        
        public static MaterialData deserialize(ByteBuffer buffer) {
            MaterialData mat = new MaterialData();
            mat.id = buffer.getInt();
            mat.name = readString(buffer);
            for (int i = 0; i < 4; i++) mat.diffuseColor[i] = buffer.getFloat();
            for (int i = 0; i < 4; i++) mat.specularColor[i] = buffer.getFloat();
            mat.roughness = buffer.getFloat();
            mat.metallic = buffer.getFloat();
            mat.transparency = buffer.getFloat();
            mat.textureId = buffer.getInt();
            return mat;
        }
        
        private static void writeString(ByteBuffer buffer, String str) {
            if (str == null) {
                buffer.putInt(0);
            } else {
                byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
                buffer.putInt(bytes.length);
                buffer.put(bytes);
            }
        }
        
        private static String readString(ByteBuffer buffer) {
            int length = buffer.getInt();
            if (length == 0) return null;
            
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    
    /**
     * Create a new voxel file header.
     */
    public static Header createHeader() {
        Header header = new Header();
        header.magic = MAGIC;
        header.version = VERSION;
        header.timestamp = System.currentTimeMillis();
        return header;
    }
    
    /**
     * Validate file format.
     */
    public static boolean isValidFile(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            return false;
        }
        
        int magic = buffer.getInt(0);
        int version = buffer.getInt(4);
        
        return magic == MAGIC && version <= VERSION;
    }
    
    /**
     * Calculate chunk checksum for integrity verification.
     */
    public static int calculateChecksum(ByteBuffer data) {
        int checksum = 0;
        int position = data.position();
        
        while (data.hasRemaining()) {
            checksum = Integer.rotateLeft(checksum, 1) ^ data.get();
        }
        
        data.position(position);
        return checksum;
    }
}