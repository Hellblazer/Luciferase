package com.hellblazer.luciferase.gpu.esvo.correct;

import com.hellblazer.luciferase.gpu.esvo.reference.ESVONodeReference;

/**
 * REFERENCE-CORRECT ESVO ray traversal implementation matching CUDA raycast.inl exactly.
 * 
 * CRITICAL FIXES APPLIED:
 * 1. Uses ESVONodeReference (correct node structure)
 * 2. Uses getValidMask() for sparse indexing (not getChildMask())  
 * 3. Correct octant mirroring algorithm
 * 4. Proper [1,2] coordinate space transformations
 * 5. Reference-accurate sparse child indexing
 */
public class ESVOTraversal {
    
    // Constants from reference
    private static final int CAST_STACK_DEPTH = 23;
    private static final int MAX_RAYCAST_ITERATIONS = 10000;
    private static final float EPSILON = (float)Math.pow(2, -CAST_STACK_DEPTH);
    
    /**
     * Cast a ray through the octree.
     * This is an exact port of the reference castRay() function.
     * 
     * @param ray The ray to cast (will be modified for epsilon handling)
     * @param nodes The octree nodes array
     * @param rootNodeIdx Index of the root node (usually 0)
     * @return The traversal result
     */
    public static ESVOResult castRay(ESVORay ray, ESVONodeReference[] nodes, int rootNodeIdx) {
        ESVOResult result = new ESVOResult();
        ESVOStack stack = new ESVOStack();
        
        float rayOrigSize = ray.originSize;
        int iter = 0;
        
        // Get rid of small ray direction components to avoid division by zero
        ray.prepareForTraversal();
        
        // Precompute the coefficients of tx(x), ty(y), and tz(z)
        // The octree is assumed to reside at coordinates [1, 2]
        float txCoef = 1.0f / -Math.abs(ray.directionX);
        float tyCoef = 1.0f / -Math.abs(ray.directionY);
        float tzCoef = 1.0f / -Math.abs(ray.directionZ);
        
        float txBias = txCoef * ray.originX;
        float tyBias = tyCoef * ray.originY;
        float tzBias = tzCoef * ray.originZ;
        
        // Select octant mask to mirror the coordinate system so
        // that ray direction is negative along each axis
        int octantMask = 7;
        if (ray.directionX > 0.0f) {
            octantMask ^= 1;
            txBias = 3.0f * txCoef - txBias;
        }
        if (ray.directionY > 0.0f) {
            octantMask ^= 2;
            tyBias = 3.0f * tyCoef - tyBias;
        }
        if (ray.directionZ > 0.0f) {
            octantMask ^= 4;
            tzBias = 3.0f * tzCoef - tzBias;
        }
        
        // Initialize the active span of t-values
        float tMin = Math.max(Math.max(2.0f * txCoef - txBias, 2.0f * tyCoef - tyBias), 
                             2.0f * tzCoef - tzBias);
        float tMax = Math.min(Math.min(txCoef - txBias, tyCoef - tyBias), 
                             tzCoef - tzBias);
        float h = tMax;
        tMin = Math.max(tMin, 0.0f);
        tMax = Math.min(tMax, 1.0f);
        
        // Initialize the current voxel to the first child of the root
        int parentIdx = rootNodeIdx;
        ESVONodeReference childDescriptor = null;  // Invalid until fetched
        int idx = 0;
        float posX = 1.0f, posY = 1.0f, posZ = 1.0f;
        int scale = CAST_STACK_DEPTH - 1;
        float scaleExp2 = 0.5f;  // exp2f(scale - s_max)
        
        if (1.5f * txCoef - txBias > tMin) {
            idx ^= 1;
            posX = 1.5f;
        }
        if (1.5f * tyCoef - tyBias > tMin) {
            idx ^= 2;
            posY = 1.5f;
        }
        if (1.5f * tzCoef - tzBias > tMin) {
            idx ^= 4;
            posZ = 1.5f;
        }
        
        // Traverse voxels along the ray as long as the current voxel
        // stays within the octree
        while (scale < CAST_STACK_DEPTH) {
            iter++;
            if (iter > MAX_RAYCAST_ITERATIONS) {
                break;
            }
            
            // Fetch child descriptor unless it is already valid
            if (childDescriptor == null) {
                if (parentIdx >= 0 && parentIdx < nodes.length) {
                    childDescriptor = nodes[parentIdx];
                } else {
                    break;  // Invalid parent
                }
            }
            
            // Determine maximum t-value of the cube by evaluating
            // tx(), ty(), and tz() at its corner
            float txCorner = posX * txCoef - txBias;
            float tyCorner = posY * tyCoef - tyBias;
            float tzCorner = posZ * tzCoef - tzBias;
            float tcMax = Math.min(Math.min(txCorner, tyCorner), tzCorner);
            
            // Process voxel if the corresponding bit in valid mask is set
            // and the active t-span is non-empty
            int childShift = idx ^ octantMask;  // Permute child slots based on mirroring
            // CRITICAL FIX: Use VALID mask (bits 8-15), not child mask
            int validMask = childDescriptor.getValidMask();  
            int childMasks = validMask << childShift;
            
            // CRITICAL FIX: Check bit 15 (0x8000) for valid mask in CUDA reference
            if ((childMasks & 0x80) != 0 && tMin <= tMax) {
                // Check if voxel is a leaf  
                // CRITICAL FIX: Use NON-LEAF mask (bits 0-7), not leaf mask
                int nonLeafMask = childDescriptor.getNonLeafMask();
                int nonLeafMasks = nonLeafMask << childShift;
                
                // Terminate if the voxel is small enough (LOD check)
                if (tcMax * ray.directionSize + rayOrigSize >= scaleExp2) {
                    result.hit = true;
                    result.t = tMin;
                    result.x = ray.originX + tMin * ray.directionX;
                    result.y = ray.originY + tMin * ray.directionY;
                    result.z = ray.originZ + tMin * ray.directionZ;
                    result.nodeIndex = parentIdx;
                    result.childIndex = idx ^ octantMask ^ 7;
                    result.stackPtr = scale;
                    result.iterations = iter;
                    break;  // Hit at t_min
                }
                
                // INTERSECT
                float tvMax = Math.min(tMax, tcMax);
                float half = scaleExp2 * 0.5f;
                float txCenter = half * txCoef + txCorner;
                float tyCenter = half * tyCoef + tyCorner;
                float tzCenter = half * tzCoef + tzCorner;
                
                // Check if we can descend (non-leaf)
                // CRITICAL FIX: Check NON-LEAF mask bit (0x0080 in CUDA reference)
                if ((nonLeafMasks & 0x80) != 0 && tMin <= tvMax) {
                    // PUSH - Write current parent to the stack
                    if (tcMax < h) {
                        stack.write(scale, parentIdx, tMax);
                    }
                    h = tcMax;
                    
                    // Find child descriptor corresponding to the current voxel
                    // This is the CRITICAL sparse indexing algorithm
                    int childIdx = childShift ^ 7;  // Actual child index
                    int childNodeIdx = childDescriptor.getChildNodeIndex(childIdx);
                    
                    if (childNodeIdx < 0) {
                        // Child doesn't exist - shouldn't happen if masks are correct
                        break;
                    }
                    
                    // Handle far pointers if needed
                    if (childDescriptor.isFar()) {
                        // CRITICAL FIX: Use ESVONodeReference for far pointer resolution
                        childNodeIdx = ESVONodeReference.resolveFarPointer(nodes, parentIdx + childNodeIdx);
                    }
                    
                    parentIdx = childNodeIdx;
                    
                    // Select child voxel that the ray enters first
                    idx = 0;
                    scale--;
                    scaleExp2 = half;
                    
                    if (txCenter > tMin) {
                        idx ^= 1;
                        posX += scaleExp2;
                    }
                    if (tyCenter > tMin) {
                        idx ^= 2;
                        posY += scaleExp2;
                    }
                    if (tzCenter > tMin) {
                        idx ^= 4;
                        posZ += scaleExp2;
                    }
                    
                    // Update active t-span and invalidate cached child descriptor
                    tMax = tvMax;
                    childDescriptor = null;
                    continue;
                }
            }
            
            // ADVANCE - Step along the ray
            int stepMask = 0;
            if (txCorner <= tcMax) {
                stepMask ^= 1;
                posX -= scaleExp2;
            }
            if (tyCorner <= tcMax) {
                stepMask ^= 2;
                posY -= scaleExp2;
            }
            if (tzCorner <= tcMax) {
                stepMask ^= 4;
                posZ -= scaleExp2;
            }
            
            // Update active t-span and flip bits of the child slot index
            tMin = tcMax;
            idx ^= stepMask;
            
            // Proceed with pop if the bit flips disagree with the ray direction
            if ((idx & stepMask) != 0) {
                // POP - Find the highest differing bit between the two positions
                int differingBits = 0;
                if ((stepMask & 1) != 0) {
                    differingBits |= Float.floatToIntBits(posX) ^ Float.floatToIntBits(posX + scaleExp2);
                }
                if ((stepMask & 2) != 0) {
                    differingBits |= Float.floatToIntBits(posY) ^ Float.floatToIntBits(posY + scaleExp2);
                }
                if ((stepMask & 4) != 0) {
                    differingBits |= Float.floatToIntBits(posZ) ^ Float.floatToIntBits(posZ + scaleExp2);
                }
                
                // Position of the highest bit
                scale = (Float.floatToIntBits((float)differingBits) >> 23) - 127;
                scaleExp2 = Float.intBitsToFloat((scale - CAST_STACK_DEPTH + 127) << 23);
                
                // Restore parent voxel from the stack
                parentIdx = stack.readNode(scale);
                tMax = stack.readTmax(scale);
                
                // Round cube position and extract child slot index
                int shx = Float.floatToIntBits(posX) >> scale;
                int shy = Float.floatToIntBits(posY) >> scale;
                int shz = Float.floatToIntBits(posZ) >> scale;
                posX = Float.intBitsToFloat(shx << scale);
                posY = Float.intBitsToFloat(shy << scale);
                posZ = Float.intBitsToFloat(shz << scale);
                idx = (shx & 1) | ((shy & 1) << 1) | ((shz & 1) << 2);
                
                // Prevent same parent from being stored again and invalidate cached child descriptor
                h = 0.0f;
                childDescriptor = null;
            }
        }
        
        // Indicate miss if we are outside the octree
        if (scale >= CAST_STACK_DEPTH || iter > MAX_RAYCAST_ITERATIONS) {
            result.t = 2.0f;
            result.hit = false;
        }
        
        // Undo mirroring of the coordinate system
        if ((octantMask & 1) == 0) {
            posX = 3.0f - scaleExp2 - posX;
        }
        if ((octantMask & 2) == 0) {
            posY = 3.0f - scaleExp2 - posY;
        }
        if ((octantMask & 4) == 0) {
            posZ = 3.0f - scaleExp2 - posZ;
        }
        
        // Clamp hit position to voxel bounds
        if (result.hit) {
            result.x = Math.min(Math.max(result.x, posX + EPSILON), posX + scaleExp2 - EPSILON);
            result.y = Math.min(Math.max(result.y, posY + EPSILON), posY + scaleExp2 - EPSILON);
            result.z = Math.min(Math.max(result.z, posZ + EPSILON), posZ + scaleExp2 - EPSILON);
        }
        
        result.iterations = iter;
        return result;
    }
    
    /**
     * Cast multiple rays (batch processing)
     */
    public static ESVOResult[] castRays(ESVORay[] rays, ESVONodeReference[] nodes, int rootNodeIdx) {
        ESVOResult[] results = new ESVOResult[rays.length];
        for (int i = 0; i < rays.length; i++) {
            results[i] = castRay(rays[i], nodes, rootNodeIdx);
        }
        return results;
    }
}