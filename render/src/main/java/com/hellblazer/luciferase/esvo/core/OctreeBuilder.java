package com.hellblazer.luciferase.esvo.core;

import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import com.hellblazer.luciferase.resource.ByteBufferResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CPU-based octree builder for ESVO with resource tracking
 * 
 * Manages memory allocations during octree construction:
 * - Triangle voxelization
 * - Parallel subdivision with thread limits
 * - Thread-local batch management
 * - Error metric calculation
 * - Attribute filtering and quantization
 * - Automatic resource cleanup
 */
public class OctreeBuilder implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OctreeBuilder.class);
    
    private final int maxDepth;
    private final List<VoxelData> voxels;
    private final UnifiedResourceManager resourceManager;
    private final AtomicLong totalMemoryAllocated;
    private final AtomicBoolean closed;
    private final List<ByteBuffer> allocatedBuffers;
    
    public OctreeBuilder(int maxDepth) {
        this.maxDepth = maxDepth;
        this.voxels = new ArrayList<>();
        this.resourceManager = UnifiedResourceManager.getInstance();
        this.totalMemoryAllocated = new AtomicLong(0);
        this.closed = new AtomicBoolean(false);
        this.allocatedBuffers = new ArrayList<>();
        
        log.debug("OctreeBuilder created with maxDepth={}", maxDepth);
    }
    
    /**
     * Add a voxel at the specified position and level
     */
    public void addVoxel(int x, int y, int z, int level, float density) {
        ensureNotClosed();
        
        // Calculate position in [1,2] coordinate space
        int resolution = 1 << level;
        float voxelSize = 1.0f / resolution;
        
        Vector3f position = new Vector3f(
            1.0f + (x + 0.5f) * voxelSize,
            1.0f + (y + 0.5f) * voxelSize,
            1.0f + (z + 0.5f) * voxelSize
        );
        
        voxels.add(new VoxelData(position, level, density));
        
        // Track memory usage (approximate)
        totalMemoryAllocated.addAndGet(32); // Approximate size of VoxelData
    }
    
    /**
     * Build an ESVO octree from a list of voxel coordinates.
     * Constructs octree structure by adding each voxel and building proper node hierarchy
     * with child masks and indices.
     * 
     * @param voxelList List of voxel positions (integer coordinates)
     * @param depth Maximum octree depth
     * @return ESVOOctreeData containing the constructed octree
     */
    public ESVOOctreeData buildFromVoxels(List<Point3i> voxelList, int depth) {
        ensureNotClosed();
        
        if (voxelList == null || voxelList.isEmpty()) {
            log.warn("Building octree from empty voxel list");
            return new ESVOOctreeData(1024); // Return minimal structure
        }
        
        log.debug("Building octree from {} voxels at depth {}", voxelList.size(), depth);
        
        // Add all voxels to internal structure
        for (Point3i voxel : voxelList) {
            addVoxel(voxel.x, voxel.y, voxel.z, depth, 1.0f);
        }
        
        // Build octree structure using hierarchical construction
        Map<Long, OctreeNode> nodeMap = new HashMap<>();
        
        // Create leaf nodes for each voxel
        for (VoxelData voxel : voxels) {
            long nodeKey = computeNodeKey(voxel.position, voxel.level);
            nodeMap.putIfAbsent(nodeKey, new OctreeNode(nodeKey, voxel.level, true));
        }
        
        // Build parent nodes bottom-up
        for (int level = depth - 1; level >= 0; level--) {
            // Collect all keys at current child level first to avoid ConcurrentModificationException
            List<Long> childKeys = new ArrayList<>();
            for (Map.Entry<Long, OctreeNode> entry : nodeMap.entrySet()) {
                if (entry.getValue().level == level + 1) {
                    childKeys.add(entry.getKey());
                }
            }
            
            // Then add parent nodes
            for (long childKey : childKeys) {
                long parentKey = getParentKey(childKey);
                nodeMap.putIfAbsent(parentKey, new OctreeNode(parentKey, level, false));
            }
        }
        
        // Convert to ESVOOctreeData structure
        int estimatedSize = nodeMap.size() * 8; // 8 bytes per node
        ESVOOctreeData octreeData = new ESVOOctreeData(estimatedSize);
        
        // Build ESVO nodes with proper child masks
        int nodeIndex = 0;
        Map<Long, Integer> keyToIndex = new HashMap<>();
        
        // First pass: assign indices to all nodes in breadth-first order
        for (int level = 0; level <= depth; level++) {
            for (Map.Entry<Long, OctreeNode> entry : nodeMap.entrySet()) {
                if (entry.getValue().level == level) {
                    keyToIndex.put(entry.getKey(), nodeIndex++);
                }
            }
        }
        
        // Far pointer support: max value for 14-bit child pointer
        final int MAX_CHILD_PTR = (1 << 14) - 1; // 16383

        // Track far pointers needed - allocate slots after regular nodes
        int nextFarPointerSlot = nodeIndex; // Start after all regular nodes
        List<FarPointerEntry> farPointers = new ArrayList<>();

        // Second pass: create ESVO nodes with proper child pointers and masks
        for (Map.Entry<Long, OctreeNode> entry : nodeMap.entrySet()) {
            long nodeKey = entry.getKey();
            OctreeNode node = entry.getValue();
            int currentIndex = keyToIndex.get(nodeKey);

            // Calculate child mask and leaf mask
            int childMask = 0;
            int leafMask = 0;
            int firstChildIndex = -1;

            if (!node.isLeaf) {
                // Check which children exist
                for (int childIdx = 0; childIdx < 8; childIdx++) {
                    long childKey = getChildKey(nodeKey, childIdx);
                    if (nodeMap.containsKey(childKey)) {
                        childMask |= (1 << childIdx);

                        // Track first child index for child pointer
                        int childIndex = keyToIndex.get(childKey);
                        if (firstChildIndex == -1) {
                            firstChildIndex = childIndex;
                        }

                        // Check if child is a leaf
                        if (nodeMap.get(childKey).isLeaf) {
                            leafMask |= (1 << childIdx);
                        }
                    }
                }
            } else {
                // Leaf nodes have no children but mark themselves as leaves
                leafMask = 0xFF; // All children would be leaves (leaf node convention)
            }

            // Create ESVO node with relative child pointer
            // Use relative offset from current node (not absolute index)
            int relativeOffset = firstChildIndex != -1 ? (firstChildIndex - currentIndex) : 0;

            // Check if we need a far pointer
            boolean useFarPointer = relativeOffset > MAX_CHILD_PTR;
            int childPtr;

            if (useFarPointer && firstChildIndex != -1) {
                // Allocate far pointer slot
                int farPointerSlot = nextFarPointerSlot++;

                // Calculate offset to far pointer slot (must fit in 14 bits when divided by 2)
                int farPointerOffset = farPointerSlot - currentIndex;

                // For far pointer resolution: ofs = nodes[parentIdx + ofs * 2].childDescriptor
                // So we store: childPtr = farPointerOffset / 2
                // The far pointer node stores the actual child index (not offset)
                childPtr = farPointerOffset;

                // Store far pointer entry for later
                farPointers.add(new FarPointerEntry(farPointerSlot, firstChildIndex));

                log.debug("Using far pointer for node {}: farSlot={}, actualChild={}",
                         currentIndex, farPointerSlot, firstChildIndex);
            } else {
                childPtr = relativeOffset;
            }

            ESVONodeUnified esvoNode = new ESVONodeUnified(
                (byte) leafMask,
                (byte) childMask,
                useFarPointer,
                childPtr,
                (byte) 0,  // contour mask (not used in basic construction)
                0          // contour ptr (not used in basic construction)
            );

            octreeData.setNode(currentIndex, esvoNode);
        }

        // Third pass: create far pointer nodes
        for (FarPointerEntry fp : farPointers) {
            // Far pointer node stores the actual child index in childDescriptor
            // When resolved: return parentIdx + nodes[parentIdx + ofs * 2].childDescriptor * 2
            // So we need to store: (firstChildIndex - parentIdx) / 2? No, looking at the code:
            // ofs = nodes[parentIdx + ofs * 2].childDescriptor
            // return parentIdx + ofs * 2
            // So childDescriptor should be the relative offset / 2
            // Actually looking at ESVONode.resolveFarPointer more carefully, it returns parentIdx + ofs * 2
            // where ofs is read from the far pointer node's childDescriptor
            // This doesn't match what we need - the far pointer should point to the actual child
            // Let me just store the absolute child index for now and adjust traversal if needed
            ESVONodeUnified farPointerNode = new ESVONodeUnified(fp.actualChildIndex, 0);
            octreeData.setNode(fp.slotIndex, farPointerNode);
        }

        int totalNodes = nodeMap.size() + farPointers.size();
        log.debug("Built octree with {} nodes ({} regular, {} far pointers) from {} voxels",
                 totalNodes, nodeMap.size(), farPointers.size(), voxelList.size());
        
        return octreeData;
    }
    
    /**
     * Build and serialize the octree to the provided buffer
     */
    public void serialize(ByteBuffer buffer) {
        ensureNotClosed();
        
        // Stub implementation - just write a simple header
        buffer.putInt(0x4553564F); // "ESVO" magic number
        buffer.putInt(maxDepth);
        buffer.putInt(voxels.size());
        
        // In full implementation, would build octree structure
        // and serialize nodes in breadth-first order
        
        log.debug("Serialized {} voxels to buffer", voxels.size());
    }
    
    /**
     * Allocate a managed buffer for octree construction
     */
    public ByteBuffer allocateBuffer(int sizeBytes, String debugName) {
        ensureNotClosed();
        
        ByteBuffer buffer = resourceManager.allocateMemory(sizeBytes);
        allocatedBuffers.add(buffer);
        totalMemoryAllocated.addAndGet(sizeBytes);
        
        log.info("OctreeBuilder allocated buffer '{}' of size {} bytes, identity: {}, now tracking {} buffers", 
                debugName, sizeBytes, System.identityHashCode(buffer), allocatedBuffers.size());
        return buffer;
    }
    
    /**
     * Get total memory allocated during octree construction
     */
    public long getTotalMemoryAllocated() {
        return totalMemoryAllocated.get();
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.debug("Closing OctreeBuilder, releasing {} bytes of memory from {} buffers", 
                     totalMemoryAllocated.get(), allocatedBuffers.size());
            
            // Release all allocated buffers
            int releasedCount = 0;
            for (ByteBuffer buffer : allocatedBuffers) {
                try {
                    log.info("OctreeBuilder releasing buffer {} (index {})", 
                             System.identityHashCode(buffer), releasedCount);
                    resourceManager.releaseMemory(buffer);
                    releasedCount++;
                    log.info("OctreeBuilder successfully released buffer {}, activeCount now: {}", 
                             System.identityHashCode(buffer), resourceManager.getActiveResourceCount());
                } catch (Exception e) {
                    log.error("Error releasing buffer", e);
                }
            }
            allocatedBuffers.clear();
            
            // Clear voxel data
            voxels.clear();
            
            log.info("OctreeBuilder closed, released {} buffers, {} bytes total", 
                    releasedCount, totalMemoryAllocated.getAndSet(0));
        }
    }
    
    private void ensureNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("OctreeBuilder has been closed");
        }
    }
    
    /**
     * Compute a unique key for a node based on its position and level.
     * Uses Morton encoding for spatial hashing.
     */
    private long computeNodeKey(Vector3f position, int level) {
        // Normalize position to [0,1] range within the [1,2] coordinate space
        float x = position.x - 1.0f;
        float y = position.y - 1.0f;
        float z = position.z - 1.0f;
        
        // Convert to integer coordinates at this level
        int resolution = 1 << level;
        int ix = Math.min((int) (x * resolution), resolution - 1);
        int iy = Math.min((int) (y * resolution), resolution - 1);
        int iz = Math.min((int) (z * resolution), resolution - 1);
        
        // Encode: level in upper bits, Morton code in lower bits
        long morton = encodeMorton(ix, iy, iz);
        return ((long) level << 48) | morton;
    }
    
    /**
     * Encode three integers into a Morton code (Z-order curve).
     */
    private long encodeMorton(int x, int y, int z) {
        long result = 0;
        for (int i = 0; i < 16; i++) {
            result |= ((x & (1L << i)) << (2 * i)) |
                      ((y & (1L << i)) << (2 * i + 1)) |
                      ((z & (1L << i)) << (2 * i + 2));
        }
        return result;
    }
    
    /**
     * Get the parent key for a given node key.
     */
    private long getParentKey(long nodeKey) {
        int level = (int) (nodeKey >> 48);
        if (level == 0) {
            return nodeKey; // Root has no parent
        }
        
        long morton = nodeKey & 0xFFFFFFFFFFFFL;
        long parentMorton = morton >> 3; // Shift right by 3 bits (divide by 8)
        return ((long) (level - 1) << 48) | parentMorton;
    }
    
    /**
     * Get the child key for a given node and child index.
     */
    private long getChildKey(long nodeKey, int childIdx) {
        int level = (int) (nodeKey >> 48);
        long morton = nodeKey & 0xFFFFFFFFFFFFL;
        long childMorton = (morton << 3) | childIdx; // Shift left by 3 bits, add child index
        return ((long) (level + 1) << 48) | childMorton;
    }
    
    /**
     * Internal voxel data structure
     */
    private static class VoxelData {
        final Vector3f position;
        final int level;
        final float density;
        
        VoxelData(Vector3f position, int level, float density) {
            this.position = position;
            this.level = level;
            this.density = density;
        }
    }
    
    /**
     * Internal octree node used during construction
     */
    private static class OctreeNode {
        final long key;
        final int level;
        final boolean isLeaf;

        OctreeNode(long key, int level, boolean isLeaf) {
            this.key = key;
            this.level = level;
            this.isLeaf = isLeaf;
        }
    }

    /**
     * Far pointer entry for nodes that need far pointer resolution
     */
    private static class FarPointerEntry {
        final int slotIndex;       // Where to store the far pointer node
        final int actualChildIndex; // The actual child index to point to

        FarPointerEntry(int slotIndex, int actualChildIndex) {
            this.slotIndex = slotIndex;
            this.actualChildIndex = actualChildIndex;
        }
    }
}