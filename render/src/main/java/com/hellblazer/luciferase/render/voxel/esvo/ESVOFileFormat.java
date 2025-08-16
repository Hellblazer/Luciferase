package com.hellblazer.luciferase.render.voxel.esvo;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * ESVO file format reader/writer.
 * 
 * File structure:
 * - Header (24 bytes)
 * - Object entries (variable)
 * - Slice info array (60 bytes each)
 * - Slice data (pages with nodes)
 * - Attachment data (colors, normals, etc.)
 * 
 * Compatible with NVIDIA ESVO octree file format.
 */
public class ESVOFileFormat {
    
    // Format constants
    public static final String FORMAT_ID = "Octree  "; // 8 chars with spaces
    public static final int FORMAT_VERSION = 1;
    
    // Size constants
    public static final int HEADER_SIZE = 24;
    public static final int OBJECT_ENTRY_BASE_SIZE = 136;
    public static final int SLICE_INFO_SIZE = 60;
    public static final int ATTACHMENT_INFO_SIZE = 12;
    
    // Octree construction parameters
    public static final int AVG_NODES_PER_SLICE = 49152;
    public static final int MAX_NODES_PER_BLOCK = 983040;
    public static final int FORCE_SPLIT_LEVELS = 2;
    
    // Slice states
    public enum SliceState {
        UNLOADED(0),
        LOADING(1),
        LOADED(2),
        UNLOADING(3);
        
        public final int value;
        
        SliceState(int value) {
            this.value = value;
        }
        
        public static SliceState fromValue(int value) {
            for (SliceState state : values()) {
                if (state.value == value) return state;
            }
            return UNLOADED;
        }
    }
    
    // Attachment types
    public enum AttachType {
        VOID(0),
        COLOR_NORMAL_DXT(1),
        COLOR_NORMAL_PALETTE(3),
        BUILD_DATA(4),
        CONTOUR(6),
        COLOR_NORMAL_CORNER(7),
        AMBIENT_OCCLUSION(10);
        
        public final int value;
        
        AttachType(int value) {
            this.value = value;
        }
        
        public static AttachType fromValue(int value) {
            for (AttachType type : values()) {
                if (type.value == value) return type;
            }
            return VOID;
        }
    }
    
    /**
     * File header structure.
     */
    public static class Header {
        public String formatId = FORMAT_ID;
        public int version = FORMAT_VERSION;
        public int numObjects;
        public int numSlices;
        public int reserved;
        
        public void writeTo(ByteBuffer buffer) {
            buffer.put(formatId.getBytes());
            buffer.putInt(version);
            buffer.putInt(numObjects);
            buffer.putInt(numSlices);
            buffer.putInt(reserved);
        }
        
        public static Header readFrom(ByteBuffer buffer) throws IOException {
            var header = new Header();
            
            byte[] idBytes = new byte[8];
            buffer.get(idBytes);
            header.formatId = new String(idBytes);
            
            if (!FORMAT_ID.equals(header.formatId)) {
                throw new IOException("Invalid format ID: " + header.formatId);
            }
            
            header.version = buffer.getInt();
            header.numObjects = buffer.getInt();
            header.numSlices = buffer.getInt();
            header.reserved = buffer.getInt();
            
            return header;
        }
    }
    
    /**
     * Object entry in the octree file.
     */
    public static class ObjectEntry {
        public float[] objectToWorld = new float[16];
        public float[] octreeToObject = new float[16];
        public int rootSliceId = -1;
        public int[] attachmentTypes = new int[0];
        
        public void writeTo(ByteBuffer buffer) {
            // Write matrices
            for (float f : objectToWorld) {
                buffer.putFloat(f);
            }
            for (float f : octreeToObject) {
                buffer.putFloat(f);
            }
            
            // Write root slice
            buffer.putInt(rootSliceId);
            
            // Write attachment count and types
            buffer.putInt(attachmentTypes.length);
            for (int type : attachmentTypes) {
                buffer.putInt(type);
            }
        }
        
