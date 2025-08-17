/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * GPU-based work queue implementation for persistent threads.
 * Manages work distribution across persistent GPU threads using SSBO.
 */
public class PersistentWorkQueue {
    private static final Logger log = LoggerFactory.getLogger(PersistentWorkQueue.class);
    
    private static final int WORK_ITEM_SIZE = 32; // bytes per work item
    private static final int QUEUE_HEADER_SIZE = 64; // bytes for queue metadata
    
    private final int maxWorkItems;
    private final int queueSSBO;
    private final int counterSSBO;
    private final ByteBuffer workQueueBuffer;
    private final IntBuffer counterBuffer;
    
    private final AtomicInteger localHead = new AtomicInteger(0);
    private final AtomicInteger localTail = new AtomicInteger(0);
    private final ReentrantLock queueLock = new ReentrantLock();
    
    private volatile boolean initialized = false;
    private volatile boolean shutdown = false;
    
    public PersistentWorkQueue(int maxWorkItems) {
        this.maxWorkItems = maxWorkItems;
        
        // Allocate CPU-side buffers
        workQueueBuffer = MemoryUtil.memAlloc(QUEUE_HEADER_SIZE + maxWorkItems * WORK_ITEM_SIZE);
        counterBuffer = MemoryUtil.memAllocInt(4); // head, tail, size, overflow_count
        
        // Initialize buffers
        workQueueBuffer.putInt(0, maxWorkItems); // max items
        workQueueBuffer.putInt(4, WORK_ITEM_SIZE); // item size
        counterBuffer.put(0, 0); // head
        counterBuffer.put(1, 0); // tail
        counterBuffer.put(2, 0); // current size
        counterBuffer.put(3, 0); // overflow count
        
        // Create GPU buffers
        queueSSBO = GL43.glGenBuffers();
        counterSSBO = GL43.glGenBuffers();
        
        initializeGPUBuffers();
    }
    
