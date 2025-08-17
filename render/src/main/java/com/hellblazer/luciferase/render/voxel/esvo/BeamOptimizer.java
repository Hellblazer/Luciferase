/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.hellblazer.luciferase.render.voxel.esvo;

import javax.vecmath.Vector3f;
import java.util.*;

/**
 * ESVO beam optimization for coherent ray processing.
 * 
 * Groups rays with similar directions and origins into beams for efficient traversal.
 * This reduces memory bandwidth and improves cache locality during octree traversal.
 * 
 * Based on the ESVO paper's beam optimization techniques:
 * - Spatial clustering of ray origins
 * - Directional clustering of ray directions  
 * - Adaptive beam splitting based on divergence
 * - Priority ordering for maximum coherence
 */
public class BeamOptimizer {
    
    /**
     * Configuration for beam optimization parameters.
     */
    public static class BeamConfig {
        /** Maximum rays per beam */
        public int maxRaysPerBeam = 64;
        
        /** Minimum rays per beam to avoid overhead */
        public int minRaysPerBeam = 1; // Changed from 4 to 1 to handle single rays
        
        /** Maximum directional angle variance in radians */
        public float maxDirectionalVariance = 0.1f;
        
        /** Maximum spatial distance variance */
        public float maxSpatialVariance = 1.0f;
        
        /** Whether to use adaptive beam splitting */
        public boolean adaptiveSplitting = true;
        
        /** Coherence threshold for beam quality */
        public float coherenceThreshold = 0.8f;
        
        public static BeamConfig defaultConfig() {
            return new BeamConfig();
        }
        
        public BeamConfig withMaxRays(int maxRays) {
            this.maxRaysPerBeam = maxRays;
            return this;
        }
        
        public BeamConfig withDirectionalVariance(float variance) {
            this.maxDirectionalVariance = variance;
            return this;
        }
        
        public BeamConfig withSpatialVariance(float variance) {
            this.maxSpatialVariance = variance;
            return this;
        }
    }
    
    /**
     * Represents a ray for beam processing.
     */
    public static class Ray {
        public final Vector3f origin;
        public final Vector3f direction;
        public final int pixelX;
        public final int pixelY;
        public final int rayId;
        
        public Ray(Vector3f origin, Vector3f direction, int pixelX, int pixelY, int rayId) {
            this.origin = new Vector3f(origin);
            this.direction = new Vector3f(direction);
            this.direction.normalize();
            this.pixelX = pixelX;
            this.pixelY = pixelY;
            this.rayId = rayId;
        }
        
        /**
         * Calculates directional similarity with another ray.
         * 
         * @param other Other ray
         * @return Dot product of normalized directions (0-1, higher = more similar)
         */
        public float directionalSimilarity(Ray other) {
            return Math.max(0.0f, this.direction.dot(other.direction));
        }
        
        /**
         * Calculates spatial distance to another ray origin.
         * 
         * @param other Other ray
         * @return Euclidean distance between origins
         */
        public float spatialDistance(Ray other) {
            var temp = new Vector3f(this.origin);
            temp.sub(other.origin);
            return temp.length();
        }
    }
    
    /**
     * Represents a coherent beam of rays.
     */
    public static class Beam {
        private final List<Ray> rays = new ArrayList<>();
        private Vector3f centroidOrigin;
        private Vector3f averageDirection;
        private float coherenceScore;
        private boolean isDirty = true;
        
        public void addRay(Ray ray) {
            rays.add(ray);
            isDirty = true;
        }
        
        public List<Ray> getRays() {
            return Collections.unmodifiableList(rays);
        }
        
        public int getRayCount() {
            return rays.size();
        }
        
        public boolean isEmpty() {
            return rays.isEmpty();
        }
        
        /**
         * Gets the beam's centroid origin (average of all ray origins).
         */
        public Vector3f getCentroidOrigin() {
            if (isDirty) {
                computeStatistics();
            }
            return new Vector3f(centroidOrigin);
        }
        
