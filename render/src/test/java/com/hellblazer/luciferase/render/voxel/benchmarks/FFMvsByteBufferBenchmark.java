package com.hellblazer.luciferase.render.voxel.benchmarks;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.lang.foreign.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive JMH benchmark comparing FFM (Foreign Function & Memory) API vs ByteBuffer
 * for various memory access patterns relevant to voxel octree operations.
 * 
 * This benchmark evaluates:
 * - Sequential read/write operations
 * - Random access patterns  
 * - Bulk operations
 * - Struct-like data access (simulating VoxelOctreeNode)
 * - Memory allocation overhead
 * - Thread-local vs shared access patterns
 * - On-heap vs off-heap ByteBuffer comparisons
 * 
 * @author Claude Code
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class FFMvsByteBufferBenchmark {
    
    // ================================================================================
    // Constants and Data Structures
    // ================================================================================
    
    private static final int BUFFER_SIZE = 64 * 1024; // 64KB buffers
    private static final int NODE_SIZE = 64; // Size of a VoxelOctreeNode in bytes
    private static final int NODES_PER_BUFFER = BUFFER_SIZE / NODE_SIZE;
    private static final int BULK_OPERATION_SIZE = 1024;
    private static final int RANDOM_ACCESS_COUNT = 100;
    
    // VoxelOctreeNode-like structure field offsets
    private static final int VALID_MASK_OFFSET = 0;     // byte
    private static final int FLAGS_OFFSET = 1;          // byte  
    private static final int CHILD_PTR_OFFSET = 2;      // byte
    private static final int FAR_FLAG_OFFSET = 3;       // byte
    private static final int CONTOUR_MASK_OFFSET = 4;   // byte
    private static final int CONTOUR_PTR_OFFSET = 8;    // int (4 bytes)
    private static final int BOUNDING_BOX_OFFSET = 12;  // 6 floats (24 bytes)
    private static final int COLOR_DATA_OFFSET = 36;    // 4 floats (16 bytes)
    private static final int METADATA_OFFSET = 52;      // int (4 bytes)
    private static final int PADDING_OFFSET = 56;       // 8 bytes padding
    
    // Memory layout for FFM
    private static final MemoryLayout NODE_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_BYTE.withName("validMask"),
        ValueLayout.JAVA_BYTE.withName("flags"),
        ValueLayout.JAVA_BYTE.withName("childPtr"),
        ValueLayout.JAVA_BYTE.withName("farFlag"),
        ValueLayout.JAVA_BYTE.withName("contourMask"),
        MemoryLayout.paddingLayout(3), // Align to 4-byte boundary
        ValueLayout.JAVA_INT.withName("contourPtr"),
        MemoryLayout.sequenceLayout(6, ValueLayout.JAVA_FLOAT).withName("boundingBox"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("colorData"),
        ValueLayout.JAVA_INT.withName("metadata"),
        MemoryLayout.paddingLayout(8) // Padding to 64 bytes
    );
    
    // ================================================================================
    // Benchmark State Variables
    // ================================================================================
    
    // FFM components
    private Arena arena;
    private MemorySegment ffmBuffer;
    private MemorySegment threadLocalFFMBuffer;
    
    // ByteBuffer components
    private ByteBuffer onHeapBuffer;
    private ByteBuffer offHeapBuffer;
    private ByteBuffer threadLocalOnHeapBuffer;
    private ByteBuffer threadLocalOffHeapBuffer;
    
    // Random access patterns
    private int[] randomIndices;
    private float[] testFloats;
    private int[] testInts;
    private byte[] testBytes;
    private byte[] bulkData;
    
    @Setup(Level.Trial)
    public void setupTrial() {
        // Initialize FFM components
        arena = Arena.ofShared();
        ffmBuffer = arena.allocate(BUFFER_SIZE, 64); // 64-byte aligned
        
        // Initialize ByteBuffers
        onHeapBuffer = ByteBuffer.allocate(BUFFER_SIZE).order(ByteOrder.nativeOrder());
        offHeapBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.nativeOrder());
        
        // Generate test data
        var random = ThreadLocalRandom.current();
        randomIndices = new int[RANDOM_ACCESS_COUNT];
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            randomIndices[i] = random.nextInt(NODES_PER_BUFFER);
        }
        
        testFloats = new float[RANDOM_ACCESS_COUNT];
        testInts = new int[RANDOM_ACCESS_COUNT];
        testBytes = new byte[RANDOM_ACCESS_COUNT];
        bulkData = new byte[BULK_OPERATION_SIZE];
        
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            testFloats[i] = random.nextFloat() * 1000.0f;
            testInts[i] = random.nextInt();
            testBytes[i] = (byte) random.nextInt(256);
        }
        
        random.nextBytes(bulkData);
    }
    
    @Setup(Level.Iteration)
    public void setupIteration() {
        // Create thread-local buffers for concurrent benchmarks
        threadLocalFFMBuffer = arena.allocate(BUFFER_SIZE, 64);
        threadLocalOnHeapBuffer = ByteBuffer.allocate(BUFFER_SIZE).order(ByteOrder.nativeOrder());
        threadLocalOffHeapBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.nativeOrder());
    }
    
    @TearDown(Level.Trial)
    public void teardownTrial() {
        if (arena != null) {
            arena.close();
        }
    }
    
    // ================================================================================
    // Memory Allocation Benchmarks
    // ================================================================================
    
    @Benchmark
    public MemorySegment ffmAllocation() {
        try (var localArena = Arena.ofConfined()) {
            return localArena.allocate(NODE_SIZE, 8);
        }
    }
    
    @Benchmark
    public ByteBuffer onHeapAllocation() {
        return ByteBuffer.allocate(NODE_SIZE).order(ByteOrder.nativeOrder());
    }
    
    @Benchmark
    public ByteBuffer offHeapAllocation() {
        return ByteBuffer.allocateDirect(NODE_SIZE).order(ByteOrder.nativeOrder());
    }
    
    @Benchmark
    public MemorySegment ffmBulkAllocation() {
        try (var localArena = Arena.ofConfined()) {
            return localArena.allocate(BUFFER_SIZE, 64);
        }
    }
    
    @Benchmark
    public ByteBuffer onHeapBulkAllocation() {
        return ByteBuffer.allocate(BUFFER_SIZE).order(ByteOrder.nativeOrder());
    }
    
    @Benchmark  
    public ByteBuffer offHeapBulkAllocation() {
        return ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.nativeOrder());
    }
    
    // ================================================================================
    // Sequential Access Benchmarks
    // ================================================================================
    
    @Benchmark
    public void ffmSequentialByteWrite(Blackhole bh) {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            ffmBuffer.set(ValueLayout.JAVA_BYTE, i, (byte) i);
        }
        bh.consume(ffmBuffer);
    }
    
    @Benchmark
    public void onHeapSequentialByteWrite(Blackhole bh) {
        onHeapBuffer.clear();
        for (int i = 0; i < BUFFER_SIZE; i++) {
            onHeapBuffer.put(i, (byte) i);
        }
        bh.consume(onHeapBuffer);
    }
    
    @Benchmark
    public void offHeapSequentialByteWrite(Blackhole bh) {
        offHeapBuffer.clear();
        for (int i = 0; i < BUFFER_SIZE; i++) {
            offHeapBuffer.put(i, (byte) i);
        }
        bh.consume(offHeapBuffer);
    }
    
    @Benchmark
    public long ffmSequentialByteRead() {
        long sum = 0;
        for (int i = 0; i < BUFFER_SIZE; i++) {
            sum += ffmBuffer.get(ValueLayout.JAVA_BYTE, i);
        }
        return sum;
    }
    
    @Benchmark
    public long onHeapSequentialByteRead() {
        long sum = 0;
        for (int i = 0; i < BUFFER_SIZE; i++) {
            sum += onHeapBuffer.get(i);
        }
        return sum;
    }
    
    @Benchmark
    public long offHeapSequentialByteRead() {
        long sum = 0;
        for (int i = 0; i < BUFFER_SIZE; i++) {
            sum += offHeapBuffer.get(i);
        }
        return sum;
    }
    
    @Benchmark
    public void ffmSequentialIntWrite(Blackhole bh) {
        for (int i = 0; i < BUFFER_SIZE / 4; i++) {
            ffmBuffer.set(ValueLayout.JAVA_INT, i * 4L, i);
        }
        bh.consume(ffmBuffer);
    }
    
    @Benchmark
    public void onHeapSequentialIntWrite(Blackhole bh) {
        onHeapBuffer.clear();
        for (int i = 0; i < BUFFER_SIZE / 4; i++) {
            onHeapBuffer.putInt(i * 4, i);
        }
        bh.consume(onHeapBuffer);
    }
    
    @Benchmark
    public void offHeapSequentialIntWrite(Blackhole bh) {
        offHeapBuffer.clear();
        for (int i = 0; i < BUFFER_SIZE / 4; i++) {
            offHeapBuffer.putInt(i * 4, i);
        }
        bh.consume(offHeapBuffer);
    }
    
    @Benchmark
    public long ffmSequentialIntRead() {
        long sum = 0;
        for (int i = 0; i < BUFFER_SIZE / 4; i++) {
            sum += ffmBuffer.get(ValueLayout.JAVA_INT, i * 4L);
        }
        return sum;
    }
    
    @Benchmark
    public long onHeapSequentialIntRead() {
        long sum = 0;
        for (int i = 0; i < BUFFER_SIZE / 4; i++) {
            sum += onHeapBuffer.getInt(i * 4);
        }
        return sum;
    }
    
    @Benchmark
    public long offHeapSequentialIntRead() {
        long sum = 0;
        for (int i = 0; i < BUFFER_SIZE / 4; i++) {
            sum += offHeapBuffer.getInt(i * 4);
        }
        return sum;
    }
    
    @Benchmark
    public void ffmSequentialFloatWrite(Blackhole bh) {
        for (int i = 0; i < BUFFER_SIZE / 4; i++) {
            ffmBuffer.set(ValueLayout.JAVA_FLOAT, i * 4L, i * 1.5f);
        }
        bh.consume(ffmBuffer);
    }
    
    @Benchmark
    public void onHeapSequentialFloatWrite(Blackhole bh) {
        onHeapBuffer.clear();
        for (int i = 0; i < BUFFER_SIZE / 4; i++) {
            onHeapBuffer.putFloat(i * 4, i * 1.5f);
        }
        bh.consume(onHeapBuffer);
    }
    
    @Benchmark
    public void offHeapSequentialFloatWrite(Blackhole bh) {
        offHeapBuffer.clear();
        for (int i = 0; i < BUFFER_SIZE / 4; i++) {
            offHeapBuffer.putFloat(i * 4, i * 1.5f);
        }
        bh.consume(offHeapBuffer);
    }
    
    @Benchmark
    public double ffmSequentialFloatRead() {
        double sum = 0.0;
        for (int i = 0; i < BUFFER_SIZE / 4; i++) {
            sum += ffmBuffer.get(ValueLayout.JAVA_FLOAT, i * 4L);
        }
        return sum;
    }
    
    @Benchmark
    public double onHeapSequentialFloatRead() {
        double sum = 0.0;
        for (int i = 0; i < BUFFER_SIZE / 4; i++) {
            sum += onHeapBuffer.getFloat(i * 4);
        }
        return sum;
    }
    
    @Benchmark
    public double offHeapSequentialFloatRead() {
        double sum = 0.0;
        for (int i = 0; i < BUFFER_SIZE / 4; i++) {
            sum += offHeapBuffer.getFloat(i * 4);
        }
        return sum;
    }
    
    // ================================================================================
    // Random Access Benchmarks
    // ================================================================================
    
    @Benchmark
    public void ffmRandomByteWrite(Blackhole bh) {
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + VALID_MASK_OFFSET;
            ffmBuffer.set(ValueLayout.JAVA_BYTE, offset, testBytes[i]);
        }
        bh.consume(ffmBuffer);
    }
    
    @Benchmark
    public void onHeapRandomByteWrite(Blackhole bh) {
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + VALID_MASK_OFFSET;
            onHeapBuffer.put(offset, testBytes[i]);
        }
        bh.consume(onHeapBuffer);
    }
    
    @Benchmark
    public void offHeapRandomByteWrite(Blackhole bh) {
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + VALID_MASK_OFFSET;
            offHeapBuffer.put(offset, testBytes[i]);
        }
        bh.consume(offHeapBuffer);
    }
    
    @Benchmark
    public long ffmRandomByteRead() {
        long sum = 0;
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + VALID_MASK_OFFSET;
            sum += ffmBuffer.get(ValueLayout.JAVA_BYTE, offset);
        }
        return sum;
    }
    
    @Benchmark
    public long onHeapRandomByteRead() {
        long sum = 0;
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + VALID_MASK_OFFSET;
            sum += onHeapBuffer.get(offset);
        }
        return sum;
    }
    
    @Benchmark
    public long offHeapRandomByteRead() {
        long sum = 0;
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + VALID_MASK_OFFSET;
            sum += offHeapBuffer.get(offset);
        }
        return sum;
    }
    
    @Benchmark
    public void ffmRandomIntWrite(Blackhole bh) {
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + CONTOUR_PTR_OFFSET;
            ffmBuffer.set(ValueLayout.JAVA_INT, offset, testInts[i]);
        }
        bh.consume(ffmBuffer);
    }
    
    @Benchmark
    public void onHeapRandomIntWrite(Blackhole bh) {
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + CONTOUR_PTR_OFFSET;
            onHeapBuffer.putInt(offset, testInts[i]);
        }
        bh.consume(onHeapBuffer);
    }
    
    @Benchmark
    public void offHeapRandomIntWrite(Blackhole bh) {
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + CONTOUR_PTR_OFFSET;
            offHeapBuffer.putInt(offset, testInts[i]);
        }
        bh.consume(offHeapBuffer);
    }
    
    @Benchmark
    public long ffmRandomIntRead() {
        long sum = 0;
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + CONTOUR_PTR_OFFSET;
            sum += ffmBuffer.get(ValueLayout.JAVA_INT, offset);
        }
        return sum;
    }
    
    @Benchmark
    public long onHeapRandomIntRead() {
        long sum = 0;
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + CONTOUR_PTR_OFFSET;
            sum += onHeapBuffer.getInt(offset);
        }
        return sum;
    }
    
    @Benchmark
    public long offHeapRandomIntRead() {
        long sum = 0;
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + CONTOUR_PTR_OFFSET;
            sum += offHeapBuffer.getInt(offset);
        }
        return sum;
    }
    
    @Benchmark
    public void ffmRandomFloatWrite(Blackhole bh) {
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + BOUNDING_BOX_OFFSET;
            ffmBuffer.set(ValueLayout.JAVA_FLOAT, offset, testFloats[i]);
        }
        bh.consume(ffmBuffer);
    }
    
    @Benchmark
    public void onHeapRandomFloatWrite(Blackhole bh) {
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + BOUNDING_BOX_OFFSET;
            onHeapBuffer.putFloat(offset, testFloats[i]);
        }
        bh.consume(onHeapBuffer);
    }
    
    @Benchmark
    public void offHeapRandomFloatWrite(Blackhole bh) {
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + BOUNDING_BOX_OFFSET;
            offHeapBuffer.putFloat(offset, testFloats[i]);
        }
        bh.consume(offHeapBuffer);
    }
    
    @Benchmark
    public double ffmRandomFloatRead() {
        double sum = 0.0;
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + BOUNDING_BOX_OFFSET;
            sum += ffmBuffer.get(ValueLayout.JAVA_FLOAT, offset);
        }
        return sum;
    }
    
    @Benchmark
    public double onHeapRandomFloatRead() {
        double sum = 0.0;
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + BOUNDING_BOX_OFFSET;
            sum += onHeapBuffer.getFloat(offset);
        }
        return sum;
    }
    
    @Benchmark
    public double offHeapRandomFloatRead() {
        double sum = 0.0;
        for (int i = 0; i < RANDOM_ACCESS_COUNT; i++) {
            int nodeIndex = randomIndices[i];
            int offset = nodeIndex * NODE_SIZE + BOUNDING_BOX_OFFSET;
            sum += offHeapBuffer.getFloat(offset);
        }
        return sum;
    }
    
    // ================================================================================
    // Bulk Operations Benchmarks
    // ================================================================================
    
    @Benchmark
    public void ffmBulkCopy(Blackhole bh) {
        MemorySegment.copy(bulkData, 0, ffmBuffer, ValueLayout.JAVA_BYTE, 0, BULK_OPERATION_SIZE);
        bh.consume(ffmBuffer);
    }
    
    @Benchmark
    public void onHeapBulkCopy(Blackhole bh) {
        onHeapBuffer.clear();
        onHeapBuffer.put(0, bulkData, 0, BULK_OPERATION_SIZE);
        bh.consume(onHeapBuffer);
    }
    
    @Benchmark
    public void offHeapBulkCopy(Blackhole bh) {
        offHeapBuffer.clear();
        offHeapBuffer.put(0, bulkData, 0, BULK_OPERATION_SIZE);
        bh.consume(offHeapBuffer);
    }
    
    @Benchmark
    public void ffmBulkFill(Blackhole bh) {
        ffmBuffer.fill((byte) 0xAB);
        bh.consume(ffmBuffer);
    }
    
    @Benchmark
    public void onHeapBulkFill(Blackhole bh) {
        onHeapBuffer.clear();
        for (int i = 0; i < BUFFER_SIZE; i++) {
            onHeapBuffer.put(i, (byte) 0xAB);
        }
        bh.consume(onHeapBuffer);
    }
    
    @Benchmark
    public void offHeapBulkFill(Blackhole bh) {
        offHeapBuffer.clear();
        for (int i = 0; i < BUFFER_SIZE; i++) {
            offHeapBuffer.put(i, (byte) 0xAB);
        }
        bh.consume(offHeapBuffer);
    }
    
    @Benchmark
    public boolean ffmBulkEquals() {
        return ffmBuffer.mismatch(ffmBuffer) == -1;
    }
    
    @Benchmark
    public boolean onHeapBulkEquals() {
        return onHeapBuffer.equals(onHeapBuffer);
    }
    
    @Benchmark
    public boolean offHeapBulkEquals() {
        return offHeapBuffer.equals(offHeapBuffer);
    }
    
    // ================================================================================
    // Struct-like Data Access Benchmarks (VoxelOctreeNode simulation)
    // ================================================================================
    
    @Benchmark
    public void ffmStructWrite(Blackhole bh) {
        for (int i = 0; i < NODES_PER_BUFFER; i++) {
            long nodeOffset = i * NODE_SIZE;
            
            // Write struct fields
            ffmBuffer.set(ValueLayout.JAVA_BYTE, nodeOffset + VALID_MASK_OFFSET, (byte) (i & 0xFF));
            ffmBuffer.set(ValueLayout.JAVA_BYTE, nodeOffset + FLAGS_OFFSET, (byte) ((i >> 8) & 0xFF));
            ffmBuffer.set(ValueLayout.JAVA_BYTE, nodeOffset + CHILD_PTR_OFFSET, (byte) (i & 0x7F));
            ffmBuffer.set(ValueLayout.JAVA_BYTE, nodeOffset + FAR_FLAG_OFFSET, (byte) ((i & 1) == 1 ? 1 : 0));
            ffmBuffer.set(ValueLayout.JAVA_BYTE, nodeOffset + CONTOUR_MASK_OFFSET, (byte) (~i & 0xFF));
            ffmBuffer.set(ValueLayout.JAVA_INT, nodeOffset + CONTOUR_PTR_OFFSET, i * 1000);
            
            // Write bounding box (6 floats)
            for (int j = 0; j < 6; j++) {
                ffmBuffer.set(ValueLayout.JAVA_FLOAT, nodeOffset + BOUNDING_BOX_OFFSET + j * 4L, 
                             i * 1.5f + j * 0.1f);
            }
            
            // Write color data (4 floats)
            for (int j = 0; j < 4; j++) {
                ffmBuffer.set(ValueLayout.JAVA_FLOAT, nodeOffset + COLOR_DATA_OFFSET + j * 4L,
                             (i % 256) / 255.0f + j * 0.25f);
            }
            
            ffmBuffer.set(ValueLayout.JAVA_INT, nodeOffset + METADATA_OFFSET, i ^ 0xDEADBEEF);
        }
        bh.consume(ffmBuffer);
    }
    
    @Benchmark
    public void onHeapStructWrite(Blackhole bh) {
        onHeapBuffer.clear();
        for (int i = 0; i < NODES_PER_BUFFER; i++) {
            int nodeOffset = i * NODE_SIZE;
            
            // Write struct fields
            onHeapBuffer.put(nodeOffset + VALID_MASK_OFFSET, (byte) (i & 0xFF));
            onHeapBuffer.put(nodeOffset + FLAGS_OFFSET, (byte) ((i >> 8) & 0xFF));
            onHeapBuffer.put(nodeOffset + CHILD_PTR_OFFSET, (byte) (i & 0x7F));
            onHeapBuffer.put(nodeOffset + FAR_FLAG_OFFSET, (byte) ((i & 1) == 1 ? 1 : 0));
            onHeapBuffer.put(nodeOffset + CONTOUR_MASK_OFFSET, (byte) (~i & 0xFF));
            onHeapBuffer.putInt(nodeOffset + CONTOUR_PTR_OFFSET, i * 1000);
            
            // Write bounding box (6 floats)
            for (int j = 0; j < 6; j++) {
                onHeapBuffer.putFloat(nodeOffset + BOUNDING_BOX_OFFSET + j * 4,
                                     i * 1.5f + j * 0.1f);
            }
            
            // Write color data (4 floats)
            for (int j = 0; j < 4; j++) {
                onHeapBuffer.putFloat(nodeOffset + COLOR_DATA_OFFSET + j * 4,
                                     (i % 256) / 255.0f + j * 0.25f);
            }
            
            onHeapBuffer.putInt(nodeOffset + METADATA_OFFSET, i ^ 0xDEADBEEF);
        }
        bh.consume(onHeapBuffer);
    }
    
    @Benchmark
    public void offHeapStructWrite(Blackhole bh) {
        offHeapBuffer.clear();
        for (int i = 0; i < NODES_PER_BUFFER; i++) {
            int nodeOffset = i * NODE_SIZE;
            
            // Write struct fields
            offHeapBuffer.put(nodeOffset + VALID_MASK_OFFSET, (byte) (i & 0xFF));
            offHeapBuffer.put(nodeOffset + FLAGS_OFFSET, (byte) ((i >> 8) & 0xFF));
            offHeapBuffer.put(nodeOffset + CHILD_PTR_OFFSET, (byte) (i & 0x7F));
            offHeapBuffer.put(nodeOffset + FAR_FLAG_OFFSET, (byte) ((i & 1) == 1 ? 1 : 0));
            offHeapBuffer.put(nodeOffset + CONTOUR_MASK_OFFSET, (byte) (~i & 0xFF));
            offHeapBuffer.putInt(nodeOffset + CONTOUR_PTR_OFFSET, i * 1000);
            
            // Write bounding box (6 floats)
            for (int j = 0; j < 6; j++) {
                offHeapBuffer.putFloat(nodeOffset + BOUNDING_BOX_OFFSET + j * 4,
                                      i * 1.5f + j * 0.1f);
            }
            
            // Write color data (4 floats)
            for (int j = 0; j < 4; j++) {
                offHeapBuffer.putFloat(nodeOffset + COLOR_DATA_OFFSET + j * 4,
                                      (i % 256) / 255.0f + j * 0.25f);
            }
            
            offHeapBuffer.putInt(nodeOffset + METADATA_OFFSET, i ^ 0xDEADBEEF);
        }
        bh.consume(offHeapBuffer);
    }
    
    @Benchmark
    public double ffmStructRead() {
        double checksum = 0.0;
        for (int i = 0; i < NODES_PER_BUFFER; i++) {
            long nodeOffset = i * NODE_SIZE;
            
            // Read struct fields
            checksum += ffmBuffer.get(ValueLayout.JAVA_BYTE, nodeOffset + VALID_MASK_OFFSET);
            checksum += ffmBuffer.get(ValueLayout.JAVA_BYTE, nodeOffset + FLAGS_OFFSET);
            checksum += ffmBuffer.get(ValueLayout.JAVA_BYTE, nodeOffset + CHILD_PTR_OFFSET);
            checksum += ffmBuffer.get(ValueLayout.JAVA_BYTE, nodeOffset + FAR_FLAG_OFFSET);
            checksum += ffmBuffer.get(ValueLayout.JAVA_BYTE, nodeOffset + CONTOUR_MASK_OFFSET);
            checksum += ffmBuffer.get(ValueLayout.JAVA_INT, nodeOffset + CONTOUR_PTR_OFFSET);
            
            // Read bounding box (6 floats)
            for (int j = 0; j < 6; j++) {
                checksum += ffmBuffer.get(ValueLayout.JAVA_FLOAT, nodeOffset + BOUNDING_BOX_OFFSET + j * 4L);
            }
            
            // Read color data (4 floats)
            for (int j = 0; j < 4; j++) {
                checksum += ffmBuffer.get(ValueLayout.JAVA_FLOAT, nodeOffset + COLOR_DATA_OFFSET + j * 4L);
            }
            
            checksum += ffmBuffer.get(ValueLayout.JAVA_INT, nodeOffset + METADATA_OFFSET);
        }
        return checksum;
    }
    
    @Benchmark
    public double onHeapStructRead() {
        double checksum = 0.0;
        for (int i = 0; i < NODES_PER_BUFFER; i++) {
            int nodeOffset = i * NODE_SIZE;
            
            // Read struct fields
            checksum += onHeapBuffer.get(nodeOffset + VALID_MASK_OFFSET);
            checksum += onHeapBuffer.get(nodeOffset + FLAGS_OFFSET);
            checksum += onHeapBuffer.get(nodeOffset + CHILD_PTR_OFFSET);
            checksum += onHeapBuffer.get(nodeOffset + FAR_FLAG_OFFSET);
            checksum += onHeapBuffer.get(nodeOffset + CONTOUR_MASK_OFFSET);
            checksum += onHeapBuffer.getInt(nodeOffset + CONTOUR_PTR_OFFSET);
            
            // Read bounding box (6 floats)
            for (int j = 0; j < 6; j++) {
                checksum += onHeapBuffer.getFloat(nodeOffset + BOUNDING_BOX_OFFSET + j * 4);
            }
            
            // Read color data (4 floats)
            for (int j = 0; j < 4; j++) {
                checksum += onHeapBuffer.getFloat(nodeOffset + COLOR_DATA_OFFSET + j * 4);
            }
            
            checksum += onHeapBuffer.getInt(nodeOffset + METADATA_OFFSET);
        }
        return checksum;
    }
    
    @Benchmark
    public double offHeapStructRead() {
        double checksum = 0.0;
        for (int i = 0; i < NODES_PER_BUFFER; i++) {
            int nodeOffset = i * NODE_SIZE;
            
            // Read struct fields
            checksum += offHeapBuffer.get(nodeOffset + VALID_MASK_OFFSET);
            checksum += offHeapBuffer.get(nodeOffset + FLAGS_OFFSET);
            checksum += offHeapBuffer.get(nodeOffset + CHILD_PTR_OFFSET);
            checksum += offHeapBuffer.get(nodeOffset + FAR_FLAG_OFFSET);
            checksum += offHeapBuffer.get(nodeOffset + CONTOUR_MASK_OFFSET);
            checksum += offHeapBuffer.getInt(nodeOffset + CONTOUR_PTR_OFFSET);
            
            // Read bounding box (6 floats)
            for (int j = 0; j < 6; j++) {
                checksum += offHeapBuffer.getFloat(nodeOffset + BOUNDING_BOX_OFFSET + j * 4);
            }
            
            // Read color data (4 floats)
            for (int j = 0; j < 4; j++) {
                checksum += offHeapBuffer.getFloat(nodeOffset + COLOR_DATA_OFFSET + j * 4);
            }
            
            checksum += offHeapBuffer.getInt(nodeOffset + METADATA_OFFSET);
        }
        return checksum;
    }
    
    // ================================================================================
    // Thread-Local vs Shared Access Benchmarks
    // ================================================================================
    
    @Benchmark
    @Group("shared_access")
    @GroupThreads(4)
    public void ffmSharedWrite(Blackhole bh) {
        var random = ThreadLocalRandom.current();
        for (int i = 0; i < 100; i++) {
            int offset = random.nextInt(BUFFER_SIZE - 4);
            ffmBuffer.set(ValueLayout.JAVA_INT, offset, random.nextInt());
        }
        bh.consume(ffmBuffer);
    }
    
    @Benchmark
    @Group("shared_access")
    @GroupThreads(4)
    public long ffmSharedRead() {
        var random = ThreadLocalRandom.current();
        long sum = 0;
        for (int i = 0; i < 100; i++) {
            int offset = random.nextInt(BUFFER_SIZE - 4);
            sum += ffmBuffer.get(ValueLayout.JAVA_INT, offset);
        }
        return sum;
    }
    
    @Benchmark
    @Group("shared_access")
    @GroupThreads(4)
    public void offHeapSharedWrite(Blackhole bh) {
        var random = ThreadLocalRandom.current();
        for (int i = 0; i < 100; i++) {
            int offset = random.nextInt(BUFFER_SIZE - 4);
            synchronized(offHeapBuffer) {
                offHeapBuffer.putInt(offset, random.nextInt());
            }
        }
        bh.consume(offHeapBuffer);
    }
    
    @Benchmark
    @Group("shared_access")
    @GroupThreads(4)
    public long offHeapSharedRead() {
        var random = ThreadLocalRandom.current();
        long sum = 0;
        for (int i = 0; i < 100; i++) {
            int offset = random.nextInt(BUFFER_SIZE - 4);
            synchronized(offHeapBuffer) {
                sum += offHeapBuffer.getInt(offset);
            }
        }
        return sum;
    }
    
    @Benchmark
    @Group("thread_local_access")
    @GroupThreads(4)
    public void ffmThreadLocalWrite(Blackhole bh) {
        var random = ThreadLocalRandom.current();
        for (int i = 0; i < 100; i++) {
            int offset = random.nextInt(BUFFER_SIZE - 4);
            threadLocalFFMBuffer.set(ValueLayout.JAVA_INT, offset, random.nextInt());
        }
        bh.consume(threadLocalFFMBuffer);
    }
    
    @Benchmark
    @Group("thread_local_access")
    @GroupThreads(4)
    public long ffmThreadLocalRead() {
        var random = ThreadLocalRandom.current();
        long sum = 0;
        for (int i = 0; i < 100; i++) {
            int offset = random.nextInt(BUFFER_SIZE - 4);
            sum += threadLocalFFMBuffer.get(ValueLayout.JAVA_INT, offset);
        }
        return sum;
    }
    
    @Benchmark
    @Group("thread_local_access")
    @GroupThreads(4)
    public void offHeapThreadLocalWrite(Blackhole bh) {
        var random = ThreadLocalRandom.current();
        for (int i = 0; i < 100; i++) {
            int offset = random.nextInt(BUFFER_SIZE - 4);
            threadLocalOffHeapBuffer.putInt(offset, random.nextInt());
        }
        bh.consume(threadLocalOffHeapBuffer);
    }
    
    @Benchmark
    @Group("thread_local_access")
    @GroupThreads(4)
    public long offHeapThreadLocalRead() {
        var random = ThreadLocalRandom.current();
        long sum = 0;
        for (int i = 0; i < 100; i++) {
            int offset = random.nextInt(BUFFER_SIZE - 4);
            sum += threadLocalOffHeapBuffer.getInt(offset);
        }
        return sum;
    }
    
    // ================================================================================
    // Complex Mixed Operations Benchmarks
    // ================================================================================
    
    @Benchmark
    public double ffmComplexMixedOperations() {
        double result = 0.0;
        var random = ThreadLocalRandom.current();
        
        // Mixed read/write operations simulating real voxel octree usage
        for (int i = 0; i < 100; i++) {
            int nodeIndex = random.nextInt(NODES_PER_BUFFER);
            long nodeOffset = nodeIndex * NODE_SIZE;
            
            // Read some fields
            byte validMask = ffmBuffer.get(ValueLayout.JAVA_BYTE, nodeOffset + VALID_MASK_OFFSET);
            int contourPtr = ffmBuffer.get(ValueLayout.JAVA_INT, nodeOffset + CONTOUR_PTR_OFFSET);
            
            // Conditional write based on read data
            if ((validMask & 0x01) != 0) {
                ffmBuffer.set(ValueLayout.JAVA_BYTE, nodeOffset + FLAGS_OFFSET, (byte) (validMask | 0x80));
                ffmBuffer.set(ValueLayout.JAVA_INT, nodeOffset + METADATA_OFFSET, contourPtr ^ 0xAAAAAAAA);
            }
            
            // Update bounding box based on some computation
            for (int j = 0; j < 6; j++) {
                float oldValue = ffmBuffer.get(ValueLayout.JAVA_FLOAT, nodeOffset + BOUNDING_BOX_OFFSET + j * 4L);
                ffmBuffer.set(ValueLayout.JAVA_FLOAT, nodeOffset + BOUNDING_BOX_OFFSET + j * 4L, 
                             oldValue * 1.01f + random.nextFloat() * 0.1f);
            }
            
            result += validMask + contourPtr;
        }
        return result;
    }
    
    @Benchmark
    public double onHeapComplexMixedOperations() {
        double result = 0.0;
        var random = ThreadLocalRandom.current();
        
        // Mixed read/write operations simulating real voxel octree usage
        for (int i = 0; i < 100; i++) {
            int nodeIndex = random.nextInt(NODES_PER_BUFFER);
            int nodeOffset = nodeIndex * NODE_SIZE;
            
            // Read some fields
            byte validMask = onHeapBuffer.get(nodeOffset + VALID_MASK_OFFSET);
            int contourPtr = onHeapBuffer.getInt(nodeOffset + CONTOUR_PTR_OFFSET);
            
            // Conditional write based on read data
            if ((validMask & 0x01) != 0) {
                onHeapBuffer.put(nodeOffset + FLAGS_OFFSET, (byte) (validMask | 0x80));
                onHeapBuffer.putInt(nodeOffset + METADATA_OFFSET, contourPtr ^ 0xAAAAAAAA);
            }
            
            // Update bounding box based on some computation
            for (int j = 0; j < 6; j++) {
                float oldValue = onHeapBuffer.getFloat(nodeOffset + BOUNDING_BOX_OFFSET + j * 4);
                onHeapBuffer.putFloat(nodeOffset + BOUNDING_BOX_OFFSET + j * 4,
                                     oldValue * 1.01f + random.nextFloat() * 0.1f);
            }
            
            result += validMask + contourPtr;
        }
        return result;
    }
    
    @Benchmark
    public double offHeapComplexMixedOperations() {
        double result = 0.0;
        var random = ThreadLocalRandom.current();
        
        // Mixed read/write operations simulating real voxel octree usage
        for (int i = 0; i < 100; i++) {
            int nodeIndex = random.nextInt(NODES_PER_BUFFER);
            int nodeOffset = nodeIndex * NODE_SIZE;
            
            // Read some fields
            byte validMask = offHeapBuffer.get(nodeOffset + VALID_MASK_OFFSET);
            int contourPtr = offHeapBuffer.getInt(nodeOffset + CONTOUR_PTR_OFFSET);
            
            // Conditional write based on read data
            if ((validMask & 0x01) != 0) {
                offHeapBuffer.put(nodeOffset + FLAGS_OFFSET, (byte) (validMask | 0x80));
                offHeapBuffer.putInt(nodeOffset + METADATA_OFFSET, contourPtr ^ 0xAAAAAAAA);
            }
            
            // Update bounding box based on some computation
            for (int j = 0; j < 6; j++) {
                float oldValue = offHeapBuffer.getFloat(nodeOffset + BOUNDING_BOX_OFFSET + j * 4);
                offHeapBuffer.putFloat(nodeOffset + BOUNDING_BOX_OFFSET + j * 4,
                                      oldValue * 1.01f + random.nextFloat() * 0.1f);
            }
            
            result += validMask + contourPtr;
        }
        return result;
    }
    
    // ================================================================================
    // Main Method for Running Benchmarks
    // ================================================================================
    
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(FFMvsByteBufferBenchmark.class.getSimpleName())
            .mode(Mode.AverageTime)
            .timeUnit(TimeUnit.NANOSECONDS)
            .warmupIterations(3)
            .warmupTime(TimeValue.seconds(2))
            .measurementIterations(5)
            .measurementTime(TimeValue.seconds(2))
            .forks(2)
            .threads(1)
            .jvmArgs(
                "-XX:+UseG1GC",
                "-Xmx4g",
                "-Xms2g",
                "--enable-preview",
                "--add-modules=jdk.incubator.foreign"
            )
            .resultFormat(ResultFormatType.JSON)
            .result("render/target/ffm-vs-bytebuffer-benchmark-results.json")
            .build();
        
        new Runner(opt).run();
    }
}