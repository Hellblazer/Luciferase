/**
 * Memory Management for Voxel Operations
 * 
 * Efficient memory allocation and pooling systems for voxel data structures.
 * Includes PageAllocator for large memory blocks and MemoryPool for object reuse
 * to minimize garbage collection overhead during intensive voxel operations.
 * 
 * The memory management system is designed to handle the high allocation rates
 * typical of voxel processing while maintaining low latency and predictable
 * performance characteristics.
 */
package com.hellblazer.luciferase.render.voxel.memory;