        public static ObjectEntry readFrom(ByteBuffer buffer) {
            var obj = new ObjectEntry();
            
            // Read matrices
            for (int i = 0; i < 16; i++) {
                obj.objectToWorld[i] = buffer.getFloat();
            }
            for (int i = 0; i < 16; i++) {
                obj.octreeToObject[i] = buffer.getFloat();
            }
            
            // Read root slice
            obj.rootSliceId = buffer.getInt();
            
            // Read attachments
            int numAttach = buffer.getInt();
            obj.attachmentTypes = new int[numAttach];
            for (int i = 0; i < numAttach; i++) {
                obj.attachmentTypes[i] = buffer.getInt();
            }
            
            return obj;
        }
        
        public int getSizeInBytes() {
            return OBJECT_ENTRY_BASE_SIZE + attachmentTypes.length * 4;
        }
    }
    
    /**
     * Slice information structure.
     */
    public static class SliceInfo {
        public int id;
        public SliceState state = SliceState.UNLOADED;
        public int[] cubePos = new int[3];
        public int cubeScale;
        public int nodeScale;
        public int numChildEntries;
        public int childEntryPtr;
        public int numAttach;
        public int attachInfoPtr;
        public int numNodes;
        public int nodeSplitPtr;
        public int numSplitNodes;
        public int nodeValidMaskPtr;
        
        public void writeTo(ByteBuffer buffer) {
            buffer.putInt(id);
            buffer.putInt(state.value);
            buffer.putInt(cubePos[0]);
            buffer.putInt(cubePos[1]);
            buffer.putInt(cubePos[2]);
            buffer.putInt(cubeScale);
            buffer.putInt(nodeScale);
            buffer.putInt(numChildEntries);
            buffer.putInt(childEntryPtr);
            buffer.putInt(numAttach);
            buffer.putInt(attachInfoPtr);
            buffer.putInt(numNodes);
            buffer.putInt(nodeSplitPtr);
            buffer.putInt(numSplitNodes);
            buffer.putInt(nodeValidMaskPtr);
        }
        
        public static SliceInfo readFrom(ByteBuffer buffer) {
            var info = new SliceInfo();
            
            info.id = buffer.getInt();
            info.state = SliceState.fromValue(buffer.getInt());
            info.cubePos[0] = buffer.getInt();
            info.cubePos[1] = buffer.getInt();
            info.cubePos[2] = buffer.getInt();
            info.cubeScale = buffer.getInt();
            info.nodeScale = buffer.getInt();
            info.numChildEntries = buffer.getInt();
            info.childEntryPtr = buffer.getInt();
            info.numAttach = buffer.getInt();
            info.attachInfoPtr = buffer.getInt();
            info.numNodes = buffer.getInt();
            info.nodeSplitPtr = buffer.getInt();
            info.numSplitNodes = buffer.getInt();
            info.nodeValidMaskPtr = buffer.getInt();
            
            return info;
        }
    }
    
    /**
     * Attachment information.
     */
    public static class AttachmentInfo {
        public AttachType type;
        public int dataOffset;
        public int dataSize;
        
        public void writeTo(ByteBuffer buffer) {
            buffer.putInt(type.value);
            buffer.putInt(dataOffset);
            buffer.putInt(dataSize);
        }
        
        public static AttachmentInfo readFrom(ByteBuffer buffer) {
            var info = new AttachmentInfo();
            info.type = AttachType.fromValue(buffer.getInt());
            info.dataOffset = buffer.getInt();
            info.dataSize = buffer.getInt();
            return info;
        }
    }
    
    /**
     * Complete slice data including info and page.
     */
    public static class SliceData {
        public SliceInfo info;
        public ESVOPage page;
        public List<AttachmentInfo> attachments = new ArrayList<>();
    }
    
    /**
     * Complete octree data structure.
     */
    public static class OctreeData {
        public Header header;
        public ObjectEntry[] objects;
        public SliceData[] slices;
    }
    
    // File I/O methods
    
