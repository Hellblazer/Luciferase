package com.hellblazer.luciferase.render.io;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Memory-mapped file access for voxel data using Java 24 FFM API.
 * 
 * Features:
 * - Zero-copy access to file data
 * - Virtual memory management
 * - Page-aligned access for optimal performance
 * - Thread-safe concurrent access
 * - Automatic region management
 */
public class MemoryMappedVoxelFile implements AutoCloseable {
    
    private static final long PAGE_SIZE = 4096; // OS page size
    private static final long REGION_SIZE = 256 * 1024 * 1024; // 256MB regions
    private static final int MAX_REGIONS = 16; // Max mapped regions
    
    private final Path filePath;
    private FileChannel channel;
    private Arena arena;
    private final List<MappedRegion> regions;
    private final ConcurrentHashMap<Long, MemorySegment> segmentCache;
    private final ReadWriteLock lock;
    private long fileSize;
    
    public static class MappedRegion {
        public final long offset;
        public final long size;
        public final MemorySegment segment;
        public final MappedByteBuffer buffer;
        private int refCount;
        
        public MappedRegion(long offset, long size, MemorySegment segment, MappedByteBuffer buffer) {
            this.offset = offset;
            this.size = size;
            this.segment = segment;
            this.buffer = buffer;
            this.refCount = 0;
        }
        
        public void acquire() {
            refCount++;
        }
        
        public void release() {
            refCount--;
        }
        
        public boolean isInUse() {
            return refCount > 0;
        }
        
        public boolean contains(long position) {
            return position >= offset && position < offset + size;
        }
    }
    
    public MemoryMappedVoxelFile(Path filePath) {
        this.filePath = filePath;
        this.regions = new ArrayList<>();
        this.segmentCache = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
    }
    
    /**
     * Open file for memory-mapped access.
     */
    public void open(boolean readOnly) throws IOException {
        StandardOpenOption[] options = readOnly ? 
            new StandardOpenOption[]{StandardOpenOption.READ} :
            new StandardOpenOption[]{StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE};
        
        channel = FileChannel.open(filePath, options);
        fileSize = channel.size();
        
        // Create confined arena for explicit memory management
        arena = Arena.ofConfined();
    }
    