        /**
         * Gets the beam's average direction (normalized average of all directions).
         */
        public Vector3f getAverageDirection() {
            if (isDirty) {
                computeStatistics();
            }
            return new Vector3f(averageDirection);
        }
        
        /**
         * Gets the beam's coherence score (0-1, higher = more coherent).
         */
        public float getCoherenceScore() {
            if (isDirty) {
                computeStatistics();
            }
            return coherenceScore;
        }
        
        /**
         * Checks if the beam would accept a new ray based on coherence criteria.
         */
        public boolean wouldAccept(Ray ray, BeamConfig config) {
            if (rays.isEmpty()) return true;
            if (rays.size() >= config.maxRaysPerBeam) return false;
            
            // Check directional coherence
            var avgDir = getAverageDirection();
            float directionalSimilarity = Math.max(0.0f, ray.direction.dot(avgDir));
            float directionalAngle = (float) Math.acos(Math.min(1.0f, directionalSimilarity));
            
            if (directionalAngle > config.maxDirectionalVariance) {
                return false;
            }
            
            // Check spatial coherence
            var centroid = getCentroidOrigin();
            var temp = new Vector3f(ray.origin);
            temp.sub(centroid);
            float spatialDistance = temp.length();
            
            return spatialDistance <= config.maxSpatialVariance;
        }
        
        /**
         * Computes beam statistics (centroid, average direction, coherence).
         */
        private void computeStatistics() {
            if (rays.isEmpty()) {
                centroidOrigin = new Vector3f();
                averageDirection = new Vector3f(0, 0, 1);
                coherenceScore = 0.0f;
                isDirty = false;
                return;
            }
            
            // Compute centroid origin
            centroidOrigin = new Vector3f();
            for (var ray : rays) {
                centroidOrigin.add(ray.origin);
            }
            centroidOrigin.scale(1.0f / rays.size());
            
            // Compute average direction
            averageDirection = new Vector3f();
            for (var ray : rays) {
                averageDirection.add(ray.direction);
            }
            averageDirection.normalize();
            
            // Compute coherence score
            coherenceScore = computeCoherenceScore();
            isDirty = false;
        }
        
        /**
         * Computes coherence score based on directional and spatial variance.
         */
        private float computeCoherenceScore() {
            if (rays.size() <= 1) return 1.0f;
            
            // Directional coherence (average dot product with mean direction)
            float directionalCoherence = 0.0f;
            for (var ray : rays) {
                directionalCoherence += Math.max(0.0f, ray.direction.dot(averageDirection));
            }
            directionalCoherence /= rays.size();
            
            // Spatial coherence (inverse of variance)
            float spatialVariance = 0.0f;
            for (var ray : rays) {
                var temp = new Vector3f(ray.origin);
                temp.sub(centroidOrigin);
                spatialVariance += temp.lengthSquared();
            }
            spatialVariance /= rays.size();
            
            // Combine coherence metrics (directional weighted higher)
            float spatialCoherence = (float) Math.exp(-spatialVariance * 0.1f); // Exponential decay
            return directionalCoherence * 0.8f + spatialCoherence * 0.2f;
        }
        
        @Override
        public String toString() {
            return String.format("Beam[rays=%d, coherence=%.3f]", getRayCount(), getCoherenceScore());
        }
    }
    
    private final BeamConfig config;
    
    public BeamOptimizer(BeamConfig config) {
        this.config = config;
    }
    
    public BeamOptimizer() {
        this(BeamConfig.defaultConfig());
    }
    
