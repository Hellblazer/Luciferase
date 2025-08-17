package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import com.hellblazer.luciferase.render.memory.GPUMemoryManager;
import com.hellblazer.luciferase.render.voxel.esvo.*;
import com.hellblazer.luciferase.render.voxel.esvo.voxelization.Octree;
import com.hellblazer.luciferase.render.lwjgl.StorageBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL45.*;

/**
 * GPU integration for ESVO pipeline.
 * Handles uploading octree data to GPU and managing GPU resources.
 */
public class ESVOGPUIntegration {
    
    private static final Logger log = Logger.getLogger(ESVOGPUIntegration.class.getName());
    
    // ESVO node size in bytes (matching the 8-byte format)
    private static final int ESVO_NODE_SIZE = 8;
    
    // Maximum nodes per SSBO (2MB / 8 bytes = 256K nodes)
    private static final int MAX_NODES_PER_BUFFER = 256 * 1024;
    
    private final GPUMemoryManager memoryManager;
    private StorageBuffer nodeBuffer;
    private StorageBuffer pageBuffer;
    private StorageBuffer metadataBuffer;
    
    // Statistics
    private int totalNodesUploaded = 0;
    private int totalPagesUploaded = 0;
    private long uploadTimeMs = 0;
    
    public ESVOGPUIntegration(GPUMemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }
    
    /**
     * Upload an ESVO octree to GPU memory.
     * 
     * @param octree The octree to upload
     * @return true if upload successful
     */
    public boolean uploadOctree(Octree octree) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Upload nodes
            List<ESVONode> nodes = octree.getESVONodes();
            if (!uploadNodes(nodes)) {
                return false;
            }
            
            // Upload pages if available
            List<ESVOPage> pages = octree.getPages();
            if (!pages.isEmpty() && !uploadPages(pages)) {
                return false;
            }
            
            // Upload metadata
            if (!uploadMetadata(octree)) {
                return false;
            }
            
            uploadTimeMs = System.currentTimeMillis() - startTime;
            
            log.info(String.format("ESVO GPU upload complete: %d nodes, %d pages in %dms",
                totalNodesUploaded, totalPagesUploaded, uploadTimeMs));
            
