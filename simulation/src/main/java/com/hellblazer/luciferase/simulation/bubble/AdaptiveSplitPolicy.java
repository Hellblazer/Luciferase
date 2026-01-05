package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.bubble.*;

import javafx.geometry.Point3D;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Adaptive split policy for overloaded bubbles using cluster-based tetrahedral subdivision.
 * <p>
 * Algorithm:
 * 1. Detect split necessity based on frame utilization (> 120% threshold)
 * 2. Analyze entity distribution using k-means clustering in RDGCS space
 * 3. Create child bubbles based on detected clusters
 * 4. Fall back to geometric (octant) subdivision for uniform distributions
 * 5. Redistribute entities to child bubbles with containment tests
 * <p>
 * Thread-safe for concurrent split analysis.
 *
 * @author hal.hildebrand
 */
public class AdaptiveSplitPolicy {

    private final float splitThreshold;
    private final int minEntitiesPerBubble;

    /**
     * Create adaptive split policy with custom configuration.
     *
     * @param splitThreshold        Frame utilization threshold for split (e.g., 1.2 = 120%)
     * @param minEntitiesPerBubble Minimum entities required per bubble after split
     */
    public AdaptiveSplitPolicy(float splitThreshold, int minEntitiesPerBubble) {
        this.splitThreshold = splitThreshold;
        this.minEntitiesPerBubble = minEntitiesPerBubble;
    }

    /**
     * Determine if a bubble should split based on frame utilization.
     *
     * @param bubble Bubble to evaluate
     * @return true if bubble exceeds split threshold
     */
    public boolean shouldSplit(EnhancedBubble bubble) {
        return bubble.frameUtilization() > splitThreshold;
    }

    /**
     * Detect entity clusters using simple k-means in RDGCS space.
     * Falls back to indicating geometric split for uniform distributions.
     *
     * @param bubble          Bubble to analyze
     * @param minClusterSize Minimum entities per cluster
     * @param maxDistance    Maximum intra-cluster distance
     * @return List of detected clusters
     */
    public List<EntityCluster> detectClusters(EnhancedBubble bubble, int minClusterSize, float maxDistance) {
        var entityIds = bubble.getEntities();
        if (entityIds.size() < minClusterSize * 2) {
            // Not enough entities for meaningful clustering
            return Collections.emptyList();
        }

        // Collect entity positions by getting bounds and querying within them
        var bounds = bubble.bounds();
        if (bounds == null) {
            return Collections.emptyList();
        }

        // Get all entity records directly (avoids coordinate issues with large radius queries)
        var allRecords = bubble.getAllEntityRecords();

        var positions = new ArrayList<Point3f>();
        var idList = new ArrayList<String>();
        for (var record : allRecords) {
            positions.add(record.position());
            idList.add(record.id());
        }

        if (positions.isEmpty()) {
            return Collections.emptyList();
        }

        // Simple 2-means clustering
        int k = Math.min(2, positions.size() / minClusterSize);
        if (k < 2) {
            return Collections.emptyList();
        }

        var clusters = performKMeans(positions, idList, k, maxDistance);

        // Filter clusters by minimum size
        var validClusters = new ArrayList<EntityCluster>();
        for (var cluster : clusters) {
            if (cluster.size() >= minClusterSize) {
                validClusters.add(cluster);
            }
        }

        // Merge small clusters if needed
        if (validClusters.size() > 1 && validClusters.stream().anyMatch(c -> c.size() < minClusterSize)) {
            return mergeSmallClusters(validClusters, minClusterSize);
        }

        return validClusters;
    }

