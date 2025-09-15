package com.hellblazer.luciferase.esvo.io;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Streaming writer for ESVO octree data
 */
public class ESVOStreamWriter {
    
    private final FileChannel channel;
    private final Path outputFile;
    private int nodeCount;
    
    public ESVOStreamWriter(Path outputFile) throws IOException {
        this.outputFile = outputFile;
        this.channel = FileChannel.open(outputFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        this.nodeCount = 0;
        
        // Write placeholder header
        writeHeader();
    }
    
    /**
     * Write a batch of nodes
     */
    public void writeNodeBatch(List<ESVOOctreeNode> nodes) throws IOException {
        ByteBuffer nodeBuffer = ByteBuffer.allocate(nodes.size() * 8);
        nodeBuffer.order(ByteOrder.LITTLE_ENDIAN);
        
        for (ESVOOctreeNode node : nodes) {
            nodeBuffer.put(node.childMask);
            nodeBuffer.put((byte)0); // padding
            nodeBuffer.putShort((short)0); // padding
            nodeBuffer.putInt(node.contour);
        }
        
        nodeBuffer.flip();
        channel.write(nodeBuffer);
        nodeCount += nodes.size();
    }
    
    /**
     * Close the writer and update header
     */
    public void close() throws IOException {
        // Update header with final node count
        long currentPos = channel.position();
        channel.position(0);
        writeHeader();
        channel.position(currentPos);
        channel.close();
    }
    
    private void writeHeader() throws IOException {
        ESVOFileFormat.Header header = new ESVOFileFormat.Header();
        header.version = ESVOFileFormat.VERSION_2;
        header.nodeCount = nodeCount;
        
        ByteBuffer headerBuffer = ByteBuffer.allocate(header.getHeaderSize());
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
        
        headerBuffer.putInt(header.magic);
        headerBuffer.putInt(header.version);
        headerBuffer.putInt(header.nodeCount);
        headerBuffer.putInt(header.reserved);
        
        if (header.version >= ESVOFileFormat.VERSION_2) {
            headerBuffer.putLong(header.metadataOffset);
            headerBuffer.putLong(header.metadataSize);
        }
        
        headerBuffer.flip();
        channel.write(headerBuffer);
    }
}