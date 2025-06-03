package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/**
 * Parallel implementations of octree operations for improved performance on large datasets
 * Provides parallel alternatives to standard octree queries using Java's parallel streams
 * 
 * @author hal.hildebrand
 */
public class ParallelOctreeOperations {

    /**
     * Configuration for parallel execution
     */
    public static class ParallelConfig {
        public final int parallelismThreshold;
        public final ForkJoinPool customThreadPool;
        
        public ParallelConfig(int parallelismThreshold, ForkJoinPool customThreadPool) {
            this.parallelismThreshold = parallelismThreshold;
            this.customThreadPool = customThreadPool;
        }
        
        public static ParallelConfig defaultConfig() {
            return new ParallelConfig(1000, null); // Use common pool if null
        }
        
        public static ParallelConfig withCustomPool(int parallelismThreshold, int parallelism) {
            return new ParallelConfig(parallelismThreshold, new ForkJoinPool(parallelism));
        }
    }

    /**
     * Parallel version of boundedBy query
     * Uses parallel streams when dataset size exceeds threshold
     * 
     * @param volume the enclosing volume
     * @param octree the octree to search
     * @param config parallel execution configuration
     * @return stream of cubes bounded by the volume
     */
    public static <Content> List<Octree.Hexahedron<Content>> boundedByParallel(
            Spatial volume, Octree<Content> octree, ParallelConfig config) {
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return octree.boundedBy(volume).collect(Collectors.toList());
        }
        