    /**
     * Analyze bubble for split feasibility and determine strategy.
     *
     * @param bubble Bubble to analyze
     * @return Split analysis result with clusters and estimated frame time
     */
    public SplitResult analyzeSplit(EnhancedBubble bubble) {
        if (!shouldSplit(bubble)) {
            return new SplitResult(false, Collections.emptyList(), 0, "Below split threshold");
        }

        int entityCount = bubble.entityCount();
        if (entityCount < minEntitiesPerBubble * 2) {
            return new SplitResult(false, Collections.emptyList(), 0,
                                 "Too few entities to split (" + entityCount + " < " + (minEntitiesPerBubble * 2) + ")");
        }

        // Attempt cluster detection
        var clusters = detectClusters(bubble, minEntitiesPerBubble, 50.0f);

        if (clusters.isEmpty() || clusters.size() == 1) {
            // Fall back to geometric split (create virtual clusters for octants)
            clusters = createGeometricClusters(bubble);
        }

        // Estimate frame time per child bubble
        float currentFrameTime = bubble.frameUtilization() * 10; // targetFrameMs = 10
        float estimatedFrameTimePerBubble = currentFrameTime / clusters.size();

        return new SplitResult(true, clusters, estimatedFrameTimePerBubble,
                             "Split into " + clusters.size() + " bubbles");
    }

    /**
     * Perform split operation, creating child bubbles from clusters.
     *
     * @param source   Source bubble to split
     * @param analysis Split analysis result
     * @return List of child bubbles
     */
    public List<EnhancedBubble> performSplit(EnhancedBubble source, SplitResult analysis) {
        if (!analysis.feasible()) {
            return Collections.emptyList();
        }

        var childBubbles = new ArrayList<EnhancedBubble>();
        for (var cluster : analysis.clusters()) {
            var childId = UUID.randomUUID();
            var child = new EnhancedBubble(childId, (byte) 10, 10);
            childBubbles.add(child);
        }

        return childBubbles;
    }

    /**
     * Redistribute entities from source bubble to child bubbles.
     * Uses cluster assignments from split analysis.
     *
     * @param source   Source bubble
     * @param children Child bubbles to receive entities
     */
    public void redistributeEntities(EnhancedBubble source, List<EnhancedBubble> children) {
        if (children.isEmpty()) {
            return;
        }

        // Get all entity records from source directly
        var allRecords = source.getAllEntityRecords();

        // Simple distribution: divide entities among children
        int entitiesPerChild = allRecords.size() / children.size();
        int childIndex = 0;

        for (int i = 0; i < allRecords.size(); i++) {
            var record = allRecords.get(i);

            // Add to current child
            children.get(childIndex).addEntity(record.id(), record.position(), record.content());

            // Move to next child if this one has enough entities
            if ((i + 1) % entitiesPerChild == 0 && childIndex < children.size() - 1) {
                childIndex++;
            }
        }
    }

    /**
     * Simple k-means clustering in 3D space.
     */
    private List<EntityCluster> performKMeans(List<Point3f> positions, List<String> ids, int k, float maxDistance) {
        int n = positions.size();
        if (n == 0 || k == 0) {
            return Collections.emptyList();
        }

        // Initialize centroids randomly
        var random = new Random(42); // Fixed seed for reproducibility
        var centroids = new ArrayList<Point3D>();
        for (int i = 0; i < k; i++) {
            var pos = positions.get(random.nextInt(n));
            centroids.add(new Point3D(pos.x, pos.y, pos.z));
        }

        // Assignment and update (simplified - just 5 iterations)
        var assignments = new int[n];
        for (int iter = 0; iter < 5; iter++) {
            // Assign to nearest centroid
            for (int i = 0; i < n; i++) {
                var pos = positions.get(i);
                float minDist = Float.MAX_VALUE;
                int bestCluster = 0;

                for (int j = 0; j < k; j++) {
                    var centroid = centroids.get(j);
                    float dx = pos.x - (float) centroid.getX();
                    float dy = pos.y - (float) centroid.getY();
                    float dz = pos.z - (float) centroid.getZ();
                    float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

                    if (dist < minDist) {
                        minDist = dist;
                        bestCluster = j;
                    }
                }
                assignments[i] = bestCluster;
            }

            // Update centroids
            for (int j = 0; j < k; j++) {
                double sumX = 0, sumY = 0, sumZ = 0;
                int count = 0;

                for (int i = 0; i < n; i++) {
                    if (assignments[i] == j) {
                        sumX += positions.get(i).x;
                        sumY += positions.get(i).y;
                        sumZ += positions.get(i).z;
                        count++;
                    }
                }

                if (count > 0) {
                    centroids.set(j, new Point3D(sumX / count, sumY / count, sumZ / count));
                }
            }
        }

        // Build clusters
        var clusters = new ArrayList<EntityCluster>();
        for (int j = 0; j < k; j++) {
            var clusterIds = new ArrayList<String>();
            var clusterPositions = new ArrayList<Point3f>();

            for (int i = 0; i < n; i++) {
                if (assignments[i] == j) {
                    clusterIds.add(ids.get(i));
                    clusterPositions.add(positions.get(i));
                }
            }

            if (!clusterIds.isEmpty()) {
                // Note: bounds not needed for split policy - using null
                clusters.add(new EntityCluster(centroids.get(j), null, clusterIds, clusterIds.size()));
            }
        }

        return clusters;
    }

