/**
 * Generic GPU rendering framework for sparse voxel data structures.
 *
 * <p>This package provides the shared GPU rendering infrastructure used by both
 * ESVO (octree) and ESVT (tetrahedral) implementations:
 *
 * <ul>
 *   <li>{@link com.hellblazer.luciferase.sparse.gpu.AbstractOpenCLRenderer} -
 *       Base class for OpenCL-based GPU renderers</li>
 * </ul>
 *
 * <p><b>Architecture:</b> Both ESVO and ESVT OpenCL renderers extend
 * {@link com.hellblazer.luciferase.sparse.gpu.AbstractOpenCLRenderer}
 * and share ~85% of the rendering logic.
 *
 * @author hal.hildebrand
 */
package com.hellblazer.luciferase.sparse.gpu;
