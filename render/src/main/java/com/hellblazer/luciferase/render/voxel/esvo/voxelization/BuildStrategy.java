package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

/**
 * Strategy for building octree structures.
 */
public enum BuildStrategy {
    TOP_DOWN,    // Build from root to leaves
    BOTTOM_UP    // Build from leaves to root
}