/**
 * Core interfaces and classes for sparse voxel data structures.
 *
 * <p>This package provides shared abstractions for both ESVO (octree-based) and
 * ESVT (tetrahedra-based) sparse voxel implementations.
 *
 * <h2>Coordinate Space</h2>
 * <p>All implementations use a unified [0,1] coordinate space:
 * <ul>
 *   <li>Origin at (0, 0, 0)</li>
 *   <li>Bounds at (1, 1, 1)</li>
 *   <li>Center at (0.5, 0.5, 0.5)</li>
 * </ul>
 *
 * <h2>Thread Safety Guidelines</h2>
 *
 * <h3>Immutable Classes (Thread-Safe)</h3>
 * <ul>
 *   <li>{@link TraversalConstants} - All static final fields</li>
 *   <li>{@link SparseCoordinateSpace} - All static methods, no state</li>
 * </ul>
 *
 * <h3>Interface Contracts</h3>
 * <ul>
 *   <li>{@link SparseVoxelNode} - Implementations must be thread-safe for concurrent reads.
 *       Write operations require external synchronization.</li>
 *   <li>{@link SparseVoxelData} - Same contract as SparseVoxelNode.
 *       The returned arrays should be treated as read-only.</li>
 * </ul>
 *
 * <h3>Mutable Classes (NOT Thread-Safe)</h3>
 * <ul>
 *   <li>{@link TraversalResult} - Each thread must use its own instance.
 *       Use pooling with thread-local storage for efficiency.</li>
 * </ul>
 *
 * <h3>Recommended Patterns</h3>
 * <pre>{@code
 * // Thread-safe read access
 * var data = octree.getData();  // SparseVoxelData
 * var node = data.getNode(0);   // Thread-safe read
 * var mask = node.getChildMask();
 *
 * // Thread-local result pooling
 * private static final ThreadLocal<TraversalResult> resultPool =
 *     ThreadLocal.withInitial(TraversalResult::new);
 *
 * public TraversalResult traverse(Ray ray) {
 *     var result = resultPool.get();
 *     result.reset();
 *     // ... perform traversal ...
 *     return result;  // Caller should copy if needed across threads
 * }
 * }</pre>
 *
 * <h3>GPU Considerations</h3>
 * <p>When uploading to GPU:
 * <ul>
 *   <li>Use {@link SparseVoxelData#nodesToByteBuffer()} for node data</li>
 *   <li>ByteBuffers are created fresh each call (no caching)</li>
 *   <li>Upload should complete before modifying source data</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @see com.hellblazer.luciferase.esvo.core
 * @see com.hellblazer.luciferase.esvt.core
 */
package com.hellblazer.luciferase.sparse.core;
