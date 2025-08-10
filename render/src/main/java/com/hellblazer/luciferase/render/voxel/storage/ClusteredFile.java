package com.hellblazer.luciferase.render.voxel.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * ClusteredFile provides clustered storage for sparse voxel octree data.
 * Based on NVIDIA ESVO clustered storage design for efficient streaming and caching.
 * 
 * Features:
 * - Cluster-based organization (64KB clusters by default)
 * - Memory-mapped file access for performance
 * - Concurrent read/write operations
 * - Compression support (LZ4/ZSTD)
 * - Block-level checksums for integrity
 * - Streaming-friendly layout
 */
public class ClusteredFile implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(ClusteredFile.class);
    
    // File format constants
    private static final int MAGIC_NUMBER = 0x45535644; // "ESVD" - Efficient Sparse Voxel Data
    private static final int VERSION = 1;
    private static final int DEFAULT_CLUSTER_SIZE = 65536; // 64KB clusters
    private static final int HEADER_SIZE = 1024; // 1KB header
    private static final int CLUSTER_HEADER_SIZE = 64; // 64B per cluster header
    
    // Compression types
    public enum CompressionType {
        NONE(0),
        LZ4(1),
        ZSTD(2);
        
        public final int id;
        CompressionType(int id) { this.id = id; }
        
        public static CompressionType fromId(int id) {
            for (CompressionType type : values()) {
                if (type.id == id) return type;
            }
            throw new IllegalArgumentException("Unknown compression type: " + id);
        }
    }
    
    /**
     * File header structure (1KB)
     */
    public static class FileHeader {
        public int magic = MAGIC_NUMBER;
        public int version = VERSION;
        public int clusterSize = DEFAULT_CLUSTER_SIZE;
        public int clusterCount = 0;
        public CompressionType compression = CompressionType.NONE;
        public long timestamp = System.currentTimeMillis();
        public int headerChecksum = 0;
        
        public void write(ByteBuffer buffer) {
            buffer.clear();
            buffer.putInt(magic);
            buffer.putInt(version);
            buffer.putInt(clusterSize);
            buffer.putInt(clusterCount);
            buffer.putInt(compression.id);
            buffer.putLong(timestamp);
            buffer.putInt(headerChecksum);
            
            // Pad to header size
            while (buffer.position() < HEADER_SIZE) {
                buffer.put((byte) 0);
            }
            buffer.flip();
        }
        
        public void read(ByteBuffer buffer) {
            buffer.clear();
            magic = buffer.getInt();
            version = buffer.getInt();
            clusterSize = buffer.getInt();
            clusterCount = buffer.getInt();
            compression = CompressionType.fromId(buffer.getInt());
            timestamp = buffer.getLong();
            headerChecksum = buffer.getInt();
            
            if (magic != MAGIC_NUMBER) {
                throw new IllegalArgumentException("Invalid file format - magic number mismatch");
            }
            if (version > VERSION) {
                throw new IllegalArgumentException("Unsupported file version: " + version);
            }
        }
    }
    
    /**
     * Cluster header structure (64B)
     */
    public static class ClusterHeader {
        public int clusterId;
        public int compressedSize;
        public int uncompressedSize;
        public int checksum;
        public CompressionType compression;
        public long dataOffset;
        public int flags;
        
        public void write(ByteBuffer buffer) {
            int startPos = buffer.position();
            buffer.putInt(clusterId);
            buffer.putInt(compressedSize);
            buffer.putInt(uncompressedSize);
            buffer.putInt(checksum);
            buffer.putInt(compression.id);
            buffer.putLong(dataOffset);
            buffer.putInt(flags);
            
            // Pad to cluster header size
            while (buffer.position() < startPos + CLUSTER_HEADER_SIZE) {
                buffer.put((byte) 0);
            }
        }
        
        public void read(ByteBuffer buffer) {
            clusterId = buffer.getInt();
            compressedSize = buffer.getInt();
            uncompressedSize = buffer.getInt();
            checksum = buffer.getInt();
            compression = CompressionType.fromId(buffer.getInt());
            dataOffset = buffer.getLong();
            flags = buffer.getInt();
            
            // Skip padding
            buffer.position(buffer.position() + (CLUSTER_HEADER_SIZE - 32));
        }
    }
    
    /**
     * Cluster data container
     */
    public static class ClusterData {
        public final int clusterId;
        public final byte[] data;
        public final CompressionType compression;
        public final int checksum;
        
        public ClusterData(int clusterId, byte[] data, CompressionType compression) {
            this.clusterId = clusterId;
            this.data = data;
            this.compression = compression;
            this.checksum = calculateChecksum(data);
        }
        
        private static int calculateChecksum(byte[] data) {
            // Simple CRC32-like checksum
            int checksum = 0;
            for (byte b : data) {
                checksum = (checksum << 1) ^ (b & 0xFF);
            }
            return checksum;
        }
    }
    
    private final Path filePath;
    private final FileChannel fileChannel;
    private final MappedByteBuffer headerBuffer;
    private final FileHeader fileHeader;
    private final CompressionType compressionType;
    private final int clusterSize;
    
    // Cluster management
    private final Map<Integer, ClusterHeader> clusterHeaders = new ConcurrentHashMap<>();
    private final Map<Integer, MappedByteBuffer> mappedClusters = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    // Statistics
    private long totalReads = 0;
    private long totalWrites = 0;
    private long totalBytesRead = 0;
    private long totalBytesWritten = 0;
    
    /**
     * Create a new ClusteredFile for writing
     */
    public static ClusteredFile create(Path filePath, CompressionType compression) throws IOException {
        return new ClusteredFile(filePath, compression, true);
    }
    
    /**
     * Open an existing ClusteredFile for reading
     */
    public static ClusteredFile open(Path filePath) throws IOException {
        return new ClusteredFile(filePath, null, false);
    }
    
    private ClusteredFile(Path filePath, CompressionType compression, boolean create) throws IOException {
        this.filePath = filePath;
        
        if (create) {
            this.fileChannel = FileChannel.open(filePath, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.READ, 
                StandardOpenOption.WRITE);
            this.compressionType = compression != null ? compression : CompressionType.NONE;
            this.clusterSize = DEFAULT_CLUSTER_SIZE;
            
            // Initialize file header
            this.fileHeader = new FileHeader();
            this.fileHeader.compression = this.compressionType;
            this.fileHeader.clusterSize = this.clusterSize;
            
            // Create and write header
            this.headerBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, HEADER_SIZE);
            this.headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            writeFileHeader();
            
        } else {
            this.fileChannel = FileChannel.open(filePath, StandardOpenOption.READ);
            
            // Read existing header
            this.headerBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, HEADER_SIZE);
            this.headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            this.fileHeader = new FileHeader();
            readFileHeader();
            
            this.compressionType = this.fileHeader.compression;
            this.clusterSize = this.fileHeader.clusterSize;
            
            // Load existing cluster headers
            loadClusterHeaders();
        }
        
        log.info("ClusteredFile {} opened: {} clusters, {} compression", 
                filePath.getFileName(), fileHeader.clusterCount, compressionType);
    }
    
    private void writeFileHeader() throws IOException {
        fileHeader.write(headerBuffer);
        headerBuffer.force();
    }
    
    private void readFileHeader() throws IOException {
        fileHeader.read(headerBuffer);
    }
    
    private void loadClusterHeaders() throws IOException {
        long offset = HEADER_SIZE;
        for (int i = 0; i < fileHeader.clusterCount; i++) {
            MappedByteBuffer clusterHeaderBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY, offset, CLUSTER_HEADER_SIZE);
            clusterHeaderBuffer.order(ByteOrder.LITTLE_ENDIAN);
            
            ClusterHeader header = new ClusterHeader();
            header.read(clusterHeaderBuffer);
            clusterHeaders.put(header.clusterId, header);
            
            offset += CLUSTER_HEADER_SIZE;
        }
    }
    
    /**
     * Write cluster data to file
     */
    public void writeCluster(ClusterData clusterData) throws IOException {
        lock.writeLock().lock();
        try {
            // Calculate positions
            long clusterHeaderOffset = HEADER_SIZE + (long) fileHeader.clusterCount * CLUSTER_HEADER_SIZE;
            long dataOffset = clusterHeaderOffset + CLUSTER_HEADER_SIZE;
            
            // Extend file if necessary
            long requiredSize = dataOffset + clusterData.data.length;
            if (fileChannel.size() < requiredSize) {
                fileChannel.truncate(requiredSize);
            }
            
            // Write cluster header
            MappedByteBuffer clusterHeaderBuffer = fileChannel.map(
                FileChannel.MapMode.READ_WRITE, clusterHeaderOffset, CLUSTER_HEADER_SIZE);
            clusterHeaderBuffer.order(ByteOrder.LITTLE_ENDIAN);
            
            ClusterHeader header = new ClusterHeader();
            header.clusterId = clusterData.clusterId;
            header.compressedSize = clusterData.data.length;
            header.uncompressedSize = clusterData.data.length; // TODO: actual compression
            header.checksum = clusterData.checksum;
            header.compression = clusterData.compression;
            header.dataOffset = dataOffset;
            header.flags = 0;
            
            header.write(clusterHeaderBuffer);
            clusterHeaderBuffer.force();
            
            // Write cluster data
            MappedByteBuffer dataBuffer = fileChannel.map(
                FileChannel.MapMode.READ_WRITE, dataOffset, clusterData.data.length);
            dataBuffer.put(clusterData.data);
            dataBuffer.force();
            
            // Update tracking
            clusterHeaders.put(clusterData.clusterId, header);
            fileHeader.clusterCount++;
            writeFileHeader();
            
            totalWrites++;
            totalBytesWritten += clusterData.data.length;
            
            log.debug("Wrote cluster {} ({} bytes)", clusterData.clusterId, clusterData.data.length);
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Read cluster data from file
     */
    public ClusterData readCluster(int clusterId) throws IOException {
        lock.readLock().lock();
        try {
            ClusterHeader header = clusterHeaders.get(clusterId);
            if (header == null) {
                throw new IOException("Cluster not found: " + clusterId);
            }
            
            // Get or create mapped buffer for this cluster
            MappedByteBuffer dataBuffer = mappedClusters.computeIfAbsent(clusterId, id -> {
                try {
                    MappedByteBuffer buffer = fileChannel.map(
                        FileChannel.MapMode.READ_ONLY, 
                        header.dataOffset, 
                        header.compressedSize);
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    return buffer;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to map cluster " + id, e);
                }
            });
            
            // Read data
            byte[] data = new byte[header.compressedSize];
            dataBuffer.position(0);
            dataBuffer.get(data);
            
            // Verify checksum
            ClusterData clusterData = new ClusterData(clusterId, data, header.compression);
            if (clusterData.checksum != header.checksum) {
                throw new IOException("Checksum mismatch for cluster " + clusterId);
            }
            
            totalReads++;
            totalBytesRead += data.length;
            
            log.debug("Read cluster {} ({} bytes)", clusterId, data.length);
            
            return clusterData;
            
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Check if a cluster exists
     */
    public boolean hasCluster(int clusterId) {
        lock.readLock().lock();
        try {
            return clusterHeaders.containsKey(clusterId);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get all cluster IDs
     */
    public Set<Integer> getClusterIds() {
        lock.readLock().lock();
        try {
            return new HashSet<>(clusterHeaders.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get file statistics
     */
    public FileStatistics getStatistics() {
        lock.readLock().lock();
        try {
            return new FileStatistics(
                fileHeader.clusterCount,
                totalReads,
                totalWrites,
                totalBytesRead,
                totalBytesWritten,
                filePath.toFile().length(),
                compressionType
            );
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Force all data to disk
     */
    public void sync() throws IOException {
        lock.writeLock().lock();
        try {
            fileChannel.force(true);
            headerBuffer.force();
            for (MappedByteBuffer buffer : mappedClusters.values()) {
                buffer.force();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            sync();
            mappedClusters.clear();
            if (fileChannel.isOpen()) {
                fileChannel.close();
            }
            log.info("ClusteredFile {} closed", filePath.getFileName());
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * File statistics container
     */
    public static class FileStatistics {
        public final int clusterCount;
        public final long totalReads;
        public final long totalWrites;
        public final long totalBytesRead;
        public final long totalBytesWritten;
        public final long fileSizeBytes;
        public final CompressionType compression;
        
        public FileStatistics(int clusterCount, long totalReads, long totalWrites,
                            long totalBytesRead, long totalBytesWritten, 
                            long fileSizeBytes, CompressionType compression) {
            this.clusterCount = clusterCount;
            this.totalReads = totalReads;
            this.totalWrites = totalWrites;
            this.totalBytesRead = totalBytesRead;
            this.totalBytesWritten = totalBytesWritten;
            this.fileSizeBytes = fileSizeBytes;
            this.compression = compression;
        }
        
        public double getCompressionRatio() {
            return totalBytesWritten > 0 ? (double) fileSizeBytes / totalBytesWritten : 1.0;
        }
        
        @Override
        public String toString() {
            return String.format("ClusteredFile Stats: %d clusters, %d reads, %d writes, %.2f MB, %.1fx compression",
                    clusterCount, totalReads, totalWrites, fileSizeBytes / (1024.0 * 1024.0), getCompressionRatio());
        }
    }
}