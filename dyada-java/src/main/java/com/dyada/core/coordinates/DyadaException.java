package com.dyada.core.coordinates;

/**
 * Base sealed class for all DyAda-specific exceptions.
 * Uses Java's sealed classes feature to provide a closed hierarchy of exceptions.
 */
public sealed class DyadaException extends Exception 
    permits DyadaTooFineException, InvalidLevelIndexException {
    
    public DyadaException(String message) {
        super(message);
    }
    
    public DyadaException(String message, Throwable cause) {
        super(message, cause);
    }
}