package com.hellblazer.luciferase.render.io;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for VoxelStreamingIO streaming functionality.
 */
public class VoxelStreamingIOTest {
    private static final Logger log = LoggerFactory.getLogger(VoxelStreamingIOTest.class);
    
    private Path tempDir;
    private VoxelStreamingIO streamingIO;
    
    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("voxel-streaming-test");
        streamingIO = new VoxelStreamingIO(tempDir);
        streamingIO.open();
    }
    
    @AfterEach
    void tearDown() throws IOException {
        if (streamingIO != null) {
            streamingIO.close();
        }
        // Clean up temp files
        Files.walk(tempDir)
            .sorted((a, b) -> -a.compareTo(b))
            .forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file: {}", path);
                }
            });
    }
    
    @Test
    void testAsyncChunkReading() throws Exception {
        // Write test data
        byte[] testData = createTestData(1024);
        streamingIO.writeChunk(ByteBuffer.wrap(testData), 0);
        
        // Read asynchronously
        CompletableFuture<ByteBuffer> future = streamingIO.readChunkAsync(
            VoxelFileFormat.HEADER_SIZE + 24, // Skip header and chunk header
            1024
        );
        
        // Verify async read completes
        ByteBuffer result = assertDoesNotThrow(() -> future.get(2, TimeUnit.SECONDS));
        assertNotNull(result);
        assertEquals(1024, result.remaining());
    }
    
    @Test
    void testBatchLoading() throws Exception {
        // Write multiple chunks
        int numChunks = 5;
        for (int i = 0; i < numChunks; i++) {
            byte[] data = createTestData(512);
            streamingIO.writeChunk(ByteBuffer.wrap(data), i);
        }
        
        // Prepare batch load request
        List<Long> offsets = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < numChunks; i++) {
            offsets.add((long)(VoxelFileFormat.HEADER_SIZE + (i * (24 + 65536)) + 24));
            sizes.add(512);
        }
        
        // Batch load
        List<ByteBuffer> results = streamingIO.batchLoad(offsets, sizes);
        
        assertEquals(numChunks, results.size());
        for (ByteBuffer buffer : results) {
            assertTrue(buffer.remaining() > 0);
        }
    }
    
    @Test
    @Disabled("LOD streaming implementation incomplete")
    void testLODStreaming() throws Exception {
        // Create LOD table structure
        VoxelFileFormat.Header header = new VoxelFileFormat.Header();
        header.magic = VoxelFileFormat.MAGIC;
        header.version = VoxelFileFormat.VERSION;
        header.chunkCount = 4; // LOD table + 3 LOD levels
        
        List<VoxelFileFormat.Chunk> chunks = new ArrayList<>();
        
        // Add LOD table chunk
        VoxelFileFormat.Chunk lodTableChunk = new VoxelFileFormat.Chunk();
        lodTableChunk.type = VoxelFileFormat.ChunkType.LOD_TABLE;
        lodTableChunk.size = 3 * 24; // 3 LOD levels (24 bytes each)
        lodTableChunk.data = createLODTable(3);
        chunks.add(lodTableChunk);
        
        // Add LOD data chunks
        for (int i = 0; i < 3; i++) {
            VoxelFileFormat.Chunk lodChunk = new VoxelFileFormat.Chunk();
            lodChunk.type = VoxelFileFormat.ChunkType.VOXEL_DATA;
            lodChunk.size = 256 * (i + 1); // Increasing sizes for each LOD
            lodChunk.data = ByteBuffer.wrap(createTestData(lodChunk.size));
            chunks.add(lodChunk);
        }
        
        // Write the structured file
        streamingIO.writeStream(header, chunks);
        
        // Test LOD streaming
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger loadedLevels = new AtomicInteger(0);
        List<Integer> loadOrder = new ArrayList<>();
        
        streamingIO.streamLOD(2, new VoxelStreamingIO.LODCallback() {
            @Override
            public void onLODLoaded(int level, ByteBuffer data) {
                log.info("LOD level {} loaded with {} bytes", level, data.remaining());
                loadOrder.add(level);
                loadedLevels.incrementAndGet();
                latch.countDown();
            }
        });
        
        // Wait for all LODs to load
        assertTrue(latch.await(5, TimeUnit.SECONDS), "LOD loading timed out");
        assertEquals(3, loadedLevels.get());
        
        // Verify progressive loading (0 -> 1 -> 2)
        assertEquals(List.of(0, 1, 2), loadOrder);
    }
    
    @Test
    void testStreamingToggle() {
        assertTrue(streamingIO.isStreamingEnabled());
        
        streamingIO.setStreamingEnabled(false);
        assertFalse(streamingIO.isStreamingEnabled());
        
        streamingIO.setStreamingEnabled(true);
        assertTrue(streamingIO.isStreamingEnabled());
    }
    
    @Test
    void testConcurrentAsyncReads() throws Exception {
        // Write test data
        int numChunks = 10;
        for (int i = 0; i < numChunks; i++) {
            byte[] data = createTestData(256);
            streamingIO.writeChunk(ByteBuffer.wrap(data), i);
        }
        
        // Launch concurrent reads
        List<CompletableFuture<ByteBuffer>> futures = new ArrayList<>();
        for (int i = 0; i < numChunks; i++) {
            long offset = VoxelFileFormat.HEADER_SIZE + (i * (24 + 65536)) + 24;
            futures.add(streamingIO.readChunkAsync(offset, 256));
        }
        
        // Wait for all to complete
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );
        
        assertDoesNotThrow(() -> allFutures.get(5, TimeUnit.SECONDS));
        
        // Verify all reads succeeded
        for (CompletableFuture<ByteBuffer> future : futures) {
            assertTrue(future.isDone());
            assertNotNull(future.get());
        }
    }
    
    private byte[] createTestData(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte)(i % 256);
        }
        return data;
    }
    
    private ByteBuffer createLODTable(int numLevels) {
        int lodEntrySize = 24; // 4 + 4 + 8 + 4 + 4 bytes per LODEntry
        ByteBuffer table = ByteBuffer.allocateDirect(numLevels * lodEntrySize);
        
        long dataOffset = VoxelFileFormat.HEADER_SIZE + 24 + (numLevels * lodEntrySize);
        for (int i = 0; i < numLevels; i++) {
            VoxelFileFormat.LODEntry entry = new VoxelFileFormat.LODEntry();
            entry.level = i;
            entry.dataOffset = dataOffset;
            entry.dataSize = 256 * (i + 1);
            entry.write(table);
            
            dataOffset += 24 + entry.dataSize; // Skip chunk header + data
        }
        
        table.flip();
        return table;
    }
}