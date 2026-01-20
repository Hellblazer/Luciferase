package com.hellblazer.luciferase.esvo.dag.io;

/**
 * Exception thrown when DAG file format validation fails.
 */
public class DAGFormatException extends RuntimeException {
    public DAGFormatException(String message) {
        super(message);
    }

    public DAGFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
