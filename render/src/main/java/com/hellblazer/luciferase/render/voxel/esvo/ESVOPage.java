package com.hellblazer.luciferase.render.voxel.esvo;

import java.lang.foreign.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * ESVO-compatible 8KB memory page for octree storage.
 * 
 * Page layout:
 * - Header (32 bytes): metadata about page contents
 * - Node data: ESVONode instances (8 bytes each)
 * - Far pointers: 32-bit addresses for far references
 * - Attachment data: colors, normals, contours, etc.
 * 
 * Pages are 8KB aligned for efficient GPU access and caching.
 */
public class ESVOPage {
    
    // Page constants
    public static final int PAGE_BYTES_LOG2 = 13;
    public static final int PAGE_BYTES = 1 << PAGE_BYTES_LOG2; // 8192 bytes
    public static final int PAGE_SIZE_DWORDS = PAGE_BYTES / 4; // 2048 dwords
    
    // Header size
    private static final int HEADER_SIZE = 32; // bytes
    
    // Maximum nodes per page (conservative estimate)
    public static final int MAX_NODES_PER_PAGE = (PAGE_BYTES - HEADER_SIZE) / 8;
    
    // Header offsets (in bytes)
    private static final int OFFSET_NODE_COUNT = 0;
    private static final int OFFSET_FAR_PTR_COUNT = 4;
    private static final int OFFSET_ATTACH_SIZE = 8;
    private static final int OFFSET_NEXT_PAGE = 12;
    private static final int OFFSET_FLAGS = 16;
    private static final int OFFSET_RESERVED = 20;
    
    // Memory storage
    private final MemorySegment memory;
    private final Arena arena;
    private final boolean ownedArena;
    
    // Dynamic tracking
    private int nodeOffset = HEADER_SIZE;
    private int farPointerOffset;
    private int attachmentOffset;
    private final List<Integer> farPointers = new ArrayList<>();
    
    /**
     * Creates a new ESVO page with its own arena.
     * 
     * @param arena Arena for memory allocation
     */
    public ESVOPage(Arena arena) {
        this.arena = arena;
        this.ownedArena = false;
        this.memory = arena.allocate(PAGE_BYTES, PAGE_BYTES); // 8KB aligned
        initialize();
    }
    
    /**
     * Creates a page from existing memory.
     * 
     * @param memory Memory segment of exactly 8KB
     */
    public ESVOPage(MemorySegment memory) {
        if (memory.byteSize() != PAGE_BYTES) {
            throw new IllegalArgumentException("Memory must be exactly 8KB");
        }
        this.memory = memory;
        this.arena = null;
        this.ownedArena = false;
        loadFromMemory();
    }
    
    /**
     * Creates a page from a ByteBuffer.
     * 
     * @param buffer Buffer with page data
     * @param arena Arena for allocation
     * @return New ESVOPage instance
     */
    public static ESVOPage readFrom(ByteBuffer buffer, Arena arena) {
        var page = new ESVOPage(arena);
        
        // Read all data into memory segment
        byte[] data = new byte[PAGE_BYTES];
        buffer.get(data);
        MemorySegment.copy(data, 0, page.memory, ValueLayout.JAVA_BYTE, 0, PAGE_BYTES);
        
        page.loadFromMemory();
        return page;
    }
    
    /**
     * Writes this page to a ByteBuffer.
     * 
     * @param buffer Buffer to write to (must have 8KB remaining)
     */
    public void writeTo(ByteBuffer buffer) {
        syncToMemory();
        
        // Write all data in one operation
        for (int i = 0; i < PAGE_BYTES; i++) {
            buffer.put(memory.get(ValueLayout.JAVA_BYTE, i));
        }
    }
    
    // Initialization
    
    private void initialize() {
        // Clear memory
        memory.fill((byte) 0);
        
        // Set initial offsets
        nodeOffset = HEADER_SIZE;
        attachmentOffset = PAGE_BYTES; // Attachments start from end
        farPointerOffset = PAGE_BYTES; // Far pointers also from end, but tracked separately
        
        // Initialize header
        setNodeCount(0);
        setFarPointerCount(0);
        setAttachmentSize(0);
        setNextPageOffset(0);
    }
    