    /**
     * Optimizes a collection of rays into coherent beams.
     * 
     * @param rays Input rays to optimize
     * @return List of optimized beams, sorted by coherence score (descending)
     */
    public List<Beam> optimizeRays(Collection<Ray> rays) {
        if (rays.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<Beam> beams = new ArrayList<>();
        List<Ray> remainingRays = new ArrayList<>(rays);
        
        // Sort rays by screen-space proximity for better initial clustering
        remainingRays.sort((a, b) -> {
            int deltaX = a.pixelX - b.pixelX;
            int deltaY = a.pixelY - b.pixelY;
            return Integer.compare(deltaX * deltaX + deltaY * deltaY, 0);
        });
        
        while (!remainingRays.isEmpty()) {
            Beam beam = createOptimalBeam(remainingRays);
            if (beam.getRayCount() >= config.minRaysPerBeam || remainingRays.isEmpty()) {
                beams.add(beam);
            } else {
                // Handle remaining rays with smaller beams if needed
                addRemainingRaysToBeams(remainingRays, beams);
                // Add the current beam even if small to avoid losing rays
                if (beam.getRayCount() > 0) {
                    beams.add(beam);
                }
                break;
            }
        }
        
        // Sort beams by coherence score (highest first for priority processing)
        beams.sort((a, b) -> Float.compare(b.getCoherenceScore(), a.getCoherenceScore()));
        
        return beams;
    }
    
    /**
     * Creates an optimal beam from the remaining rays.
     */
    private Beam createOptimalBeam(List<Ray> remainingRays) {
        if (remainingRays.isEmpty()) {
            return new Beam();
        }
        
        // Start with the first ray as seed
        Ray seed = remainingRays.remove(0);
        Beam beam = new Beam();
        beam.addRay(seed);
        
        // Greedily add most compatible rays
        Iterator<Ray> iter = remainingRays.iterator();
        while (iter.hasNext() && beam.getRayCount() < config.maxRaysPerBeam) {
            Ray candidate = iter.next();
            
            if (beam.wouldAccept(candidate, config)) {
                beam.addRay(candidate);
                iter.remove();
                
                // If adaptive splitting is enabled, check if we should split
                if (config.adaptiveSplitting && 
                    beam.getCoherenceScore() < config.coherenceThreshold && 
                    beam.getRayCount() >= config.minRaysPerBeam) {
                    break;
                }
            }
        }
        
        return beam;
    }
    
    /**
     * Adds remaining rays to existing beams or creates small beams.
     */
    private void addRemainingRaysToBeams(List<Ray> remainingRays, List<Beam> beams) {
        for (Ray ray : remainingRays) {
            boolean added = false;
            
            // Try to add to existing beams
            for (Beam beam : beams) {
                if (beam.wouldAccept(ray, config)) {
                    beam.addRay(ray);
                    added = true;
                    break;
                }
            }
            
            // Create single-ray beam if couldn't add elsewhere
            if (!added) {
                Beam singleRayBeam = new Beam();
                singleRayBeam.addRay(ray);
                beams.add(singleRayBeam);
            }
        }
        remainingRays.clear();
    }
    
    /**
     * Analyzes beam optimization results.
     */
    public static class BeamAnalysis {
        public final int totalRays;
        public final int totalBeams;
        public final float averageBeamSize;
        public final float averageCoherence;
        public final float compressionRatio;
        
        public BeamAnalysis(List<Beam> beams) {
            this.totalBeams = beams.size();
            this.totalRays = beams.stream().mapToInt(Beam::getRayCount).sum();
            this.averageBeamSize = totalBeams > 0 ? (float) totalRays / totalBeams : 0.0f;
            this.averageCoherence = beams.stream()
                .map(Beam::getCoherenceScore)
                .reduce(0.0f, Float::sum) / Math.max(1, totalBeams);
            this.compressionRatio = totalRays > 0 ? (float) totalBeams / totalRays : 0.0f;
        }
        
        @Override
        public String toString() {
            return String.format("BeamAnalysis[rays=%d, beams=%d, avgSize=%.1f, avgCoherence=%.3f, compression=%.3f]",
                totalRays, totalBeams, averageBeamSize, averageCoherence, compressionRatio);
        }
    }
    
    /**
     * Analyzes beam optimization results.
     */
    public BeamAnalysis analyzeBeams(List<Beam> beams) {
        return new BeamAnalysis(beams);
    }
}