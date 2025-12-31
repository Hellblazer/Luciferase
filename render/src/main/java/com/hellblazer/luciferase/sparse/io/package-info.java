/**
 * Shared I/O infrastructure for sparse voxel data structures.
 *
 * <p>This package provides common I/O utilities used by both ESVO (octree)
 * and ESVT (tetrahedral) serialization implementations:
 *
 * <ul>
 *   <li>{@link com.hellblazer.luciferase.sparse.io.SparseVoxelIOUtils} -
 *       Shared utility methods for serialization, compression, and buffer management</li>
 * </ul>
 *
 * <p><b>Architecture:</b> Both ESVO and ESVT I/O classes share ~75-80% of
 * functionality through these utilities, with type-specific implementations
 * for headers and data section layouts.
 *
 * @author hal.hildebrand
 * @see com.hellblazer.luciferase.esvo.io
 * @see com.hellblazer.luciferase.esvt.io
 */
package com.hellblazer.luciferase.sparse.io;