    private void loadFromMemory() {
        // Load header
        int nodeCount = getNodeCount();
        int farCount = getFarPointerCount();
        int attachSize = getAttachmentSize();
        
        // Calculate offsets
        nodeOffset = HEADER_SIZE + nodeCount * 8;
        attachmentOffset = PAGE_BYTES - attachSize;
        
        // Far pointers are stored just before attachments
        if (farCount > 0) {
            farPointerOffset = attachmentOffset - farCount * 4;
            // Load far pointers
            farPointers.clear();
            for (int i = 0; i < farCount; i++) {
                farPointers.add(memory.get(ValueLayout.JAVA_INT, farPointerOffset + i * 4));
            }
        } else {
            farPointerOffset = attachmentOffset;
        }
    }
    
    private void syncToMemory() {
        // Update header
        setNodeCount((nodeOffset - HEADER_SIZE) / 8);
        setFarPointerCount(farPointers.size());
        setAttachmentSize(PAGE_BYTES - attachmentOffset);
        
        // Write far pointers - they're stored in the space just before attachments
        if (farPointers.size() > 0) {
            int actualFarPtrOffset = attachmentOffset - farPointers.size() * 4;
            for (int i = 0; i < farPointers.size(); i++) {
                memory.set(ValueLayout.JAVA_INT, actualFarPtrOffset + i * 4, farPointers.get(i));
            }
        }
    }
    
    // Node operations
    
    /**
     * Allocates space for a new node.
     * 
     * @return Offset of the allocated node, or -1 if no space
     */
    public int allocateNode() {
        if (nodeOffset + 8 > farPointerOffset) {
            return -1; // No space
        }
        
        int offset = nodeOffset - HEADER_SIZE;
        nodeOffset += 8;
        setNodeCount((nodeOffset - HEADER_SIZE) / 8);
        return offset;
    }
    
    /**
     * Writes a node at the specified offset.
     * 
     * @param offset Offset returned by allocateNode
     * @param node Node to write
     */
    public void writeNode(int offset, ESVONode node) {
        int actualOffset = HEADER_SIZE + offset;
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        node.writeTo(buffer);
        buffer.flip();
        
        memory.set(ValueLayout.JAVA_LONG, actualOffset, buffer.getLong());
    }
    
    /**
     * Reads a node from the specified offset.
     * 
     * @param offset Offset of the node
     * @return ESVONode instance
     */
    public ESVONode readNode(int offset) {
        int actualOffset = HEADER_SIZE + offset;
        long data = memory.get(ValueLayout.JAVA_LONG, actualOffset);
        
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(data);
        buffer.flip();
        
        return ESVONode.readFrom(buffer);
    }
    
    // Far pointer operations
    
    /**
     * Adds a far pointer to the page.
     * 
     * @param pointer 32-bit far pointer value
     * @return Index of the far pointer
     */
    public int addFarPointer(int pointer) {
        // Calculate the actual offset for this far pointer
        int newFarPtrOffset = attachmentOffset - (farPointers.size() + 1) * 4;
        
        if (newFarPtrOffset < nodeOffset) {
            return -1; // No space
        }
        
        int index = farPointers.size();
        farPointers.add(pointer);
        farPointerOffset = newFarPtrOffset;
        memory.set(ValueLayout.JAVA_INT, farPointerOffset + index * 4, pointer);
        setFarPointerCount(farPointers.size());
        return index;
    }
    
    /**
     * Gets a far pointer by index.
     * 
     * @param index Far pointer index
     * @return Far pointer value
     */
    public int getFarPointer(int index) {
        if (index < 0 || index >= farPointers.size()) {
            throw new IndexOutOfBoundsException("Far pointer index out of range");
        }
        return farPointers.get(index);
    }
    
    // Attachment operations
    
