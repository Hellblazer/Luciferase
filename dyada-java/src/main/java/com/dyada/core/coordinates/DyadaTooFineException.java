package com.dyada.core.coordinates;

/**
 * Exception thrown when a refinement level exceeds the maximum supported precision.
 * DyAda supports up to 62 levels per dimension due to IEEE 754 double precision constraints.
 */
public final class DyadaTooFineException extends DyadaException {
    
    public DyadaTooFineException(String message) {
        super(message);
    }
    
    public DyadaTooFineException(String message, Throwable cause) {
        super(message, cause);
    }
}