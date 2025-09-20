package com.hellblazer.luciferase.esvo.io;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serializer for ESVO octree data with resource management
 */
public class ESVOSerializer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ESVOSerializer.class);
    
    private final int version;
    private final UnifiedResourceManager resourceManager;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong totalBytesWritten = new AtomicLong(0);
    private final List<ByteBuffer> allocatedBuffers = new ArrayList<>();
    
    public ESVOSerializer() {
        this(ESVOFileFormat.VERSION_2);
    }
    
    public ESVOSerializer(int version) {
        this.version = version;
        this.resourceManager = UnifiedResourceManager.getInstance();
        log.debug("ESVOSerializer created with version {}", version);
    }
    
    /**
     * Serialize octree data to file
     */
    public void serialize(ESVOOctreeData octree, Path outputFile) throws IOException {
        ensureNotClosed();
        
        try (FileChannel channel = FileChannel.open(outputFile, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            
            // Count actual nodes
            int nodeCount = countNodes(octree);
            
            // Write header
            ESVOFileFormat.Header header = new ESVOFileFormat.Header();
            header.version = version;
            header.nodeCount = nodeCount;
            
            ByteBuffer headerBuffer = allocateBuffer(header.getHeaderSize(), "header");
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            writeHeader(headerBuffer, header);
            headerBuffer.flip();
            long bytesWritten = channel.write(headerBuffer);
            totalBytesWritten.addAndGet(bytesWritten);
            
            // Write nodes
            writeNodes(channel, octree, nodeCount);
            
            log.info("Serialized {} nodes to {}, {} bytes written", 
                    nodeCount, outputFile, totalBytesWritten.get());
        }
    }
    
    /**
     * Serialize with metadata
     */
    public void serializeWithMetadata(ESVOOctreeData octree, ESVOMetadata metadata, Path outputFile) 
            throws IOException {
        
        try (FileChannel channel = FileChannel.open(outputFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            
            int nodeCount = countNodes(octree);
            
            // Prepare header
            ESVOFileFormat.Header header = new ESVOFileFormat.Header();
            header.version = ESVOFileFormat.VERSION_2;
            header.nodeCount = nodeCount;
            
            // Write header (will update metadata offset later)
            ByteBuffer headerBuffer = allocateBuffer(header.getHeaderSize(), "header");
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            writeHeader(headerBuffer, header);
            headerBuffer.flip();
            long bytesWritten = channel.write(headerBuffer);
            totalBytesWritten.addAndGet(bytesWritten);
            
            // Write nodes
            writeNodes(channel, octree, nodeCount);
            
            // Write metadata
            long metadataOffset = channel.position();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(metadata);
            }
            byte[] metadataBytes = baos.toByteArray();
            
            ByteBuffer metadataBuffer = ByteBuffer.wrap(metadataBytes);
            channel.write(metadataBuffer);
            
            // Update header with metadata info
            header.metadataOffset = metadataOffset;
            header.metadataSize = metadataBytes.length;
            
            headerBuffer.clear();
            writeHeader(headerBuffer, header);
            headerBuffer.flip();
            channel.position(0);
            channel.write(headerBuffer);
        }
    }
    
    private void writeHeader(ByteBuffer buffer, ESVOFileFormat.Header header) {
        buffer.putInt(header.magic);
        buffer.putInt(header.version);
        buffer.putInt(header.nodeCount);
        buffer.putInt(header.reserved);
        
        if (header.version >= ESVOFileFormat.VERSION_2) {
            buffer.putLong(header.metadataOffset);
            buffer.putLong(header.metadataSize);
        }
    }
    
    private int countNodes(ESVOOctreeData octree) {
        // Get the actual stored node indices and return the max index + 1
        int[] indices = octree.getNodeIndices();
        if (indices.length == 0) {
            return 0;
        }
        // Return max index + 1 to ensure we have space for all nodes
        return indices[indices.length - 1] + 1;
    }
    
    private void writeNodes(FileChannel channel, ESVOOctreeData octree, int nodeCount) 
            throws IOException {
        
        // Skip if no nodes to write
        if (nodeCount == 0) {
            return;
        }
        
        // Allocate buffer for nodes (12 bytes per node: 1+1+2+4+4)
        ByteBuffer nodeBuffer = allocateBuffer(nodeCount * 12, "nodes");
        nodeBuffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // Write each node
        for (int i = 0; i < nodeCount; i++) {
            ESVONodeUnified node = octree.getNode(i);
            if (node != null) {
                // Write childDescriptor (4 bytes)
                nodeBuffer.putInt(node.getChildDescriptor());
                // Write contourDescriptor (4 bytes)  
                nodeBuffer.putInt(node.getContourDescriptor());
                // Write padding (4 bytes) for alignment
                nodeBuffer.putInt(0);
            } else {
                nodeBuffer.putInt(0); // Empty first 4 bytes
                nodeBuffer.putInt(0); // Empty second 4 bytes
                nodeBuffer.putInt(0); // Empty third 4 bytes
            }
        }
        
        nodeBuffer.flip();
        long nodesBytesWritten = channel.write(nodeBuffer);
        totalBytesWritten.addAndGet(nodesBytesWritten);
    }
    
    /**
     * Allocate a managed buffer
     */
    private ByteBuffer allocateBuffer(int size, String name) {
        ByteBuffer buffer = resourceManager.allocateMemory(size);
        allocatedBuffers.add(buffer);
        log.trace("Allocated {} buffer of size {} bytes", name, size);
        return buffer;
    }
    
    /**
     * Get total bytes written
     */
    public long getTotalBytesWritten() {
        return totalBytesWritten.get();
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.debug("Closing ESVOSerializer, releasing {} buffers", allocatedBuffers.size());
            
            // Release all allocated buffers
            for (ByteBuffer buffer : allocatedBuffers) {
                try {
                    resourceManager.releaseMemory(buffer);
                } catch (Exception e) {
                    log.error("Error releasing buffer", e);
                }
            }
            allocatedBuffers.clear();
            
            log.info("ESVOSerializer closed. Total bytes written: {}", totalBytesWritten.get());
        }
    }
    
    private void ensureNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("ESVOSerializer has been closed");
        }
    }
}