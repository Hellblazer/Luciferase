package com.dyada.exceptions;

/**
 * Exception thrown when a coordinate transformation operation fails.
 * This can occur due to singular matrices, dimension mismatches, or invalid parameters.
 */
public class TransformationException extends Exception {
    
    /**
     * Creates a new transformation exception with the specified message.
     * 
     * @param message the detail message
     */
    public TransformationException(String message) {
        super(message);
    }
    
    /**
     * Creates a new transformation exception with the specified message and cause.
     * 
     * @param message the detail message
     * @param cause the underlying cause
     */
    public TransformationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Creates a new transformation exception with the specified cause.
     * 
     * @param cause the underlying cause
     */
    public TransformationException(Throwable cause) {
        super(cause);
    }
}