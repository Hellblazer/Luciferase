package com.hellblazer.luciferase.render.io;

import com.hellblazer.luciferase.render.compression.DXTCompressor;
import com.hellblazer.luciferase.render.compression.SparseVoxelCompressor;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Streaming I/O system for voxel data with progressive loading.
 * 
 * Features:
 * - Asynchronous chunk loading
 * - Memory-mapped file access
 * - LRU cache for frequently accessed chunks
 * - Progressive LOD streaming
 * - Parallel decompression
 */
public class VoxelStreamingIO {
    
    private static final int CACHE_SIZE = 256; // MB
    private static final int CHUNK_SIZE = 65536; // 64KB chunks
    private static final int PREFETCH_DISTANCE = 3;
    
    private final Path filePath;
    private FileChannel channel;
    private VoxelFileFormat.Header header;
    private Map<VoxelFileFormat.ChunkType, List<VoxelFileFormat.Chunk>> chunkIndex;
    private final LRUCache<Long, ByteBuffer> cache;
    private final ExecutorService loadExecutor;
    private final CompletionService<LoadedChunk> loadService;
    private final Queue<Future<LoadedChunk>> pendingLoads;
    
    // Streaming control
    private volatile boolean streamingEnabled = true;
    
    // Compression handlers
    private final DXTCompressor dxtCompressor;
    private final SparseVoxelCompressor svoCompressor;
    
    public VoxelStreamingIO(Path filePath) {
        this.filePath = filePath;
        this.cache = new LRUCache<>(CACHE_SIZE * 1024 * 1024 / CHUNK_SIZE);
        this.loadExecutor = Executors.newFixedThreadPool(4);
        this.loadService = new ExecutorCompletionService<>(loadExecutor);
        this.pendingLoads = new ConcurrentLinkedQueue<>();
        this.chunkIndex = new HashMap<>();
        this.dxtCompressor = new DXTCompressor();
        this.svoCompressor = new SparseVoxelCompressor();
    }
    
    /**
     * Open file for streaming.
     */
    public void open() throws IOException {
        channel = FileChannel.open(filePath, 
            StandardOpenOption.READ, 
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE);
        
        if (channel.size() > 0) {
            readHeader();
            buildChunkIndex();
        }
    }
    
