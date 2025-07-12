package com.hellblazer.luciferase.lucien;

/**
 * Utility class to control verbose test output based on environment variables.
 * 
 * Set VERBOSE_TESTS=true to enable detailed output from analysis and validation tests.
 * By default, verbose output is suppressed to keep test runs clean.
 */
public class TestOutputSuppressor {
    
    private static final boolean VERBOSE = Boolean.parseBoolean(
        System.getenv().getOrDefault("VERBOSE_TESTS", "false")
    );
    
    /**
     * Check if verbose test output is enabled
     */
    public static boolean isVerbose() {
        return VERBOSE;
    }
    
    /**
     * Print a line only if verbose mode is enabled
     */
    public static void println(String message) {
        if (VERBOSE) {
            System.out.println(message);
        }
    }
    
    /**
     * Print a formatted string only if verbose mode is enabled
     */
    public static void printf(String format, Object... args) {
        if (VERBOSE) {
            System.out.printf(format, args);
        }
    }
    
    /**
     * Print without newline only if verbose mode is enabled
     */
    public static void print(String message) {
        if (VERBOSE) {
            System.out.print(message);
        }
    }
}