        return executeInPool(config, () -> {
            // Use the octree's optimized boundedBy method and parallelize the stream
            return octree.boundedBy(volume).parallel().collect(Collectors.toList());
        });
    }

    /**
     * Parallel version of bounding query
     * 
     * @param volume the volume to contain
     * @param octree the octree to search
     * @param config parallel execution configuration
     * @return stream of cubes that bound the volume
     */
    public static <Content> List<Octree.Hexahedron<Content>> boundingParallel(
            Spatial volume, Octree<Content> octree, ParallelConfig config) {
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return octree.bounding(volume).collect(Collectors.toList());
        }
        
        return executeInPool(config, () -> {
            // Use the octree's optimized bounding method and parallelize the stream
            return octree.bounding(volume).parallel().collect(Collectors.toList());
        });
    }

    /**
     * Parallel k-Nearest Neighbor search
     * Partitions the search space and processes chunks in parallel
     * 
     * @param queryPoint the point to search around
     * @param k number of neighbors to find
     * @param octree the octree to search
     * @param config parallel execution configuration
     * @return list of k nearest neighbors
     */
    public static <Content> List<KNearestNeighborSearch.KNNCandidate<Content>> kNearestNeighborsParallel(
            Point3f queryPoint, int k, Octree<Content> octree, ParallelConfig config) {
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return KNearestNeighborSearch.findKNearestNeighbors(queryPoint, k, octree);
        }
        
        return executeInPool(config, () -> {
            // Process all candidates in parallel
            List<KNearestNeighborSearch.KNNCandidate<Content>> allCandidates = 
                map.entrySet().parallelStream()
                    .map(entry -> {
                        Spatial.Cube cube = Octree.toCube(entry.getKey());
                        float distance = calculateMinDistanceToBox(queryPoint, cube);
                        Point3f cubeCenter = getCubeCenter(cube);
                        
                        return new KNearestNeighborSearch.KNNCandidate<>(
                            entry.getKey(), entry.getValue(), cubeCenter, distance);
                    })
                    .collect(Collectors.toList());
            
            // Sort and take top k (this part remains sequential as it's inherently sequential)
            return allCandidates.stream()
                .sorted(Comparator.comparing(c -> c.distance))
                .limit(k)
                .collect(Collectors.toList());
        });
    }

    /**
     * Parallel ray tracing intersection search
     * Processes ray-cube intersection tests in parallel
     * 
     * @param ray the ray to trace
     * @param octree the octree to search
     * @param config parallel execution configuration
     * @return list of intersections ordered by distance
     */
    public static <Content> List<RayTracingSearch.RayIntersection<Content>> rayIntersectedAllParallel(
            Ray3D ray, Octree<Content> octree, ParallelConfig config) {
        
        NavigableMap<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return RayTracingSearch.rayIntersectedAll(ray, octree);
        }
        
        return executeInPool(config, () -> {
            List<RayTracingSearch.RayIntersection<Content>> intersections = 
                map.entrySet().parallelStream()
                    .map(entry -> {
                        Spatial.Cube cube = Octree.toCube(entry.getKey());
                        RayBoxIntersection intersection = rayBoxIntersection(ray, cube);
                        
                        if (intersection.intersects) {
                            return new RayTracingSearch.RayIntersection<>(
                                entry.getKey(), 
                                entry.getValue(), 
                                cube, 
                                intersection.distance,
                                intersection.intersectionPoint
                            );
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
            // Sort by distance (sequential as sorting is inherently sequential)
            intersections.sort(Comparator.comparing(ri -> ri.distance));
            return intersections;
        });
    }

    /**
     * Batch processing for multiple queries
     * Processes multiple k-NN queries in parallel
     * 
     * @param queryPoints list of points to search around
     * @param k number of neighbors per query
     * @param octree the octree to search
     * @param config parallel execution configuration
     * @return map of query points to their k nearest neighbors
     */
    public static <Content> Map<Point3f, List<KNearestNeighborSearch.KNNCandidate<Content>>> 
            batchKNearestNeighbors(List<Point3f> queryPoints, int k, Octree<Content> octree, 
                                 ParallelConfig config) {
        
        if (queryPoints.size() < config.parallelismThreshold / 10) {
            // Use sequential processing for small batch sizes
            return queryPoints.stream()
                .collect(Collectors.toMap(
                    point -> point,
                    point -> KNearestNeighborSearch.findKNearestNeighbors(point, k, octree)
                ));
        }
        
        return executeInPool(config, () -> {
            return queryPoints.parallelStream()
                .collect(Collectors.toConcurrentMap(
                    point -> point,
                    point -> kNearestNeighborsParallel(point, k, octree, config)
                ));
        });
    }

    /**
     * Execute operation in the configured thread pool
     */
    private static <T> T executeInPool(ParallelConfig config, java.util.function.Supplier<T> operation) {
        if (config.customThreadPool != null) {
            return config.customThreadPool.submit(operation::get).join();
        } else {
            return operation.get(); // Use common fork-join pool
        }
    }

    // Helper methods (duplicated from other classes for parallel operations)
    
    private static boolean cubeContainedInVolume(Spatial.Cube cube, Spatial volume) {
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return false;
        }
        
        return cube.originX() >= bounds.minX && cube.originX() + cube.extent() <= bounds.maxX
            && cube.originY() >= bounds.minY && cube.originY() + cube.extent() <= bounds.maxY
            && cube.originZ() >= bounds.minZ && cube.originZ() + cube.extent() <= bounds.maxZ;
    }
    
    private static boolean cubeIntersectsVolume(Spatial.Cube cube, Spatial volume) {
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return false;
        }
        
        return !(cube.originX() + cube.extent() < bounds.minX || cube.originX() > bounds.maxX
                || cube.originY() + cube.extent() < bounds.minY || cube.originY() > bounds.maxY
                || cube.originZ() + cube.extent() < bounds.minZ || cube.originZ() > bounds.maxZ);
    }
    
    private static VolumeBounds getVolumeBounds(Spatial volume) {
        return switch (volume) {
            case Spatial.Cube cube -> new VolumeBounds(cube.originX(), cube.originY(), cube.originZ(),
                                                       cube.originX() + cube.extent(), cube.originY() + cube.extent(),
                                                       cube.originZ() + cube.extent());
            case Spatial.Sphere sphere -> new VolumeBounds(sphere.centerX() - sphere.radius(),
                                                           sphere.centerY() - sphere.radius(),
                                                           sphere.centerZ() - sphere.radius(),
                                                           sphere.centerX() + sphere.radius(),
                                                           sphere.centerY() + sphere.radius(),
                                                           sphere.centerZ() + sphere.radius());
            case Spatial.aabb aabb -> new VolumeBounds(aabb.originX(), aabb.originY(), aabb.originZ(), aabb.extentX(),
                                                       aabb.extentY(), aabb.extentZ());
            case Spatial.aabt aabt -> new VolumeBounds(aabt.originX(), aabt.originY(), aabt.originZ(), aabt.extentX(),
                                                       aabt.extentY(), aabt.extentZ());
            case Spatial.Parallelepiped para -> new VolumeBounds(para.originX(), para.originY(), para.originZ(),
                                                                 para.extentX(), para.extentY(), para.extentZ());
            case Spatial.Tetrahedron tet -> {
                var vertices = new javax.vecmath.Tuple3f[] { tet.a(), tet.b(), tet.c(), tet.d() };
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
                for (var vertex : vertices) {
                    minX = Math.min(minX, vertex.x);
                    minY = Math.min(minY, vertex.y);
                    minZ = Math.min(minZ, vertex.z);
                    maxX = Math.max(maxX, vertex.x);
                    maxY = Math.max(maxY, vertex.y);
                    maxZ = Math.max(maxZ, vertex.z);
                }
                yield new VolumeBounds(minX, minY, minZ, maxX, maxY, maxZ);
            }
            default -> null;
        };
    }
    
    private static float calculateMinDistanceToBox(Point3f point, Spatial.Cube cube) {
        float dx = Math.max(0, Math.max(cube.originX() - point.x, 
                                       point.x - (cube.originX() + cube.extent())));
        float dy = Math.max(0, Math.max(cube.originY() - point.y, 
                                       point.y - (cube.originY() + cube.extent())));
        float dz = Math.max(0, Math.max(cube.originZ() - point.z, 
                                       point.z - (cube.originZ() + cube.extent())));
        
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    private static Point3f getCubeCenter(Spatial.Cube cube) {
        float halfExtent = cube.extent() / 2.0f;
        return new Point3f(
            cube.originX() + halfExtent,
            cube.originY() + halfExtent,
            cube.originZ() + halfExtent
        );
    }
    
    // Ray-box intersection for parallel operations
    private static class RayBoxIntersection {
        public final boolean intersects;
        public final float distance;
        public final Point3f intersectionPoint;

        public RayBoxIntersection(boolean intersects, float distance, Point3f intersectionPoint) {
            this.intersects = intersects;
            this.distance = distance;
            this.intersectionPoint = intersectionPoint;
        }
    }
    
    private static RayBoxIntersection rayBoxIntersection(Ray3D ray, Spatial.Cube cube) {
        Point3f origin = ray.origin();
        javax.vecmath.Vector3f direction = ray.direction();
        
        float minX = cube.originX();
        float maxX = cube.originX() + cube.extent();
        float minY = cube.originY();
        float maxY = cube.originY() + cube.extent();
        float minZ = cube.originZ();
        float maxZ = cube.originZ() + cube.extent();

        float tmin = Float.NEGATIVE_INFINITY;
        float tmax = Float.POSITIVE_INFINITY;

        // X slab
        if (Math.abs(direction.x) < 1e-6f) {
            if (origin.x < minX || origin.x > maxX) {
                return new RayBoxIntersection(false, Float.MAX_VALUE, null);
            }
        } else {
            float invDirX = 1.0f / direction.x;
            float t1 = (minX - origin.x) * invDirX;
            float t2 = (maxX - origin.x) * invDirX;
            
            if (t1 > t2) {
                float temp = t1; t1 = t2; t2 = temp;
            }
            
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            
            if (tmin > tmax) {
                return new RayBoxIntersection(false, Float.MAX_VALUE, null);
            }
        }

        // Y slab
        if (Math.abs(direction.y) < 1e-6f) {
            if (origin.y < minY || origin.y > maxY) {
                return new RayBoxIntersection(false, Float.MAX_VALUE, null);
            }
        } else {
            float invDirY = 1.0f / direction.y;
            float t1 = (minY - origin.y) * invDirY;
            float t2 = (maxY - origin.y) * invDirY;
            
            if (t1 > t2) {
                float temp = t1; t1 = t2; t2 = temp;
            }
            
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            
            if (tmin > tmax) {
                return new RayBoxIntersection(false, Float.MAX_VALUE, null);
            }
        }

        // Z slab
        if (Math.abs(direction.z) < 1e-6f) {
            if (origin.z < minZ || origin.z > maxZ) {
                return new RayBoxIntersection(false, Float.MAX_VALUE, null);
            }
        } else {
            float invDirZ = 1.0f / direction.z;
            float t1 = (minZ - origin.z) * invDirZ;
            float t2 = (maxZ - origin.z) * invDirZ;
            
            if (t1 > t2) {
                float temp = t1; t1 = t2; t2 = temp;
            }
            
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            
            if (tmin > tmax) {
                return new RayBoxIntersection(false, Float.MAX_VALUE, null);
            }
        }

        if (tmax < 0) {
            return new RayBoxIntersection(false, Float.MAX_VALUE, null);
        }

        float t = (tmin >= 0) ? tmin : tmax;
        Point3f intersectionPoint = ray.getPointAt(t);
        
        return new RayBoxIntersection(true, t, intersectionPoint);
    }
    
    // Helper record for volume bounds
    private record VolumeBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    }
}