    /**
     * Close file and release resources.
     */
    public void close() throws IOException {
        if (channel != null) {
            channel.close();
        }
        loadExecutor.shutdown();
        try {
            if (!loadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                loadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            loadExecutor.shutdownNow();
        }
    }
    
    /**
     * Write voxel data with streaming.
     */
    public void writeStream(VoxelFileFormat.Header header, 
                           List<VoxelFileFormat.Chunk> chunks) throws IOException {
        this.header = header;
        
        // Write header
        ByteBuffer headerBuffer = ByteBuffer.allocate(VoxelFileFormat.HEADER_SIZE);
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
        header.write(headerBuffer);
        headerBuffer.flip();
        channel.write(headerBuffer, 0);
        
        // Write chunks
        long offset = VoxelFileFormat.HEADER_SIZE;
        for (VoxelFileFormat.Chunk chunk : chunks) {
            chunk.offset = offset;
            ByteBuffer compressed = compressChunk(chunk);
            chunk.compressedSize = compressed.remaining();
            
            // Write chunk header
            ByteBuffer chunkHeader = ByteBuffer.allocate(24);
            chunkHeader.order(ByteOrder.LITTLE_ENDIAN);
            chunk.writeHeader(chunkHeader);
            chunkHeader.flip();
            channel.write(chunkHeader, offset);
            
            // Write chunk data
            channel.write(compressed, offset + 24);
            offset += 24 + chunk.compressedSize;
        }
    }
    
    /**
     * Read chunk asynchronously.
     */
    public CompletableFuture<ByteBuffer> readChunkAsync(long offset, int size) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readChunk(offset, size);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, loadExecutor);
    }
    
    /**
     * Read chunk synchronously with caching.
     */
    public ByteBuffer readChunk(long offset, int size) throws IOException {
        // Check cache
        ByteBuffer cached = cache.get(offset);
        if (cached != null) {
            return cached.duplicate();
        }
        
        // Read from file
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        channel.read(buffer, offset);
        buffer.flip();
        
        // Decompress if needed
        VoxelFileFormat.Chunk chunk = findChunkAt(offset);
        if (chunk != null && chunk.compressionType != 0) {
            buffer = decompressChunk(buffer, chunk);
        }
        
        // Cache result
        cache.put(offset, buffer);
        
        // Prefetch next chunks
        prefetchChunks(offset);
        
        return buffer;
    }
    
    /**
     * Stream LOD level progressively.
     */
    public void streamLOD(int targetLevel, LODCallback callback) throws IOException {
        List<VoxelFileFormat.Chunk> lodChunks = chunkIndex.get(VoxelFileFormat.ChunkType.LOD_TABLE);
        if (lodChunks == null || lodChunks.isEmpty()) {
            return;
        }
        
        // Load LOD table
        VoxelFileFormat.Chunk lodTableChunk = lodChunks.get(0);
        ByteBuffer lodTable = readChunk(lodTableChunk.offset, lodTableChunk.size);
        
        // Stream levels progressively
        for (int level = 0; level <= targetLevel; level++) {
            VoxelFileFormat.LODEntry entry = VoxelFileFormat.LODEntry.read(lodTable);
            
            // Load LOD data asynchronously
            final int currentLevel = level;
            readChunkAsync(entry.dataOffset, entry.dataSize)
                .thenAccept(data -> callback.onLODLoaded(currentLevel, data));
        }
    }
    
    /**
     * Memory-mapped access for large files.
     */
    public MappedByteBuffer mapRegion(long offset, int size) throws IOException {
        return channel.map(FileChannel.MapMode.READ_ONLY, offset, size);
    }
    
    /**
     * Batch load multiple chunks.
     */
    public List<ByteBuffer> batchLoad(List<Long> offsets, List<Integer> sizes) 
            throws IOException, InterruptedException, ExecutionException {
        
        List<Future<ByteBuffer>> futures = new ArrayList<>();
        
        for (int i = 0; i < offsets.size(); i++) {
            final long offset = offsets.get(i);
            final int size = sizes.get(i);
            
            futures.add(loadExecutor.submit(() -> readChunk(offset, size)));
        }
        
        List<ByteBuffer> results = new ArrayList<>();
        for (Future<ByteBuffer> future : futures) {
            results.add(future.get());
        }
        
        return results;
    }
    
    private void readHeader() throws IOException {
        ByteBuffer headerBuffer = ByteBuffer.allocate(VoxelFileFormat.HEADER_SIZE);
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
        channel.read(headerBuffer, 0);
        headerBuffer.flip();
        header = VoxelFileFormat.Header.read(headerBuffer);
        
        if (!header.isValid()) {
            throw new IOException("Invalid voxel file format");
        }
    }
    
    private void buildChunkIndex() throws IOException {
        long offset = VoxelFileFormat.HEADER_SIZE;
        
        for (int i = 0; i < header.chunkCount; i++) {
            ByteBuffer chunkHeader = ByteBuffer.allocate(24);
            chunkHeader.order(ByteOrder.LITTLE_ENDIAN);
            channel.read(chunkHeader, offset);
            chunkHeader.flip();
            
            VoxelFileFormat.Chunk chunk = VoxelFileFormat.Chunk.readHeader(chunkHeader);
            chunkIndex.computeIfAbsent(chunk.type, k -> new ArrayList<>()).add(chunk);
            
            offset += 24 + chunk.compressedSize;
        }
    }
    
    private VoxelFileFormat.Chunk findChunkAt(long offset) {
        for (List<VoxelFileFormat.Chunk> chunks : chunkIndex.values()) {
            for (VoxelFileFormat.Chunk chunk : chunks) {
                if (chunk.offset == offset) {
                    return chunk;
                }
            }
        }
        return null;
    }
    
    private ByteBuffer compressChunk(VoxelFileFormat.Chunk chunk) throws IOException {
        VoxelFileFormat.CompressionType type = 
            VoxelFileFormat.CompressionType.fromCode(chunk.compressionType);
        
        switch (type) {
            case NONE:
                return chunk.data;
                
            case ZLIB:
                return compressZlib(chunk.data);
                
            case DXT1:
            case DXT5:
                // Assume texture data with width/height in first 8 bytes
                int width = chunk.data.getInt(0);
                int height = chunk.data.getInt(4);
                ByteBuffer textureData = chunk.data.slice();
                textureData.position(8);
                return dxtCompressor.compress(textureData, width, height,
                    type == VoxelFileFormat.CompressionType.DXT1 ? 
                    DXTCompressor.CompressionFormat.DXT1 : 
                    DXTCompressor.CompressionFormat.DXT5);
                
            case CUSTOM_SVO:
                // TODO: Implement SVO compression
                return chunk.data;
                
            default:
                return chunk.data;
        }
    }
    
    private ByteBuffer decompressChunk(ByteBuffer compressed, VoxelFileFormat.Chunk chunk) 
            throws IOException {
        
        VoxelFileFormat.CompressionType type = 
            VoxelFileFormat.CompressionType.fromCode(chunk.compressionType);
        
        switch (type) {
            case NONE:
                return compressed;
                
            case ZLIB:
                return decompressZlib(compressed, chunk.size);
                
            case DXT1:
            case DXT5:
                // Extract dimensions from chunk metadata
                int width = chunk.size >> 16;
                int height = chunk.size & 0xFFFF;
                return dxtCompressor.decompress(compressed, width, height,
                    type == VoxelFileFormat.CompressionType.DXT1 ? 
                    DXTCompressor.CompressionFormat.DXT1 : 
                    DXTCompressor.CompressionFormat.DXT5);
                
            case CUSTOM_SVO:
                // TODO: Implement SVO decompression
                return compressed;
                
            default:
                return compressed;
        }
    }
    
    private ByteBuffer compressZlib(ByteBuffer input) throws IOException {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();
        
        ByteBuffer output = ByteBuffer.allocateDirect(input.remaining() * 2);
        byte[] buffer = new byte[1024];
        
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            output.put(buffer, 0, count);
        }
        
        deflater.end();
        output.flip();
        return output;
    }
    
    private ByteBuffer decompressZlib(ByteBuffer input, int uncompressedSize) 
            throws IOException {
        
        Inflater inflater = new Inflater();
        inflater.setInput(input);
        
        ByteBuffer output = ByteBuffer.allocateDirect(uncompressedSize);
        byte[] buffer = new byte[1024];
        
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                output.put(buffer, 0, count);
            }
        } catch (Exception e) {
            throw new IOException("Decompression failed", e);
        } finally {
            inflater.end();
        }
        
        output.flip();
        return output;
    }
    
    private void prefetchChunks(long currentOffset) {
        // Prefetch next chunks in background
        for (int i = 1; i <= PREFETCH_DISTANCE; i++) {
            long nextOffset = currentOffset + (i * CHUNK_SIZE);
            
            if (!cache.containsKey(nextOffset)) {
                loadService.submit(() -> {
                    try {
                        ByteBuffer data = readChunk(nextOffset, CHUNK_SIZE);
                        return new LoadedChunk(nextOffset, data);
                    } catch (IOException e) {
                        return null;
                    }
                });
            }
        }
    }
    
    /**
     * Simple LRU cache implementation.
     */
    private static class LRUCache<K, V> extends LinkedHashMap<K, V> {
        private final int maxEntries;
        
        public LRUCache(int maxEntries) {
            super(16, 0.75f, true);
            this.maxEntries = maxEntries;
        }
        
        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxEntries;
        }
    }
    
    private static class LoadedChunk {
        public final long offset;
        public final ByteBuffer data;
        
        public LoadedChunk(long offset, ByteBuffer data) {
            this.offset = offset;
            this.data = data;
        }
    }
    
    public interface LODCallback {
        void onLODLoaded(int level, ByteBuffer data);
    }
    
    /**
     * Check if streaming is currently enabled.
     */
    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }
    
    /**
     * Enable or disable streaming functionality.
     */
    public void setStreamingEnabled(boolean enabled) {
        this.streamingEnabled = enabled;
    }
    
    /**
     * Get file statistics.
     */
    public FileStats getStats() {
        FileStats stats = new FileStats();
        stats.totalSize = header.totalSize;
        stats.chunkCount = header.chunkCount;
        stats.cacheHits = cache.size();
        stats.compressionRatio = calculateCompressionRatio();
        return stats;
    }
    
    public static class FileStats {
        public long totalSize;
        public int chunkCount;
        public int cacheHits;
        public float compressionRatio;
    }
    
    private float calculateCompressionRatio() {
        long compressed = 0;
        long uncompressed = 0;
        
        for (List<VoxelFileFormat.Chunk> chunks : chunkIndex.values()) {
            for (VoxelFileFormat.Chunk chunk : chunks) {
                compressed += chunk.compressedSize;
                uncompressed += chunk.size;
            }
        }
        
        return uncompressed > 0 ? (float)uncompressed / compressed : 1.0f;
    }
}