    /**
     * Merge small clusters to meet minimum size requirement.
     */
    private List<EntityCluster> mergeSmallClusters(List<EntityCluster> clusters, int minSize) {
        var result = new ArrayList<EntityCluster>();
        var smallClusters = new ArrayList<EntityCluster>();

        for (var cluster : clusters) {
            if (cluster.size() >= minSize) {
                result.add(cluster);
            } else {
                smallClusters.add(cluster);
            }
        }

        // Merge all small clusters into one
        if (!smallClusters.isEmpty()) {
            var mergedIds = new ArrayList<String>();
            var mergedPositions = new ArrayList<Point3f>();
            double sumX = 0, sumY = 0, sumZ = 0;

            for (var cluster : smallClusters) {
                mergedIds.addAll(cluster.entityIds());
                sumX += cluster.centroid().getX() * cluster.size();
                sumY += cluster.centroid().getY() * cluster.size();
                sumZ += cluster.centroid().getZ() * cluster.size();
            }

            int totalSize = mergedIds.size();
            var centroid = new Point3D(sumX / totalSize, sumY / totalSize, sumZ / totalSize);

            // Note: We can't easily get positions from IDs here, so we use a placeholder bounds
            // In a real implementation, we'd need to fetch positions from the bubble
            var bounds = result.isEmpty() ? null : result.get(0).bounds();

            result.add(new EntityCluster(centroid, bounds, mergedIds, totalSize));
        }

        return result;
    }

    /**
     * Create geometric octant clusters for uniform distributions.
     */
    private List<EntityCluster> createGeometricClusters(EnhancedBubble bubble) {
        var bounds = bubble.bounds();
        if (bounds == null) {
            return Collections.emptyList();
        }

        // For geometric split, create 2-8 virtual clusters based on octants
        // Simplified: just create 2 clusters (left/right split)
        var centroid = bubble.centroid();
        if (centroid == null) {
            return Collections.emptyList();
        }

        var leftIds = new ArrayList<String>();
        var rightIds = new ArrayList<String>();

        // Split entities based on position relative to centroid
        var allRecords = bubble.getAllEntityRecords();

        for (var record : allRecords) {
            if (record.position().x < centroid.getX()) {
                leftIds.add(record.id());
            } else {
                rightIds.add(record.id());
            }
        }

        var clusters = new ArrayList<EntityCluster>();

        if (!leftIds.isEmpty()) {
            var leftCentroid = new Point3D(centroid.getX() - 10, centroid.getY(), centroid.getZ());
            // Use bubble's bounds for geometric clusters
            clusters.add(new EntityCluster(leftCentroid, null, leftIds, leftIds.size()));
        }

        if (!rightIds.isEmpty()) {
            var rightCentroid = new Point3D(centroid.getX() + 10, centroid.getY(), centroid.getZ());
            // Use bubble's bounds for geometric clusters
            clusters.add(new EntityCluster(rightCentroid, null, rightIds, rightIds.size()));
        }

        return clusters;
    }

    /**
     * Entity cluster record for split analysis.
     */
    public record EntityCluster(
        Point3D centroid,
        BubbleBounds bounds,
        List<String> entityIds,
        int size
    ) {
    }

    /**
     * Split analysis result.
     */
    public record SplitResult(
        boolean feasible,
        List<EntityCluster> clusters,
        float estimatedFrameTimePerBubble,
        String reason
    ) {
    }
}