            return true;
            
        } catch (Exception e) {
            log.severe("Failed to upload ESVO octree to GPU: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Upload ESVO nodes to GPU storage buffer.
     */
    private boolean uploadNodes(List<ESVONode> nodes) {
        if (nodes.isEmpty()) {
            return true;
        }
        
        int nodeCount = nodes.size();
        int bufferSize = nodeCount * ESVO_NODE_SIZE;
        
        // Check size limit
        if (nodeCount > MAX_NODES_PER_BUFFER) {
            log.warning("Node count exceeds maximum buffer size, splitting required");
            // For now, truncate to max size
            nodeCount = MAX_NODES_PER_BUFFER;
            bufferSize = nodeCount * ESVO_NODE_SIZE;
        }
        
        // Allocate buffer
        ByteBuffer buffer = MemoryUtil.memAlloc(bufferSize);
        
        try {
            // Pack nodes into buffer
            for (int i = 0; i < nodeCount; i++) {
                ESVONode node = nodes.get(i);
                packNode(node, buffer);
            }
            
            buffer.flip();
            
            // Create or update storage buffer
            if (nodeBuffer == null) {
                nodeBuffer = new StorageBuffer(bufferSize);
            }
            
            // Upload to GPU
            nodeBuffer.write(buffer);
            
            totalNodesUploaded = nodeCount;
            
            log.fine("Uploaded " + nodeCount + " ESVO nodes to GPU");
            
            return true;
            
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }
    
    /**
     * Pack an ESVO node into a byte buffer.
     * Format matches the 8-byte ESVO specification.
     */
    private void packNode(ESVONode node, ByteBuffer buffer) {
        // Bytes 0-1: Valid mask and non-leaf mask
        buffer.put(node.getValidMask());
        buffer.put(node.getNonLeafMask());
        
        // Bytes 2-3: Child pointer (16 bits)
        int childPointer = node.getChildPointer();
        buffer.put((byte)(childPointer & 0xFF));
        buffer.put((byte)((childPointer >> 8) & 0xFF));
        
        // Bytes 4-5: Contour mask and flags
        buffer.put(node.getContourMask());
        buffer.put((byte)0); // Reserved flags
        
        // Bytes 6-7: Contour pointer or additional data
        int contourPointer = node.getContourPointer();
        buffer.put((byte)(contourPointer & 0xFF));
        buffer.put((byte)((contourPointer >> 8) & 0xFF));
    }
    
    /**
     * Upload ESVO pages to GPU.
     */
    private boolean uploadPages(List<ESVOPage> pages) {
        if (pages.isEmpty()) {
            return true;
        }
        
        int totalSize = pages.size() * ESVOPage.PAGE_BYTES;
        ByteBuffer buffer = MemoryUtil.memAlloc(totalSize);
        
        try {
            // Pack all pages
            for (ESVOPage page : pages) {
                byte[] pageData = page.serialize();
                buffer.put(pageData);
            }
            
            buffer.flip();
            
            // Create or update page buffer
            if (pageBuffer == null) {
                pageBuffer = new StorageBuffer(totalSize);
            }
            
            pageBuffer.write(buffer);
            
            totalPagesUploaded = pages.size();
            
            log.fine("Uploaded " + pages.size() + " ESVO pages to GPU");
            
            return true;
            
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }
    
    /**
     * Upload octree metadata for GPU traversal.
     */
    private boolean uploadMetadata(Octree octree) {
        // Metadata structure (16 floats/ints):
        // [0-2]: Bounding box min
        // [3-5]: Bounding box max
        // [6]: Node count
        // [7]: Max depth
        // [8]: LOD count
        // [9]: Leaf count
        // [10]: Has contours flag
        // [11]: Is complete flag
        // [12-15]: Reserved
        
        ByteBuffer buffer = MemoryUtil.memAlloc(16 * 4); // 16 floats
        
        try {
            // Bounding box (placeholder values)
            buffer.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f); // Min
            buffer.putFloat(1.0f).putFloat(1.0f).putFloat(1.0f); // Max
            
            // Octree statistics
            buffer.putInt(octree.getNodeCount());
            buffer.putInt(octree.getMaxDepth());
            buffer.putInt(octree.getLODCount());
            buffer.putInt(octree.getLeafCount());
            
            // Flags
            buffer.putInt(octree.hasContours() ? 1 : 0);
            buffer.putInt(octree.isComplete() ? 1 : 0);
            
            // Reserved
            buffer.putInt(0).putInt(0).putInt(0).putInt(0);
            
            buffer.flip();
            
            // Create or update metadata buffer
            if (metadataBuffer == null) {
                metadataBuffer = new StorageBuffer(buffer.capacity());
            }
            
            metadataBuffer.write(buffer);
            
            return true;
            
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }
    
    /**
     * Bind buffers for GPU rendering.
     */
    public void bindForRendering() {
        if (nodeBuffer != null) {
            nodeBuffer.bind(0); // Binding point 0 for nodes
        }
        if (pageBuffer != null) {
            pageBuffer.bind(1); // Binding point 1 for pages
        }
        if (metadataBuffer != null) {
            metadataBuffer.bind(2); // Binding point 2 for metadata
        }
    }
    
    /**
     * Release GPU resources.
     */
    public void release() {
        if (nodeBuffer != null) {
            nodeBuffer.cleanup();
            nodeBuffer = null;
        }
        if (pageBuffer != null) {
            pageBuffer.cleanup();
            pageBuffer = null;
        }
        if (metadataBuffer != null) {
            metadataBuffer.cleanup();
            metadataBuffer = null;
        }
        
        totalNodesUploaded = 0;
        totalPagesUploaded = 0;
    }
    
    // Getters for statistics
    
    public int getTotalNodesUploaded() {
        return totalNodesUploaded;
    }
    
    public int getTotalPagesUploaded() {
        return totalPagesUploaded;
    }
    
    public long getUploadTimeMs() {
        return uploadTimeMs;
    }
    
    public boolean isUploaded() {
        return nodeBuffer != null && totalNodesUploaded > 0;
    }
}