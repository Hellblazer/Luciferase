package com.dyada.core.coordinates;

/**
 * Exception thrown when level and index arrays have inconsistent dimensions or invalid values.
 */
public final class InvalidLevelIndexException extends DyadaException {
    
    public InvalidLevelIndexException(String message) {
        super(message);
    }
    
    public InvalidLevelIndexException(String message, Throwable cause) {
        super(message, cause);
    }
}