    /**
     * Adds attachment data to the page.
     * 
     * @param data Attachment data bytes
     * @return Offset of the attachment data
     */
    public int addAttachment(byte[] data) {
        int spaceNeeded = data.length;
        
        // Calculate minimum offset based on what's already allocated
        int minOffset = nodeOffset;
        if (farPointers.size() > 0) {
            // If we have far pointers, they take space too
            minOffset = Math.max(minOffset, farPointerOffset);
        }
        
        if (attachmentOffset - spaceNeeded < minOffset) {
            return -1; // No space
        }
        
        attachmentOffset -= spaceNeeded;
        MemorySegment.copy(data, 0, memory, ValueLayout.JAVA_BYTE, attachmentOffset, data.length);
        setAttachmentSize(PAGE_BYTES - attachmentOffset);
        return attachmentOffset;
    }
    
    /**
     * Reads attachment data from the page.
     * 
     * @param offset Attachment offset
     * @param length Number of bytes to read
     * @return Attachment data
     */
    public byte[] readAttachment(int offset, int length) {
        byte[] data = new byte[length];
        MemorySegment.copy(memory, ValueLayout.JAVA_BYTE, offset, data, 0, length);
        return data;
    }
    
    // Header operations
    
    public int getNodeCount() {
        return memory.get(ValueLayout.JAVA_INT, OFFSET_NODE_COUNT);
    }
    
    public void setNodeCount(int count) {
        memory.set(ValueLayout.JAVA_INT, OFFSET_NODE_COUNT, count);
    }
    
    public int getFarPointerCount() {
        return memory.get(ValueLayout.JAVA_INT, OFFSET_FAR_PTR_COUNT);
    }
    
    public void setFarPointerCount(int count) {
        memory.set(ValueLayout.JAVA_INT, OFFSET_FAR_PTR_COUNT, count);
    }
    
    public int getAttachmentSize() {
        return memory.get(ValueLayout.JAVA_INT, OFFSET_ATTACH_SIZE);
    }
    
    public void setAttachmentSize(int size) {
        memory.set(ValueLayout.JAVA_INT, OFFSET_ATTACH_SIZE, size);
    }
    
    public int getNextPageOffset() {
        return memory.get(ValueLayout.JAVA_INT, OFFSET_NEXT_PAGE);
    }
    
    public void setNextPageOffset(int offset) {
        memory.set(ValueLayout.JAVA_INT, OFFSET_NEXT_PAGE, offset);
    }
    
    // Utility methods
    
    /**
     * Gets the size of this page in bytes.
     * 
     * @return Always returns 8192
     */
    public int getSizeInBytes() {
        return PAGE_BYTES;
    }
    
    /**
     * Gets the base address of this page.
     * 
     * @return Memory address
     */
    public long getBaseAddress() {
        return memory.address();
    }
    
    /**
     * Gets the underlying memory segment.
     * 
     * @return Memory segment
     */
    public MemorySegment getMemorySegment() {
        return memory;
    }
    
    /**
     * Calculates remaining space in the page.
     * 
     * @return Bytes of free space
     */
    public int getRemainingSpace() {
        // Space between end of nodes and start of far pointers/attachments
        int endOfNodes = nodeOffset;
        int startOfData = Math.min(farPointerOffset, attachmentOffset);
        return Math.max(0, startOfData - endOfNodes);
    }
    
    /**
     * Checks if the page has space for a node.
     * 
     * @return true if space available
     */
    public boolean hasSpaceForNode() {
        return getRemainingSpace() >= 8;
    }
    
    /**
     * Checks if the page is empty.
     * 
     * @return true if no nodes allocated
     */
    public boolean isEmpty() {
        return getNodeCount() == 0;
    }
    
    /**
     * Serialize page to bytes for GPU upload or file I/O
     * 
     * @return byte array containing full page data
     */
    public byte[] serialize() {
        syncToMemory();
        byte[] data = new byte[PAGE_BYTES];
        for (int i = 0; i < PAGE_BYTES; i++) {
            data[i] = memory.get(ValueLayout.JAVA_BYTE, i);
        }
        return data;
    }
    
    @Override
    public String toString() {
        syncToMemory();
        return String.format("ESVOPage[nodes=%d, farPtrs=%d, attach=%dB, free=%dB]",
                getNodeCount(), getFarPointerCount(), 
                getAttachmentSize(), getRemainingSpace());
    }
}