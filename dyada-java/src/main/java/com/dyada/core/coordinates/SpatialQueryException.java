package com.dyada.core.coordinates;

/**
 * Exception thrown during spatial query operations when coordinates are invalid or 
 * queries cannot be completed due to spatial constraints.
 */
public final class SpatialQueryException extends RuntimeException {
    
    public SpatialQueryException(String message) {
        super(message);
    }
    
    public SpatialQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}