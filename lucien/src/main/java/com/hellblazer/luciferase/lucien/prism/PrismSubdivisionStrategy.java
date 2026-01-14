/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>>.
 */
package com.hellblazer.luciferase.lucien.prism;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.SubdivisionStrategy;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.HashSet;
import java.util.Set;

/**
 * Prism-specific subdivision strategy with anisotropic refinement criteria.
 * 
 * This strategy exploits the Prism's unique anisotropic structure where horizontal
 * (x,y) subdivision is fine-grained (triangular 4-way) while vertical (z) subdivision
 * is coarse-grained (linear 2-way). The refinement criteria adapt to:
 * 
 * - Entity aspect ratios (horizontal vs vertical extent)
 * - Spatial distribution patterns (layered vs columnar)
 * - Workload characteristics (terrain, urban, atmospheric)
 * - Anisotropic thresholds for horizontal and vertical refinement
 * 
 * Key features:
 * - Directional subdivision: Can refine only horizontal or only vertical
 * - Aspect-aware refinement: Prefers horizontal refinement for flat entities
 * - Layer-optimized: Efficient for stratified/layered data
 * - Adaptive thresholds: Different criteria for horizontal vs vertical subdivision
 * 
 * @param <ID>      The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class PrismSubdivisionStrategy<ID extends EntityID, Content>
extends SubdivisionStrategy<PrismKey, ID, Content> {

    private static final int PRISM_CHILDREN = 8;  // 4 triangle × 2 line = 8 total children
    
    // Anisotropic refinement parameters
    private double horizontalRefinementThreshold = 0.6;  // Threshold for horizontal (triangle) refinement
    private double verticalRefinementThreshold = 0.8;    // Threshold for vertical (line) refinement
    private double aspectRatioWeight = 0.3;              // How much entity aspect ratio influences decision
    private boolean enableDirectionalRefinement = true;  // Allow refinement in only one dimension
    
    /**
     * Create a balanced strategy for mixed workloads.
     */
    public static <ID extends EntityID, Content> PrismSubdivisionStrategy<ID, Content> balanced() {
        var strategy = new PrismSubdivisionStrategy<ID, Content>();
        strategy.minEntitiesForSplit = 4;
        strategy.loadFactor = 0.75;
        strategy.spanningThreshold = 0.5;
        strategy.horizontalRefinementThreshold = 0.6;
        strategy.verticalRefinementThreshold = 0.8;
        return strategy;
    }
    
    /**
     * Create a strategy optimized for terrain/layered data.
     * Favors horizontal refinement over vertical.
     */
    public static <ID extends EntityID, Content> PrismSubdivisionStrategy<ID, Content> forTerrainData() {
        var strategy = new PrismSubdivisionStrategy<ID, Content>();
        strategy.minEntitiesForSplit = 6;
        strategy.loadFactor = 0.8;
        strategy.spanningThreshold = 0.4;
        strategy.horizontalRefinementThreshold = 0.5;   // Lower threshold = easier to trigger
        strategy.verticalRefinementThreshold = 0.9;     // Higher threshold = harder to trigger
        strategy.aspectRatioWeight = 0.5;               // Strong aspect ratio influence
        return strategy;
    }
    
    /**
     * Create a strategy optimized for atmospheric/volume data.
     * Balances horizontal and vertical refinement.
     */
    public static <ID extends EntityID, Content> PrismSubdivisionStrategy<ID, Content> forVolumetricData() {
        var strategy = new PrismSubdivisionStrategy<ID, Content>();
        strategy.minEntitiesForSplit = 4;
        strategy.loadFactor = 0.7;
        strategy.spanningThreshold = 0.6;
        strategy.horizontalRefinementThreshold = 0.6;
        strategy.verticalRefinementThreshold = 0.6;    // Equal thresholds
        strategy.aspectRatioWeight = 0.2;              // Lower aspect influence
        return strategy;
    }
    
    /**
     * Create a strategy optimized for columnar/vertical structures.
     * Favors vertical refinement over horizontal.
     */
    public static <ID extends EntityID, Content> PrismSubdivisionStrategy<ID, Content> forColumnarData() {
        var strategy = new PrismSubdivisionStrategy<ID, Content>();
        strategy.minEntitiesForSplit = 3;
        strategy.loadFactor = 0.65;
        strategy.spanningThreshold = 0.5;
        strategy.horizontalRefinementThreshold = 0.8;   // Harder to trigger horizontal
        strategy.verticalRefinementThreshold = 0.5;     // Easier to trigger vertical
        strategy.aspectRatioWeight = 0.4;
        return strategy;
    }
    
    @Override
    public Set<PrismKey> calculateTargetNodes(PrismKey parentIndex, byte parentLevel, EntityBounds entityBounds,
                                              AbstractSpatialIndex<PrismKey, ID, Content> spatialIndex) {
        var targetNodes = new HashSet<PrismKey>();
        
        if (entityBounds == null) {
            return targetNodes;
        }
        
        var parentTriangle = parentIndex.getTriangle();
        var parentLine = parentIndex.getLine();
        
        // Determine which dimensions need subdivision based on entity bounds
        var needsHorizontalSubdiv = shouldSubdivideHorizontally(entityBounds, parentTriangle);
        var needsVerticalSubdiv = shouldSubdivideVertically(entityBounds, parentLine);
        
        // Generate appropriate child prisms
        if (needsHorizontalSubdiv && needsVerticalSubdiv) {
            // Full subdivision: all 8 children
            for (var i = 0; i < PRISM_CHILDREN; i++) {
                var child = parentIndex.child(i);
                if (entityIntersectsPrism(entityBounds, child)) {
                    targetNodes.add(child);
                }
            }
        } else if (needsHorizontalSubdiv) {
            // Only horizontal subdivision: 4 children (same line, different triangles)
            for (var triangleChild = 0; triangleChild < Triangle.CHILDREN; triangleChild++) {
                var childTriangle = parentTriangle.child(triangleChild);
                var child = new PrismKey(childTriangle, parentLine);
                if (entityIntersectsPrism(entityBounds, child)) {
                    targetNodes.add(child);
                }
            }
        } else if (needsVerticalSubdiv) {
            // Only vertical subdivision: 2 children (same triangle, different lines)
            for (var lineChild = 0; lineChild < Line.CHILDREN; lineChild++) {
                var childLine = parentLine.child(lineChild);
                var child = new PrismKey(parentTriangle, childLine);
                if (entityIntersectsPrism(entityBounds, child)) {
                    targetNodes.add(child);
                }
            }
        }
        
        return targetNodes;
    }
    
    @Override
    public SubdivisionResult determineStrategy(SubdivisionContext<PrismKey, ID> context) {
        // Check if we're at maximum depth
        if (context.isAtMaxDepth()) {
            return SubdivisionResult.insertInParent("At maximum depth");
        }
        
        // During bulk operations, defer subdivision for better performance
        if (context.isBulkOperation && !context.isCriticallyOverloaded()) {
            return SubdivisionResult.deferSubdivision("Bulk operation in progress");
        }
        
        // If critically overloaded, force immediate subdivision
        if (context.isCriticallyOverloaded()) {
            return SubdivisionResult.forceSubdivision("Node critically overloaded");
        }
        
        // Check if we have enough entities to warrant subdivision
        if (context.currentNodeSize < minEntitiesForSplit) {
            return SubdivisionResult.insertInParent("Too few entities for efficient subdivision");
        }
        
        // Estimate subdivision benefit with anisotropic considerations
        var benefit = estimateAnisotropicSubdivisionBenefit(context);
        
        // Low benefit - keep in parent
        if (benefit < 0.3) {
            return SubdivisionResult.insertInParent("Low subdivision benefit score: " + benefit);
        }
        
        // Analyze entity aspect ratio and spatial extent
        var aspectAnalysis = analyzeEntityAspectRatio(context);
        
        // Determine refinement direction based on aspect ratio and thresholds
        var refinementDirection = determineRefinementDirection(context, aspectAnalysis);
        
        // Check if entity should span multiple children
        if (context.newEntityBounds != null) {
            var targetChildren = calculateTargetNodes(context.nodeIndex, context.nodeLevel, 
                                                     context.newEntityBounds, null);
            
            if (targetChildren.size() > 1) {
                return SubdivisionResult.splitToChildren(targetChildren,
                    String.format("Entity spans %d children (direction: %s)", 
                                targetChildren.size(), refinementDirection));
            }
            
            // Entity fits in single child - create only that child
            if (targetChildren.size() == 1) {
                return SubdivisionResult.createSingleChild(targetChildren.iterator().next(),
                    String.format("Entity fits in single child (direction: %s)", refinementDirection));
            }
        }
        
        // High benefit and appropriate refinement direction
        if (benefit > 0.7) {
            return SubdivisionResult.forceSubdivision(
                String.format("High benefit (%.2f) for %s refinement", benefit, refinementDirection));
        }
        
        // Medium benefit - standard subdivision
        return SubdivisionResult.forceSubdivision(
            String.format("Standard subdivision (benefit: %.2f, direction: %s)", benefit, refinementDirection));
    }
    
    @Override
    protected double estimateEntitySizeFactor(SubdivisionContext<PrismKey, ID> context) {
        if (context.newEntityBounds == null) {
            return 0.5; // Default for point entities
        }
        
        // Get entity extents
        var entitySizeX = context.newEntityBounds.getMaxX() - context.newEntityBounds.getMinX();
        var entitySizeY = context.newEntityBounds.getMaxY() - context.newEntityBounds.getMinY();
        var entitySizeZ = context.newEntityBounds.getMaxZ() - context.newEntityBounds.getMinZ();
        
        // Get prism extents (normalized to [0,1])
        var prismBounds = context.nodeIndex.getWorldBounds();
        var prismSizeX = prismBounds[3] - prismBounds[0];
        var prismSizeY = prismBounds[4] - prismBounds[1];
        var prismSizeZ = prismBounds[5] - prismBounds[2];
        
        // Calculate relative size factors for each dimension
        var horizontalFactor = Math.max(entitySizeX / prismSizeX, entitySizeY / prismSizeY);
        var verticalFactor = entitySizeZ / prismSizeZ;
        
        // Combine using anisotropic weighting
        // Horizontal contributes more due to finer granularity (4-way vs 2-way)
        var combinedFactor = (horizontalFactor * 0.67) + (verticalFactor * 0.33);
        
        return Math.min(combinedFactor, 1.0);
    }
    
    /**
     * Estimate subdivision benefit with anisotropic considerations.
     */
    private double estimateAnisotropicSubdivisionBenefit(SubdivisionContext<PrismKey, ID> context) {
        if (context.isAtMaxDepth()) {
            return 0.0;
        }
        
        // Base benefit calculation
        var baseBenefit = estimateSubdivisionBenefit(context);
        
        // Adjust based on aspect ratio and refinement direction
        if (context.newEntityBounds != null) {
            var aspectAnalysis = analyzeEntityAspectRatio(context);
            
            // Bonus for well-aligned aspect ratios
            var alignmentBonus = 0.0;
            if (aspectAnalysis.isHorizontallyOriented && aspectAnalysis.horizontalExtent > 0.5) {
                alignmentBonus = 0.1; // Horizontal entity benefits from horizontal subdivision
            } else if (aspectAnalysis.isVerticallyOriented && aspectAnalysis.verticalExtent > 0.5) {
                alignmentBonus = 0.05; // Vertical entity benefits from vertical subdivision
            }
            
            return Math.min(baseBenefit + alignmentBonus, 1.0);
        }
        
        return baseBenefit;
    }
    
    /**
     * Analyze entity aspect ratio to guide anisotropic refinement.
     */
    private AspectAnalysis analyzeEntityAspectRatio(SubdivisionContext<PrismKey, ID> context) {
        if (context.newEntityBounds == null) {
            return new AspectAnalysis(false, false, 0.5, 0.5);
        }
        
        var entitySizeX = context.newEntityBounds.getMaxX() - context.newEntityBounds.getMinX();
        var entitySizeY = context.newEntityBounds.getMaxY() - context.newEntityBounds.getMinY();
        var entitySizeZ = context.newEntityBounds.getMaxZ() - context.newEntityBounds.getMinZ();
        
        var maxHorizontal = Math.max(entitySizeX, entitySizeY);
        var maxDimension = Math.max(maxHorizontal, entitySizeZ);
        
        // Calculate aspect ratios
        var horizontalAspect = maxHorizontal / maxDimension;
        var verticalAspect = entitySizeZ / maxDimension;
        
        // Determine orientation
        var isHorizontallyOriented = horizontalAspect > 0.7;  // Entity is flat/wide
        var isVerticallyOriented = verticalAspect > 0.7;      // Entity is tall/columnar
        
        return new AspectAnalysis(isHorizontallyOriented, isVerticallyOriented, 
                                 horizontalAspect, verticalAspect);
    }
    
    /**
     * Determine which refinement direction(s) to use based on entity characteristics.
     */
    private RefinementDirection determineRefinementDirection(SubdivisionContext<PrismKey, ID> context,
                                                            AspectAnalysis aspectAnalysis) {
        if (!enableDirectionalRefinement) {
            return RefinementDirection.BOTH;
        }
        
        // Calculate refinement scores for each direction
        var horizontalScore = calculateHorizontalRefinementScore(context, aspectAnalysis);
        var verticalScore = calculateVerticalRefinementScore(context, aspectAnalysis);
        
        // Determine direction based on scores and thresholds
        var shouldRefineHorizontal = horizontalScore >= horizontalRefinementThreshold;
        var shouldRefineVertical = verticalScore >= verticalRefinementThreshold;
        
        if (shouldRefineHorizontal && shouldRefineVertical) {
            return RefinementDirection.BOTH;
        } else if (shouldRefineHorizontal) {
            return RefinementDirection.HORIZONTAL;
        } else if (shouldRefineVertical) {
            return RefinementDirection.VERTICAL;
        } else {
            // Neither exceeds threshold - use the higher score
            return horizontalScore > verticalScore ? 
                   RefinementDirection.HORIZONTAL : RefinementDirection.VERTICAL;
        }
    }
    
    /**
     * Calculate score for horizontal (triangular) refinement.
     */
    private double calculateHorizontalRefinementScore(SubdivisionContext<PrismKey, ID> context,
                                                      AspectAnalysis aspectAnalysis) {
        var baseScore = (double) context.currentNodeSize / context.maxEntitiesPerNode;
        
        // Boost score for horizontally-oriented entities
        if (aspectAnalysis.isHorizontallyOriented) {
            baseScore *= (1.0 + aspectRatioWeight * aspectAnalysis.horizontalExtent);
        }
        
        return Math.min(baseScore, 1.0);
    }
    
    /**
     * Calculate score for vertical (linear) refinement.
     */
    private double calculateVerticalRefinementScore(SubdivisionContext<PrismKey, ID> context,
                                                    AspectAnalysis aspectAnalysis) {
        var baseScore = (double) context.currentNodeSize / context.maxEntitiesPerNode;
        
        // Boost score for vertically-oriented entities
        if (aspectAnalysis.isVerticallyOriented) {
            baseScore *= (1.0 + aspectRatioWeight * aspectAnalysis.verticalExtent);
        }
        
        // Penalty for vertical refinement (coarser granularity)
        baseScore *= 0.9;
        
        return Math.min(baseScore, 1.0);
    }
    
    /**
     * Check if horizontal subdivision is needed for the entity.
     */
    private boolean shouldSubdivideHorizontally(EntityBounds bounds, Triangle triangle) {
        var triangleBounds = triangle.getWorldBounds();
        
        // Calculate horizontal overlap
        var overlapX = Math.min(bounds.getMaxX(), triangleBounds[2]) - 
                      Math.max(bounds.getMinX(), triangleBounds[0]);
        var overlapY = Math.min(bounds.getMaxY(), triangleBounds[3]) - 
                      Math.max(bounds.getMinY(), triangleBounds[1]);
        
        // Subdivide if entity spans significant portion of triangle
        var triangleWidth = triangleBounds[2] - triangleBounds[0];
        var triangleHeight = triangleBounds[3] - triangleBounds[1];
        
        return (overlapX / triangleWidth > 0.5) || (overlapY / triangleHeight > 0.5);
    }
    
    /**
     * Check if vertical subdivision is needed for the entity.
     */
    private boolean shouldSubdivideVertically(EntityBounds bounds, Line line) {
        var lineBounds = line.getWorldBounds();
        
        // Calculate vertical overlap
        var overlapZ = Math.min(bounds.getMaxZ(), lineBounds[1]) - 
                      Math.max(bounds.getMinZ(), lineBounds[0]);
        
        // Subdivide if entity spans significant portion of line segment
        var lineHeight = lineBounds[1] - lineBounds[0];
        
        return overlapZ / lineHeight > 0.6;
    }
    
    /**
     * Check if entity bounds intersect with a prism.
     */
    private boolean entityIntersectsPrism(EntityBounds bounds, PrismKey prismKey) {
        var prismBounds = prismKey.getWorldBounds();
        
        return !(bounds.getMaxX() < prismBounds[0] || bounds.getMinX() > prismBounds[3] ||
                 bounds.getMaxY() < prismBounds[1] || bounds.getMinY() > prismBounds[4] ||
                 bounds.getMaxZ() < prismBounds[2] || bounds.getMinZ() > prismBounds[5]);
    }
    
    // Configuration methods
    
    public PrismSubdivisionStrategy<ID, Content> withHorizontalRefinementThreshold(double threshold) {
        this.horizontalRefinementThreshold = threshold;
        return this;
    }
    
    public PrismSubdivisionStrategy<ID, Content> withVerticalRefinementThreshold(double threshold) {
        this.verticalRefinementThreshold = threshold;
        return this;
    }
    
    public PrismSubdivisionStrategy<ID, Content> withAspectRatioWeight(double weight) {
        this.aspectRatioWeight = weight;
        return this;
    }
    
    public PrismSubdivisionStrategy<ID, Content> withDirectionalRefinement(boolean enabled) {
        this.enableDirectionalRefinement = enabled;
        return this;
    }
    
    // Getters
    
    public double getHorizontalRefinementThreshold() {
        return horizontalRefinementThreshold;
    }
    
    public double getVerticalRefinementThreshold() {
        return verticalRefinementThreshold;
    }
    
    public double getAspectRatioWeight() {
        return aspectRatioWeight;
    }
    
    public boolean isDirectionalRefinementEnabled() {
        return enableDirectionalRefinement;
    }
    
    /**
     * Aspect ratio analysis result.
     */
    private static class AspectAnalysis {
        final boolean isHorizontallyOriented;
        final boolean isVerticallyOriented;
        final double horizontalExtent;  // 0.0 to 1.0
        final double verticalExtent;    // 0.0 to 1.0
        
        AspectAnalysis(boolean isHorizontallyOriented, boolean isVerticallyOriented,
                      double horizontalExtent, double verticalExtent) {
            this.isHorizontallyOriented = isHorizontallyOriented;
            this.isVerticallyOriented = isVerticallyOriented;
            this.horizontalExtent = horizontalExtent;
            this.verticalExtent = verticalExtent;
        }
    }
    
    /**
     * Refinement direction enumeration.
     */
    private enum RefinementDirection {
        HORIZONTAL,  // Only triangular subdivision
        VERTICAL,    // Only linear subdivision
        BOTH         // Full 8-way subdivision
    }
}
