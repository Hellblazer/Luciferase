/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.performance;

import com.hellblazer.luciferase.lucien.VolumeBounds;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Spatial distribution patterns for performance testing. Based on C++ Octree benchmark distributions.
 *
 * @author hal.hildebrand
 */
public enum SpatialDistribution {
    /**
     * Uniform random distribution throughout the volume
     */
    UNIFORM_RANDOM {
        @Override
        public List<Point3f> generate(int count, VolumeBounds bounds) {
            List<Point3f> points = new ArrayList<>(count);
            Random rand = new Random(42); // Fixed seed for reproducibility

            float width = bounds.maxX() - bounds.minX();
            float height = bounds.maxY() - bounds.minY();
            float depth = bounds.maxZ() - bounds.minZ();

            for (int i = 0; i < count; i++) {
                points.add(
                new Point3f(bounds.minX() + rand.nextFloat() * width, bounds.minY() + rand.nextFloat() * height,
                            bounds.minZ() + rand.nextFloat() * depth));
            }

            return points;
        }
    },

    /**
     * Gaussian clusters at random locations
     */
    CLUSTERED {
        @Override
        public List<Point3f> generate(int count, VolumeBounds bounds) {
            List<Point3f> points = new ArrayList<>(count);
            Random rand = new Random(42);

            // Create clusters
            int clusterCount = Math.max(1, count / 100);
            float clusterRadius = Math.min(bounds.maxX() - bounds.minX(),
                                           Math.min(bounds.maxY() - bounds.minY(), bounds.maxZ() - bounds.minZ()))
            * 0.1f; // 10% of smallest dimension

            // Generate cluster centers
            List<Point3f> clusterCenters = UNIFORM_RANDOM.generate(clusterCount, bounds);

            // Generate points around clusters
            for (int i = 0; i < count; i++) {
                Point3f center = clusterCenters.get(i % clusterCount);

                // Gaussian distribution around center
                float dx = (float) (rand.nextGaussian() * clusterRadius);
                float dy = (float) (rand.nextGaussian() * clusterRadius);
                float dz = (float) (rand.nextGaussian() * clusterRadius);

                Point3f point = new Point3f(Math.max(bounds.minX(), Math.min(bounds.maxX(), center.x + dx)),
                                            Math.max(bounds.minY(), Math.min(bounds.maxY(), center.y + dy)),
                                            Math.max(bounds.minZ(), Math.min(bounds.maxZ(), center.z + dz)));

                points.add(point);
            }

            return points;
        }
    },

    /**
     * Points along diagonal from min to max corner (structured pattern)
     */
    DIAGONAL {
        @Override
        public List<Point3f> generate(int count, VolumeBounds bounds) {
            List<Point3f> points = new ArrayList<>(count);

            if (count == 0) {
                return points;
            }

            // Add corner points first
            points.add(new Point3f(bounds.minX(), bounds.minY(), bounds.minZ()));
            if (count == 1) {
                return points;
            }

            points.add(new Point3f(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
            if (count == 2) {
                return points;
            }

            // Add axis-aligned corner points
            int cornerIdx = 2;
            if (cornerIdx < count) {
                points.add(new Point3f(bounds.maxX(), bounds.minY(), bounds.minZ()));
                cornerIdx++;
            }
            if (cornerIdx < count) {
                points.add(new Point3f(bounds.minX(), bounds.maxY(), bounds.minZ()));
                cornerIdx++;
            }
            if (cornerIdx < count) {
                points.add(new Point3f(bounds.minX(), bounds.minY(), bounds.maxZ()));
                cornerIdx++;
            }

            // Fill remaining points along diagonal
            float step = 1.0f / (count - cornerIdx + 1);
            for (int i = cornerIdx; i < count; i++) {
                float t = (i - cornerIdx + 1) * step;
                points.add(new Point3f(bounds.minX() + t * (bounds.maxX() - bounds.minX()),
                                       bounds.minY() + t * (bounds.maxY() - bounds.minY()),
                                       bounds.minZ() + t * (bounds.maxZ() - bounds.minZ())));
            }

            return points;
        }
    },

    /**
     * Cylindrical semi-random distribution (matches C++ benchmark)
     */
    SURFACE_ALIGNED {
        @Override
        public List<Point3f> generate(int count, VolumeBounds bounds) {
            List<Point3f> points = new ArrayList<>(count);
            Random rand = new Random(42);

            if (count == 0) {
                return points;
            }

            // Add corner points first
            points.add(new Point3f(bounds.minX(), bounds.minY(), bounds.minZ()));
            if (count == 1) {
                return points;
            }

            points.add(new Point3f(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
            if (count == 2) {
                return points;
            }

            // Cylindrical distribution for remaining points
            float centerX = (bounds.minX() + bounds.maxX()) / 2;
            float centerY = (bounds.minY() + bounds.maxY()) / 2;
            float maxRadius = Math.min((bounds.maxX() - bounds.minX()) / 2, (bounds.maxY() - bounds.minY()) / 2);
            float height = bounds.maxZ() - bounds.minZ();

            for (int i = 2; i < count; i++) {
                double angle = rand.nextDouble() * 2 * Math.PI;
                double radius = maxRadius * 0.25 + rand.nextDouble() * maxRadius * 0.5; // 25% to 75% of max radius

                points.add(new Point3f((float) (centerX + Math.cos(angle) * radius), (float) (centerY + Math.sin(angle)
                * radius), bounds.minZ() + rand.nextFloat() * height));
            }

            return points;
        }
    },

    /**
     * Worst-case distribution for tree structures (all points in one corner)
     */
    WORST_CASE {
        @Override
        public List<Point3f> generate(int count, VolumeBounds bounds) {
            List<Point3f> points = new ArrayList<>(count);
            Random rand = new Random(42);

            // All points clustered in a tiny region (1% of volume)
            float epsilon = 0.01f;
            float minX = bounds.minX();
            float minY = bounds.minY();
            float minZ = bounds.minZ();
            float maxX = bounds.minX() + (bounds.maxX() - bounds.minX()) * epsilon;
            float maxY = bounds.minY() + (bounds.maxY() - bounds.minY()) * epsilon;
            float maxZ = bounds.minZ() + (bounds.maxZ() - bounds.minZ()) * epsilon;

            for (int i = 0; i < count; i++) {
                points.add(new Point3f(minX + rand.nextFloat() * (maxX - minX), minY + rand.nextFloat() * (maxY - minY),
                                       minZ + rand.nextFloat() * (maxZ - minZ)));
            }

            return points;
        }
    };

    /**
     * Generate points with this distribution pattern
     */
    public abstract List<Point3f> generate(int count, VolumeBounds bounds);
}