    private void initializeGPUBuffers() {
        // Initialize work queue SSBO
        GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, queueSSBO);
        GL43.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 
                         workQueueBuffer.capacity(), 
                         GL43.GL_DYNAMIC_DRAW);
        GL43.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, workQueueBuffer);
        
        // Initialize counter SSBO
        GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, counterSSBO);
        GL43.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 
                         counterBuffer.capacity() * Integer.BYTES, 
                         GL43.GL_DYNAMIC_DRAW);
        GL43.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, counterBuffer);
        
        GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        
        initialized = true;
        log.debug("Initialized persistent work queue with {} work items", maxWorkItems);
    }
    
    /**
     * Submits a work item to the queue.
     */
    public boolean submitWork(WorkItem item) {
        if (shutdown || !initialized) {
            return false;
        }
        
        queueLock.lock();
        try {
            int tail = localTail.get();
            int head = localHead.get();
            int nextTail = (tail + 1) % maxWorkItems;
            
            // Check if queue is full
            if (nextTail == head) {
                log.debug("Work queue full, dropping work item");
                return false;
            }
            
            // Write work item to buffer
            int offset = QUEUE_HEADER_SIZE + tail * WORK_ITEM_SIZE;
            item.writeTo(workQueueBuffer, offset);
            
            // Update local tail
            localTail.set(nextTail);
            
            // Update GPU counter
            updateGPUCounters();
            
            return true;
            
        } finally {
            queueLock.unlock();
        }
    }
    
    /**
     * Submits multiple work items efficiently.
     */
    public int submitWorkBatch(WorkItem[] items) {
        if (shutdown || !initialized) {
            return 0;
        }
        
        queueLock.lock();
        try {
            int submitted = 0;
            int tail = localTail.get();
            int head = localHead.get();
            
            for (WorkItem item : items) {
                int nextTail = (tail + 1) % maxWorkItems;
                
                if (nextTail == head) {
                    break; // Queue full
                }
                
                int offset = QUEUE_HEADER_SIZE + tail * WORK_ITEM_SIZE;
                item.writeTo(workQueueBuffer, offset);
                
                tail = nextTail;
                submitted++;
            }
            
            localTail.set(tail);
            updateGPUCounters();
            
            return submitted;
            
        } finally {
            queueLock.unlock();
        }
    }
    
    /**
     * Gets the current queue size.
     */
    public int getQueueSize() {
        int tail = localTail.get();
        int head = localHead.get();
        return tail >= head ? tail - head : maxWorkItems - head + tail;
    }
    
    /**
     * Checks if the queue is empty.
     */
    public boolean isEmpty() {
        return localHead.get() == localTail.get();
    }
    
    /**
     * Checks if the queue is full.
     */
    public boolean isFull() {
        int tail = localTail.get();
        int head = localHead.get();
        return (tail + 1) % maxWorkItems == head;
    }
    
    /**
     * Gets the queue utilization as a percentage.
     */
    public float getUtilization() {
        return (float) getQueueSize() / maxWorkItems;
    }
    
    /**
     * Syncs queue state with GPU.
     */
    public void syncWithGPU() {
        if (!initialized) return;
        
        // Read counters from GPU
        GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, counterSSBO);
        ByteBuffer gpuCounters = GL43.glMapBuffer(GL43.GL_SHADER_STORAGE_BUFFER, GL43.GL_READ_ONLY);
        
        if (gpuCounters != null) {
            int gpuHead = gpuCounters.getInt(0);
            int gpuTail = gpuCounters.getInt(4);
            int currentSize = gpuCounters.getInt(8);
            int overflowCount = gpuCounters.getInt(12);
            
            GL43.glUnmapBuffer(GL43.GL_SHADER_STORAGE_BUFFER);
            
            // Update local counters
            localHead.set(gpuHead);
            
            if (overflowCount > 0) {
                log.warn("GPU work queue experienced {} overflows", overflowCount);
            }
            
            log.debug("GPU queue state - Head: {}, Tail: {}, Size: {}", 
                     gpuHead, gpuTail, currentSize);
        }
        
        GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }
    
    /**
     * Binds the work queue to shader storage binding points.
     */
    public void bindToShader(int queueBinding, int counterBinding) {
        if (!initialized) return;
        
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, queueBinding, queueSSBO);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, counterBinding, counterSSBO);
    }
    
    /**
     * Updates the work queue data on GPU.
     */
    public void uploadToGPU() {
        if (!initialized) return;
        
        GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, queueSSBO);
        GL43.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, workQueueBuffer);
        GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }
    
    /**
     * Resets the queue to empty state.
     */
    public void reset() {
        queueLock.lock();
        try {
            localHead.set(0);
            localTail.set(0);
            
            // Reset GPU counters
            counterBuffer.put(0, 0);
            counterBuffer.put(1, 0);
            counterBuffer.put(2, 0);
            counterBuffer.put(3, 0);
            
            updateGPUCounters();
            
        } finally {
            queueLock.unlock();
        }
    }
    
    /**
     * Gets queue statistics.
     */
    public QueueStatistics getStatistics() {
        syncWithGPU();
        
        return new QueueStatistics(
            getQueueSize(),
            maxWorkItems,
            getUtilization(),
            counterBuffer.get(3) // overflow count
        );
    }
    
    /**
     * Shuts down the work queue and releases resources.
     */
    public void shutdown() {
        shutdown = true;
        
        if (initialized) {
            GL43.glDeleteBuffers(queueSSBO);
            GL43.glDeleteBuffers(counterSSBO);
        }
        
        MemoryUtil.memFree(workQueueBuffer);
        MemoryUtil.memFree(counterBuffer);
        
        log.debug("Persistent work queue shut down");
    }
    
    private void updateGPUCounters() {
        counterBuffer.put(0, localHead.get());
        counterBuffer.put(1, localTail.get());
        counterBuffer.put(2, getQueueSize());
        
        GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, counterSSBO);
        GL43.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, counterBuffer);
        GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }
    
    /**
     * Work item for the persistent queue.
     */
    public static class WorkItem {
        public static final int RAY_TRAVERSAL = 0;
        public static final int SHADOW_RAY = 1;
        public static final int AMBIENT_OCCLUSION = 2;
        public static final int BEAM_TRAVERSAL = 3;
        
        private final int type;
        private final float[] data;
        
        public WorkItem(int type, float[] data) {
            this.type = type;
            this.data = data != null ? data : new float[0];
        }
        
        public static WorkItem createRayTraversal(float[] origin, float[] direction, 
                                                 int pixelX, int pixelY) {
            float[] data = new float[8];
            System.arraycopy(origin, 0, data, 0, 3);
            System.arraycopy(direction, 0, data, 3, 3);
            data[6] = Float.intBitsToFloat(pixelX);
            data[7] = Float.intBitsToFloat(pixelY);
            return new WorkItem(RAY_TRAVERSAL, data);
        }
        
        public static WorkItem createShadowRay(float[] origin, float[] direction, 
                                              float maxDistance, int lightIndex) {
            float[] data = new float[8];
            System.arraycopy(origin, 0, data, 0, 3);
            System.arraycopy(direction, 0, data, 3, 3);
            data[6] = maxDistance;
            data[7] = Float.intBitsToFloat(lightIndex);
            return new WorkItem(SHADOW_RAY, data);
        }
        
        public static WorkItem createAOSample(float[] position, float[] normal, 
                                             float radius, int sampleIndex) {
            float[] data = new float[8];
            System.arraycopy(position, 0, data, 0, 3);
            System.arraycopy(normal, 0, data, 3, 3);
            data[6] = radius;
            data[7] = Float.intBitsToFloat(sampleIndex);
            return new WorkItem(AMBIENT_OCCLUSION, data);
        }
        
        public void writeTo(ByteBuffer buffer, int offset) {
            buffer.putInt(offset, type);
            buffer.putInt(offset + 4, data.length);
            
            for (int i = 0; i < data.length && i < 6; i++) {
                buffer.putFloat(offset + 8 + i * 4, data[i]);
            }
            
            // Pad remaining space
            for (int i = data.length; i < 6; i++) {
                buffer.putFloat(offset + 8 + i * 4, 0.0f);
            }
        }
        
        public int getType() {
            return type;
        }
        
        public float[] getData() {
            return data.clone();
        }
    }
    
    /**
     * Queue statistics.
     */
    public static class QueueStatistics {
        private final int currentSize;
        private final int maxSize;
        private final float utilization;
        private final int overflowCount;
        
        public QueueStatistics(int currentSize, int maxSize, float utilization, int overflowCount) {
            this.currentSize = currentSize;
            this.maxSize = maxSize;
            this.utilization = utilization;
            this.overflowCount = overflowCount;
        }
        
        public int getCurrentSize() { return currentSize; }
        public int getMaxSize() { return maxSize; }
        public float getUtilization() { return utilization; }
        public int getOverflowCount() { return overflowCount; }
        
        @Override
        public String toString() {
            return String.format("Queue[%d/%d, %.1f%%, %d overflows]", 
                               currentSize, maxSize, utilization * 100, overflowCount);
        }
    }
}