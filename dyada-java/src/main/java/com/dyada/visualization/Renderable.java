package com.dyada.visualization;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.transformations.CoordinateTransformation;

import java.util.List;

/**
 * Interface for objects that can be rendered in a visualization system.
 * Provides methods for obtaining geometric data and applying transformations.
 */
public interface Renderable {
    
    /**
     * Gets the vertices that define this renderable object.
     * 
     * @return list of vertex coordinates
     */
    List<Coordinate> getVertices();
    
    /**
     * Gets the indices that define how vertices are connected.
     * For triangular meshes, every 3 indices define a triangle.
     * 
     * @return list of vertex indices
     */
    List<Integer> getIndices();
    
    /**
     * Gets the bounding box of this renderable object.
     * 
     * @return bounding box as [min, max] coordinates
     */
    BoundingBox getBoundingBox();
    
    /**
     * Applies a coordinate transformation to this renderable.
     * 
     * @param transformation the transformation to apply
     * @return a new transformed renderable
     */
    Renderable transform(CoordinateTransformation transformation);
    
    /**
     * Gets the rendering properties for this object.
     * 
     * @return rendering properties
     */
    RenderingProperties getRenderingProperties();
    
    /**
     * Sets the rendering properties for this object.
     * 
     * @param properties the new rendering properties
     */
    void setRenderingProperties(RenderingProperties properties);
    
    /**
     * Gets the level of detail for this renderable.
     * Higher values indicate more detailed representation.
     * 
     * @return level of detail (0-based)
     */
    int getLevelOfDetail();
    
    /**
     * Creates a simplified version of this renderable for distant viewing.
     * 
     * @param targetComplexity the target complexity reduction factor (0.0 to 1.0)
     * @return simplified renderable
     */
    Renderable simplify(double targetComplexity);
    
    /**
     * Checks if this renderable is visible from a given viewpoint.
     * 
     * @param viewpoint the viewing position
     * @param viewDirection the viewing direction
     * @param fieldOfView the field of view angle in radians
     * @return true if visible
     */
    boolean isVisible(Coordinate viewpoint, Coordinate viewDirection, double fieldOfView);
    
    /**
     * Gets the estimated rendering cost for this object.
     * Used for level-of-detail and culling decisions.
     * 
     * @return rendering cost (arbitrary units)
     */
    double getRenderingCost();
}