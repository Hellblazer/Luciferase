/**
 * Generic optimization framework for sparse voxel data structures.
 *
 * <p>This package provides the shared optimization infrastructure used by both
 * ESVO (octree) and ESVT (tetrahedral) implementations:
 *
 * <ul>
 *   <li>{@link com.hellblazer.luciferase.sparse.optimization.AbstractOptimizationPipeline} -
 *       Base class for optimization pipelines</li>
 *   <li>{@link com.hellblazer.luciferase.sparse.optimization.Optimizer} -
 *       Interface for individual optimizers</li>
 *   <li>{@link com.hellblazer.luciferase.sparse.optimization.OptimizationResult} -
 *       Result of running the optimization pipeline</li>
 *   <li>{@link com.hellblazer.luciferase.sparse.optimization.OptimizationReport} -
 *       Detailed report of optimization steps</li>
 *   <li>{@link com.hellblazer.luciferase.sparse.optimization.OptimizationStep} -
 *       Single optimization step result</li>
 *   <li>{@link com.hellblazer.luciferase.sparse.optimization.OptimizerStats} -
 *       Per-optimizer statistics tracking</li>
 * </ul>
 *
 * <p><b>Architecture:</b> Both ESVO and ESVT optimization pipelines extend
 * {@link com.hellblazer.luciferase.sparse.optimization.AbstractOptimizationPipeline}
 * and share ~95% of the pipeline logic. Type-specific optimizer execution is
 * handled in the subclasses.
 *
 * @author hal.hildebrand
 */
package com.hellblazer.luciferase.sparse.optimization;
