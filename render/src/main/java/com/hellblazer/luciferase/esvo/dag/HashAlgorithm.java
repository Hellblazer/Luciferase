package com.hellblazer.luciferase.esvo.dag;

/**
 * Supported hash algorithms for DAG deduplication.
 * SHA-256 provides good collision resistance for structural hashing.
 */
public enum HashAlgorithm {
    SHA256;

    /**
     * Create a new hasher instance for this algorithm
     */
    public Hasher createHasher() {
        return new JavaMessageDigestHasher("SHA-256");
    }

    @Override
    public String toString() {
        return "SHA-256";
    }
}
