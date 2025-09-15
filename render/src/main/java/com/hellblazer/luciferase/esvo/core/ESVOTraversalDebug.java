package com.hellblazer.luciferase.esvo.core;

/**
 * DEBUG VERSION of ESVOTraversal with detailed tracing
 */
public class ESVOTraversalDebug {
    
    // Constants from reference
    private static final int CAST_STACK_DEPTH = 23;
    private static final int MAX_RAYCAST_ITERATIONS = 10000;
    private static final float EPSILON = (float)Math.pow(2, -CAST_STACK_DEPTH);
    
    /**
     * Cast a ray through the octree WITH DEBUG TRACING
     */
    public static ESVOResult castRay(ESVORay ray, ESVONode[] nodes, int rootNodeIdx) {
        System.out.println("=== STARTING RAY TRAVERSAL DEBUG ===");
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
        
        System.out.println("octantMask = " + octantMask);
        
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
        ESVONode childDescriptor = null;  // Invalid until fetched
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
        
        System.out.println("Initial idx = " + idx + " (binary: " + Integer.toBinaryString(idx) + ")");
        System.out.println("Initial pos = (" + posX + "," + posY + "," + posZ + ")");
        
        // Traverse voxels along the ray as long as the current voxel
        // stays within the octree
        while (scale < CAST_STACK_DEPTH) {
            iter++;
            if (iter > MAX_RAYCAST_ITERATIONS) {
                break;
            }
            
            System.out.println("\n--- ITERATION " + iter + " ---");
            System.out.println("idx = " + idx + ", parentIdx = " + parentIdx);
            
            // Fetch child descriptor unless it is already valid
            if (childDescriptor == null) {
                if (parentIdx >= 0 && parentIdx < nodes.length) {
                    childDescriptor = nodes[parentIdx];
                    
                    System.out.println("Fetched childDescriptor: " + childDescriptor);
                    
                    // If we've reached a leaf node, terminate with hit
                    if (childDescriptor.isLeaf()) {
                        System.out.println("*** TERMINATING: Hit leaf node ***");
                        System.out.println("Final idx = " + idx + ", childIndex will be = " + (idx ^ octantMask ^ 7));
                        
                        result.hit = true;
                        result.t = tMin;
                        result.x = ray.originX + tMin * ray.directionX;
                        result.y = ray.originY + tMin * ray.directionY;
                        result.z = ray.originZ + tMin * ray.directionZ;
                        result.nodeIndex = parentIdx;
                        result.childIndex = idx ^ octantMask ^ 7;
                        result.stackPtr = scale;
                        result.iterations = iter;
                        break;
                    }
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
            
            // Process voxel if the corresponding child exists
            // and the active t-span is non-empty
            int childShift = idx ^ octantMask;  // Permute child slots based on mirroring
            
            System.out.println("childShift = " + childShift + " (idx ^ octantMask = " + idx + " ^ " + octantMask + ")");
            
            // CRITICAL FIX: Use exact CUDA algorithm - shift entire descriptor 
            int rawDescriptor = childDescriptor.getRawChildDescriptor();
            int childMasks = rawDescriptor << childShift;
            boolean childExists = (childMasks & 0x8000) != 0;
            
            System.out.println("rawDescriptor = " + Integer.toHexString(rawDescriptor));
            System.out.println("childMasks = " + Integer.toHexString(childMasks) + " (rawDescriptor << " + childShift + ")");
            System.out.println("childExists = " + childExists);
            
            if (childExists && tMin <= tMax) {
                // Check if voxel is a leaf using CUDA algorithm
                boolean isNonLeaf = (childMasks & 0x0080) != 0;
                
                System.out.println("isNonLeaf = " + isNonLeaf);
                
                // Terminate if the voxel is small enough (LOD check)
                if (tcMax * ray.directionSize + rayOrigSize >= scaleExp2) {
                    System.out.println("*** TERMINATING: LOD check ***");
                    System.out.println("Final idx = " + idx + ", childIndex will be = " + (idx ^ octantMask ^ 7));
                    
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
                if (isNonLeaf && tMin <= tvMax) {
                    System.out.println("Descending to child...");
                    
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
                        // CRITICAL FIX: Use ESVONode for far pointer resolution
                        childNodeIdx = ESVONode.resolveFarPointer(nodes, parentIdx + childNodeIdx);
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
                    
                    System.out.println("New idx = " + idx + ", new parentIdx = " + parentIdx);
                    
                    // Update active t-span and invalidate cached child descriptor
                    tMax = tvMax;
                    childDescriptor = null;
                    continue;
                }
            }
            
            // If we get here, we're doing ADVANCE
            System.out.println("ADVANCE: idx = " + idx + " -> terminating");
            break;
        }
        
        System.out.println("=== END RAY TRAVERSAL DEBUG ===");
        return result;
    }
}