    /**
     * Map a region of the file into memory.
     */
    public MappedRegion mapRegion(long offset, long size) throws IOException {
        lock.writeLock().lock();
        try {
            // Align to page boundaries
            long alignedOffset = alignToPage(offset);
            long alignedSize = alignToPage(size + (offset - alignedOffset));
            
            // Check if region already mapped
            for (MappedRegion region : regions) {
                if (region.contains(offset) && region.contains(offset + size - 1)) {
                    region.acquire();
                    return region;
                }
            }
            
            // Evict old regions if necessary
            while (regions.size() >= MAX_REGIONS) {
                evictLRURegion();
            }
            
            // Map new region using FFM
            FileChannel.MapMode mode = channel.isOpen() ? 
                FileChannel.MapMode.READ_WRITE : FileChannel.MapMode.READ_ONLY;
            
            MappedByteBuffer buffer = channel.map(mode, alignedOffset, alignedSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            // Create memory segment for FFM access
            MemorySegment segment = MemorySegment.ofBuffer(buffer);
            
            MappedRegion region = new MappedRegion(alignedOffset, alignedSize, segment, buffer);
            region.acquire();
            regions.add(region);
            
            return region;
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Read data from mapped file.
     */
    public ByteBuffer read(long offset, int length) throws IOException {
        lock.readLock().lock();
        try {
            MappedRegion region = findOrMapRegion(offset, length);
            
            long regionOffset = offset - region.offset;
            ByteBuffer slice = region.buffer.duplicate();
            slice.position((int)regionOffset);
            slice.limit((int)(regionOffset + length));
            
            return slice.slice();
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Write data to mapped file.
     */
    public void write(long offset, ByteBuffer data) throws IOException {
        // First ensure file is large enough
        long requiredSize = offset + data.remaining();
        if (requiredSize > fileSize) {
            lock.writeLock().lock();
            try {
                // Extend file size if needed
                if (requiredSize > fileSize) {
                    channel.position(requiredSize - 1);
                    channel.write(ByteBuffer.wrap(new byte[]{0}));
                    fileSize = channel.size();
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        lock.readLock().lock();
        try {
            MappedRegion region = findOrMapRegion(offset, data.remaining());
            
            long regionOffset = offset - region.offset;
            ByteBuffer slice = region.buffer.duplicate();
            slice.position((int)regionOffset);
            slice.put(data);
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Direct memory access using FFM API.
     */
    public MemorySegment getMemorySegment(long offset, long size) throws IOException {
        lock.readLock().lock();
        try {
            // Check cache first
            MemorySegment cached = segmentCache.get(offset);
            if (cached != null && cached.byteSize() >= size) {
                return cached;
            }
            
            MappedRegion region = findOrMapRegion(offset, size);
            
            // Create a slice of the memory segment
            long regionOffset = offset - region.offset;
            MemorySegment slice = region.segment.asSlice(regionOffset, size);
            
            // Cache for future access
            segmentCache.put(offset, slice);
            
            return slice;
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Bulk read operation for multiple regions.
     */
    public List<ByteBuffer> bulkRead(List<Long> offsets, List<Integer> sizes) 
            throws IOException {
        
        if (offsets.size() != sizes.size()) {
            throw new IllegalArgumentException("Offset and size lists must have same length");
        }
        
        List<ByteBuffer> results = new ArrayList<>();
        
        lock.readLock().lock();
        try {
            for (int i = 0; i < offsets.size(); i++) {
                results.add(read(offsets.get(i), sizes.get(i)));
            }
            
            return results;
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Force changes to be written to disk.
     */
    public void force() throws IOException {
        lock.readLock().lock();
        try {
            for (MappedRegion region : regions) {
                if (region.buffer.isDirect()) {
                    region.buffer.force();
                }
            }
            
            channel.force(true);
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Prefetch pages into memory for faster access.
     */
    public void prefetch(long offset, long size) throws IOException {
        lock.readLock().lock();
        try {
            MappedRegion region = findOrMapRegion(offset, size);
            
            // Touch pages to bring them into memory
            long regionOffset = offset - region.offset;
            for (long pos = regionOffset; pos < regionOffset + size; pos += PAGE_SIZE) {
                region.buffer.get((int)pos);
            }
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Zero-copy transfer to native memory.
     */
    public void copyToNative(long fileOffset, MemorySegment destination, long size) 
            throws IOException {
        
        lock.readLock().lock();
        try {
            MemorySegment source = getMemorySegment(fileOffset, size);
            destination.copyFrom(source);
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Unmap a region from memory.
     */
    public void unmapRegion(MappedRegion region) {
        lock.writeLock().lock();
        try {
            region.release();
            
            if (!region.isInUse()) {
                regions.remove(region);
                
                // Remove cached segments for this region
                segmentCache.entrySet().removeIf(entry -> {
                    long offset = entry.getKey();
                    return region.contains(offset);
                });
            }
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Unmap all regions.
     */
    public void unmapAll() {
        lock.writeLock().lock();
        try {
            regions.clear();
            segmentCache.clear();
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            unmapAll();
            
            if (arena != null) {
                arena.close();
            }
            
            if (channel != null) {
                channel.close();
            }
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private MappedRegion findOrMapRegion(long offset, long size) throws IOException {
        // Find existing region
        for (MappedRegion region : regions) {
            if (region.contains(offset) && region.contains(offset + size - 1)) {
                return region;
            }
        }
        
        // Map new region
        return mapRegion(offset, size);
    }
    
    private void evictLRURegion() {
        // Find region with lowest reference count
        MappedRegion lru = null;
        for (MappedRegion region : regions) {
            if (!region.isInUse()) {
                if (lru == null || region.refCount < lru.refCount) {
                    lru = region;
                }
            }
        }
        
        if (lru != null) {
            regions.remove(lru);
            
            // Remove cached segments
            final MappedRegion finalLru = lru;
            segmentCache.entrySet().removeIf(entry -> {
                long offset = entry.getKey();
                return finalLru.contains(offset);
            });
        }
    }
    
    private long alignToPage(long value) {
        return (value + PAGE_SIZE - 1) & ~(PAGE_SIZE - 1);
    }
    
    /**
     * Get file statistics.
     */
    public FileStats getStats() {
        lock.readLock().lock();
        try {
            FileStats stats = new FileStats();
            stats.fileSize = fileSize;
            stats.mappedRegions = regions.size();
            stats.totalMappedSize = regions.stream()
                .mapToLong(r -> r.size)
                .sum();
            stats.cachedSegments = segmentCache.size();
            
            return stats;
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public static class FileStats {
        public long fileSize;
        public int mappedRegions;
        public long totalMappedSize;
        public int cachedSegments;
        
        @Override
        public String toString() {
            return String.format("FileStats{size=%d, regions=%d, mapped=%d, cached=%d}",
                fileSize, mappedRegions, totalMappedSize, cachedSegments);
        }
    }
    
    /**
     * Resize the file.
     */
    public void resize(long newSize) throws IOException {
        lock.writeLock().lock();
        try {
            unmapAll();
            
            try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
                raf.setLength(newSize);
            }
            
            fileSize = newSize;
            
        } finally {
            lock.writeLock().unlock();
        }
    }
}