    /**
     * Writes a complete octree file.
     */
    public void writeOctreeFile(Path path, OctreeData data) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw");
             FileChannel channel = file.getChannel()) {
            
            // Calculate offsets
            int headerOffset = 0;
            int objectsOffset = HEADER_SIZE;
            int objectsSize = 0;
            for (var obj : data.objects) {
                objectsSize += obj.getSizeInBytes();
            }
            
            int sliceInfoOffset = objectsOffset + objectsSize;
            int sliceInfoSize = data.slices.length * SLICE_INFO_SIZE;
            
            int sliceDataOffset = sliceInfoOffset + sliceInfoSize;
            
            // Allocate buffer for header and metadata
            ByteBuffer metaBuffer = ByteBuffer.allocate(sliceDataOffset);
            metaBuffer.order(ByteOrder.LITTLE_ENDIAN);
            
            // Write header
            data.header.writeTo(metaBuffer);
            
            // Write objects
            for (var obj : data.objects) {
                obj.writeTo(metaBuffer);
            }
            
            // Write slice infos
            for (var slice : data.slices) {
                slice.info.writeTo(metaBuffer);
            }
            
            // Write metadata to file
            metaBuffer.flip();
            channel.write(metaBuffer, 0);
            
            // Write slice data (pages)
            long currentOffset = sliceDataOffset;
            for (var slice : data.slices) {
                if (slice.page != null) {
                    ByteBuffer pageBuffer = ByteBuffer.allocateDirect(ESVOPage.PAGE_BYTES);
                    pageBuffer.order(ByteOrder.LITTLE_ENDIAN);
                    slice.page.writeTo(pageBuffer);
                    pageBuffer.flip();
                    channel.write(pageBuffer, currentOffset);
                    currentOffset += ESVOPage.PAGE_BYTES;
                }
            }
        }
    }
    
    /**
     * Reads a complete octree file.
     */
    public OctreeData readOctreeFile(Path path, Arena arena) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
             FileChannel channel = file.getChannel()) {
            
            var data = new OctreeData();
            
            // Read header
            ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            channel.read(headerBuffer, 0);
            headerBuffer.flip();
            data.header = Header.readFrom(headerBuffer);
            
            // Read objects
            data.objects = new ObjectEntry[data.header.numObjects];
            long offset = HEADER_SIZE;
            
            for (int i = 0; i < data.header.numObjects; i++) {
                // Read base size first to determine attachment count
                ByteBuffer objBuffer = ByteBuffer.allocate(OBJECT_ENTRY_BASE_SIZE + 256);
                objBuffer.order(ByteOrder.LITTLE_ENDIAN);
                channel.read(objBuffer, offset);
                objBuffer.flip();
                
                data.objects[i] = ObjectEntry.readFrom(objBuffer);
                offset += data.objects[i].getSizeInBytes();
            }
            
            // Read slice infos
            data.slices = new SliceData[data.header.numSlices];
            ByteBuffer sliceInfoBuffer = ByteBuffer.allocate(data.header.numSlices * SLICE_INFO_SIZE);
            sliceInfoBuffer.order(ByteOrder.LITTLE_ENDIAN);
            channel.read(sliceInfoBuffer, offset);
            sliceInfoBuffer.flip();
            
            for (int i = 0; i < data.header.numSlices; i++) {
                data.slices[i] = new SliceData();
                data.slices[i].info = SliceInfo.readFrom(sliceInfoBuffer);
            }
            
            offset += data.header.numSlices * SLICE_INFO_SIZE;
            
            // Read slice pages
            for (var slice : data.slices) {
                if (slice.info.numNodes > 0) {
                    ByteBuffer pageBuffer = ByteBuffer.allocateDirect(ESVOPage.PAGE_BYTES);
                    pageBuffer.order(ByteOrder.LITTLE_ENDIAN);
                    channel.read(pageBuffer, offset);
                    pageBuffer.flip();
                    
                    slice.page = ESVOPage.readFrom(pageBuffer, arena);
                    offset += ESVOPage.PAGE_BYTES;
                }
            }
            
            return data;
        }
    }
    
    /**
     * Writes just the header.
     */
    public void writeHeader(Path path, Header header) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        header.writeTo(buffer);
        buffer.flip();
        
        Files.write(path, buffer.array(), 
                   StandardOpenOption.CREATE,
                   StandardOpenOption.WRITE,
                   StandardOpenOption.TRUNCATE_EXISTING);
    }
    
    /**
     * Reads just the header.
     */
    public Header readHeader(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return Header.readFrom(buffer);
    }
}