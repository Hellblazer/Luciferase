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
    public static class ParallelConfig implements AutoCloseable {
        public final int parallelismThreshold;
        public final ForkJoinPool customThreadPool;
        private final boolean shouldShutdownPool;
        
        public ParallelConfig(int parallelismThreshold, ForkJoinPool customThreadPool) {
            this(parallelismThreshold, customThreadPool, false);
        }
        
        private ParallelConfig(int parallelismThreshold, ForkJoinPool customThreadPool, boolean shouldShutdownPool) {
            this.parallelismThreshold = parallelismThreshold;
            this.customThreadPool = customThreadPool;
            this.shouldShutdownPool = shouldShutdownPool;
        }
        
        public static ParallelConfig defaultConfig() {
            return new ParallelConfig(1000, null, false); // Use common pool if null
        }
        
        public static ParallelConfig withCustomPool(int parallelismThreshold, int parallelism) {
            return new ParallelConfig(parallelismThreshold, new ForkJoinPool(parallelism), true);
        }
        
        @Override
        public void close() {
            if (shouldShutdownPool && customThreadPool != null) {
                customThreadPool.shutdown();
            }
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
        
        Map<Long, Content> map = octree.getMap();
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
     * Parallel version of boundedBy query using adapter
     * 
     * @param volume the volume to search within
     * @param adapter the adapter to search
     * @param config parallel execution configuration
     * @return list of cubes bounded by the volume
     */
    public static <Content> List<Octree.Hexahedron<Content>> boundedByParallel(
            Spatial volume, SingleContentAdapter<Content> adapter, ParallelConfig config) {
        
        Map<Long, Content> map = adapter.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return adapter.boundedBy(volume).collect(Collectors.toList());
        }
        
        return executeInPool(config, () -> {
            // Use the adapter's optimized boundedBy method and parallelize the stream
            return adapter.boundedBy(volume).parallel().collect(Collectors.toList());
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
        
        Map<Long, Content> map = octree.getMap();
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
        
        Map<Long, Content> map = octree.getMap();
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
        
        Map<Long, Content> map = octree.getMap();
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
     * Parallel plane intersection search
     * Processes plane-cube intersection tests in parallel
     * 
     * @param plane the plane to test intersection with
     * @param octree the octree to search
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param config parallel execution configuration
     * @return list of intersections ordered by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<PlaneIntersectionSearch.PlaneIntersection<Content>> planeIntersectedAllParallel(
            Plane3D plane, Octree<Content> octree, Point3f referencePoint, ParallelConfig config) {
        
        Map<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return PlaneIntersectionSearch.planeIntersectedAll(plane, octree, referencePoint);
        }
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        return executeInPool(config, () -> {
            List<PlaneIntersectionSearch.PlaneIntersection<Content>> intersections = 
                map.entrySet().parallelStream()
                    .map(entry -> {
                        Spatial.Cube cube = Octree.toCube(entry.getKey());
                        
                        if (plane.intersectsCube(cube)) {
                            Point3f cubeCenter = getCubeCenter(cube);
                            float distance = calculateDistance(referencePoint, cubeCenter);
                            
                            return new PlaneIntersectionSearch.PlaneIntersection<>(
                                entry.getKey(), 
                                entry.getValue(), 
                                cube, 
                                distance,
                                cubeCenter
                            );
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
            // Sort by distance from reference point
            intersections.sort(Comparator.comparing(pi -> pi.distanceToReferencePoint));
            return intersections;
        });
    }

    /**
     * Parallel count of plane intersections
     * More efficient than getting all intersections when only count is needed
     * 
     * @param plane the plane to test intersection with
     * @param octree the octree to search
     * @param config parallel execution configuration
     * @return number of cubes intersecting the plane
     */
    public static <Content> long countPlaneIntersectionsParallel(
            Plane3D plane, Octree<Content> octree, ParallelConfig config) {
        
        Map<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return PlaneIntersectionSearch.countPlaneIntersections(plane, octree);
        }
        
        return executeInPool(config, () -> {
            return map.entrySet().parallelStream()
                .mapToLong(entry -> {
                    Spatial.Cube cube = Octree.toCube(entry.getKey());
                    return plane.intersectsCube(cube) ? 1 : 0;
                })
                .sum();
        });
    }

    /**
     * Parallel test for any plane intersection
     * Short-circuits on first intersection found for efficiency
     * 
     * @param plane the plane to test intersection with
     * @param octree the octree to search
     * @param config parallel execution configuration
     * @return true if any cube intersects the plane
     */
    public static <Content> boolean hasAnyIntersectionParallel(
            Plane3D plane, Octree<Content> octree, ParallelConfig config) {
        
        Map<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return PlaneIntersectionSearch.hasAnyIntersection(plane, octree);
        }
        
        return executeInPool(config, () -> {
            return map.entrySet().parallelStream()
                .anyMatch(entry -> {
                    Spatial.Cube cube = Octree.toCube(entry.getKey());
                    return plane.intersectsCube(cube);
                });
        });
    }

    /**
     * Parallel search for cubes on positive side of plane
     * 
     * @param plane the plane to test against
     * @param octree the octree to search
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param config parallel execution configuration
     * @return list of intersections on positive side, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<PlaneIntersectionSearch.PlaneIntersection<Content>> cubesOnPositiveSideParallel(
            Plane3D plane, Octree<Content> octree, Point3f referencePoint, ParallelConfig config) {
        
        Map<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return PlaneIntersectionSearch.cubesOnPositiveSide(plane, octree, referencePoint);
        }
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        return executeInPool(config, () -> {
            List<PlaneIntersectionSearch.PlaneIntersection<Content>> results = 
                map.entrySet().parallelStream()
                    .map(entry -> {
                        Spatial.Cube cube = Octree.toCube(entry.getKey());
                        Point3f cubeCenter = getCubeCenter(cube);
                        
                        // Check if cube center is on positive side of plane
                        if (plane.distanceToPoint(cubeCenter) > 0) {
                            float distance = calculateDistance(referencePoint, cubeCenter);
                            
                            return new PlaneIntersectionSearch.PlaneIntersection<>(
                                entry.getKey(), 
                                entry.getValue(), 
                                cube, 
                                distance,
                                cubeCenter
                            );
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
            // Sort by distance from reference point
            results.sort(Comparator.comparing(pi -> pi.distanceToReferencePoint));
            return results;
        });
    }

    /**
     * Parallel search for cubes on negative side of plane
     * 
     * @param plane the plane to test against
     * @param octree the octree to search
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param config parallel execution configuration
     * @return list of intersections on negative side, sorted by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<PlaneIntersectionSearch.PlaneIntersection<Content>> cubesOnNegativeSideParallel(
            Plane3D plane, Octree<Content> octree, Point3f referencePoint, ParallelConfig config) {
        
        Map<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return PlaneIntersectionSearch.cubesOnNegativeSide(plane, octree, referencePoint);
        }
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        return executeInPool(config, () -> {
            List<PlaneIntersectionSearch.PlaneIntersection<Content>> results = 
                map.entrySet().parallelStream()
                    .map(entry -> {
                        Spatial.Cube cube = Octree.toCube(entry.getKey());
                        Point3f cubeCenter = getCubeCenter(cube);
                        
                        // Check if cube center is on negative side of plane
                        if (plane.distanceToPoint(cubeCenter) < 0) {
                            float distance = calculateDistance(referencePoint, cubeCenter);
                            
                            return new PlaneIntersectionSearch.PlaneIntersection<>(
                                entry.getKey(), 
                                entry.getValue(), 
                                cube, 
                                distance,
                                cubeCenter
                            );
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
            // Sort by distance from reference point
            results.sort(Comparator.comparing(pi -> pi.distanceToReferencePoint));
            return results;
        });
    }

    /**
     * Batch processing for multiple plane intersection queries
     * Processes multiple plane intersection queries in parallel
     * 
     * @param planes list of planes to test intersection with
     * @param octree the octree to search
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param config parallel execution configuration
     * @return map of planes to their intersection results
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> Map<Plane3D, List<PlaneIntersectionSearch.PlaneIntersection<Content>>> 
            batchPlaneIntersections(List<Plane3D> planes, Octree<Content> octree, 
                                  Point3f referencePoint, ParallelConfig config) {
        
        if (planes.size() < config.parallelismThreshold / 10) {
            // Use sequential processing for small batch sizes
            return planes.stream()
                .collect(Collectors.toMap(
                    plane -> plane,
                    plane -> PlaneIntersectionSearch.planeIntersectedAll(plane, octree, referencePoint)
                ));
        }
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        return executeInPool(config, () -> {
            return planes.parallelStream()
                .collect(Collectors.toConcurrentMap(
                    plane -> plane,
                    plane -> planeIntersectedAllParallel(plane, octree, referencePoint, config)
                ));
        });
    }

    /**
     * Parallel frustum culling search
     * Processes frustum-cube intersection tests in parallel
     * 
     * @param frustum the camera frustum to test intersection with
     * @param octree the octree to search
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @param config parallel execution configuration
     * @return list of intersections ordered by distance from camera
     * @throws IllegalArgumentException if camera position has negative coordinates
     */
    public static <Content> List<FrustumCullingSearch.FrustumIntersection<Content>> frustumCulledAllParallel(
            Frustum3D frustum, Octree<Content> octree, Point3f cameraPosition, ParallelConfig config) {
        
        Map<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return FrustumCullingSearch.frustumCulledAll(frustum, octree, cameraPosition);
        }
        
        validatePositiveCoordinates(cameraPosition, "cameraPosition");
        
        return executeInPool(config, () -> {
            List<FrustumCullingSearch.FrustumIntersection<Content>> intersections = 
                map.entrySet().parallelStream()
                    .map(entry -> {
                        Spatial.Cube cube = Octree.toCube(entry.getKey());
                        FrustumCullingSearch.CullingResult result = testFrustumCulling(frustum, cube);
                        
                        if (result != FrustumCullingSearch.CullingResult.OUTSIDE) {
                            Point3f cubeCenter = getCubeCenter(cube);
                            float distance = calculateDistance(cameraPosition, cubeCenter);
                            
                            return new FrustumCullingSearch.FrustumIntersection<>(
                                entry.getKey(), 
                                entry.getValue(), 
                                cube, 
                                distance,
                                cubeCenter,
                                result
                            );
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
            // Sort by distance from camera
            intersections.sort(Comparator.comparing(fi -> fi.distanceToCamera));
            return intersections;
        });
    }

    /**
     * Parallel count of frustum intersections
     * More efficient than getting all intersections when only count is needed
     * 
     * @param frustum the camera frustum to test intersection with
     * @param octree the octree to search
     * @param config parallel execution configuration
     * @return number of cubes intersecting the frustum
     */
    public static <Content> long countFrustumIntersectionsParallel(
            Frustum3D frustum, Octree<Content> octree, ParallelConfig config) {
        
        Map<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return FrustumCullingSearch.countFrustumIntersections(frustum, octree);
        }
        
        return executeInPool(config, () -> {
            return map.entrySet().parallelStream()
                .mapToLong(entry -> {
                    Spatial.Cube cube = Octree.toCube(entry.getKey());
                    FrustumCullingSearch.CullingResult result = testFrustumCulling(frustum, cube);
                    return result != FrustumCullingSearch.CullingResult.OUTSIDE ? 1 : 0;
                })
                .sum();
        });
    }

    /**
     * Batch processing for multiple frustum culling queries
     * Processes multiple frustum culling queries in parallel
     * 
     * @param frustums list of frustums to test
     * @param octree the octree to search
     * @param cameraPosition camera position for distance calculations (positive coordinates only)
     * @param config parallel execution configuration
     * @return map of frustums to their intersection results
     * @throws IllegalArgumentException if camera position has negative coordinates
     */
    public static <Content> Map<Frustum3D, List<FrustumCullingSearch.FrustumIntersection<Content>>> 
            batchFrustumCulling(List<Frustum3D> frustums, Octree<Content> octree, 
                               Point3f cameraPosition, ParallelConfig config) {
        
        if (frustums.size() < config.parallelismThreshold / 10) {
            // Use sequential processing for small batch sizes
            return frustums.stream()
                .collect(Collectors.toMap(
                    frustum -> frustum,
                    frustum -> FrustumCullingSearch.frustumCulledAll(frustum, octree, cameraPosition)
                ));
        }
        
        validatePositiveCoordinates(cameraPosition, "cameraPosition");
        
        return executeInPool(config, () -> {
            return frustums.parallelStream()
                .collect(Collectors.toConcurrentMap(
                    frustum -> frustum,
                    frustum -> frustumCulledAllParallel(frustum, octree, cameraPosition, config)
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
    
    /**
     * Test frustum culling for a single cube
     * 
     * @param frustum the camera frustum
     * @param cube the cube to test
     * @return culling result (INSIDE, INTERSECTING, or OUTSIDE)
     */
    private static FrustumCullingSearch.CullingResult testFrustumCulling(Frustum3D frustum, Spatial.Cube cube) {
        // Test if cube is completely inside frustum
        if (frustum.containsCube(cube)) {
            return FrustumCullingSearch.CullingResult.INSIDE;
        }
        
        // Test if cube intersects frustum
        if (frustum.intersectsCube(cube)) {
            return FrustumCullingSearch.CullingResult.INTERSECTING;
        }
        
        // Cube is completely outside
        return FrustumCullingSearch.CullingResult.OUTSIDE;
    }
    
    /**
     * Calculate Euclidean distance between two points
     */
    private static float calculateDistance(Point3f p1, Point3f p2) {
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        float dz = p1.z - p2.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    /**
     * Validate that all coordinates in a point are positive
     */
    private static void validatePositiveCoordinates(Point3f point, String paramName) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException(paramName + " coordinates must be positive, got: " + point);
        }
    }
    
    /**
     * Parallel sphere intersection search
     * Processes sphere-cube intersection tests in parallel
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param octree the octree to search
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param config parallel execution configuration
     * @return list of intersections ordered by distance from reference point
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> List<SphereIntersectionSearch.SphereIntersection<Content>> sphereIntersectedAllParallel(
            Point3f sphereCenter, float sphereRadius, Octree<Content> octree, Point3f referencePoint, ParallelConfig config) {
        
        Map<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return SphereIntersectionSearch.sphereIntersectedAll(sphereCenter, sphereRadius, octree, referencePoint);
        }
        
        validatePositiveCoordinates(sphereCenter, "sphereCenter");
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }
        
        return executeInPool(config, () -> {
            List<SphereIntersectionSearch.SphereIntersection<Content>> intersections = 
                map.entrySet().parallelStream()
                    .map(entry -> {
                        Spatial.Cube cube = Octree.toCube(entry.getKey());
                        SphereIntersectionSearch.IntersectionType intersectionType = testSphereIntersection(sphereCenter, sphereRadius, cube);
                        
                        if (intersectionType != SphereIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE) {
                            Point3f cubeCenter = getCubeCenter(cube);
                            float distance = calculateDistance(referencePoint, cubeCenter);
                            
                            return new SphereIntersectionSearch.SphereIntersection<>(
                                entry.getKey(), 
                                entry.getValue(), 
                                cube, 
                                distance,
                                cubeCenter,
                                intersectionType
                            );
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
            // Sort by distance from reference point
            intersections.sort(Comparator.comparing(si -> si.distanceToReferencePoint));
            return intersections;
        });
    }

    /**
     * Parallel count of sphere intersections
     * More efficient than getting all intersections when only count is needed
     * 
     * @param sphereCenter center of the sphere (positive coordinates only)
     * @param sphereRadius radius of the sphere (positive)
     * @param octree the octree to search
     * @param config parallel execution configuration
     * @return number of cubes intersecting the sphere
     * @throws IllegalArgumentException if any coordinate is negative or radius is non-positive
     */
    public static <Content> long countSphereIntersectionsParallel(
            Point3f sphereCenter, float sphereRadius, Octree<Content> octree, ParallelConfig config) {
        
        Map<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return SphereIntersectionSearch.countSphereIntersections(sphereCenter, sphereRadius, octree);
        }
        
        validatePositiveCoordinates(sphereCenter, "sphereCenter");
        
        if (sphereRadius <= 0) {
            throw new IllegalArgumentException("Sphere radius must be positive, got: " + sphereRadius);
        }
        
        return executeInPool(config, () -> {
            return map.entrySet().parallelStream()
                .mapToLong(entry -> {
                    Spatial.Cube cube = Octree.toCube(entry.getKey());
                    SphereIntersectionSearch.IntersectionType intersectionType = testSphereIntersection(sphereCenter, sphereRadius, cube);
                    return intersectionType != SphereIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE ? 1 : 0;
                })
                .sum();
        });
    }

    /**
     * Parallel AABB intersection search
     * Processes AABB-cube intersection tests in parallel
     * 
     * @param aabb the axis-aligned bounding box to test intersection with
     * @param octree the octree to search
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param config parallel execution configuration
     * @return list of intersections ordered by distance from reference point
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> List<AABBIntersectionSearch.AABBIntersection<Content>> aabbIntersectedAllParallel(
            AABBIntersectionSearch.AABB aabb, Octree<Content> octree, Point3f referencePoint, ParallelConfig config) {
        
        Map<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return AABBIntersectionSearch.aabbIntersectedAll(aabb, octree, referencePoint);
        }
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        return executeInPool(config, () -> {
            List<AABBIntersectionSearch.AABBIntersection<Content>> intersections = 
                map.entrySet().parallelStream()
                    .map(entry -> {
                        Spatial.Cube cube = Octree.toCube(entry.getKey());
                        AABBIntersectionSearch.IntersectionType intersectionType = testAABBIntersection(aabb, cube);
                        
                        if (intersectionType != AABBIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE) {
                            Point3f cubeCenter = getCubeCenter(cube);
                            float distance = calculateDistance(referencePoint, cubeCenter);
                            
                            return new AABBIntersectionSearch.AABBIntersection<>(
                                entry.getKey(), 
                                entry.getValue(), 
                                cube, 
                                distance,
                                cubeCenter,
                                intersectionType
                            );
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
            // Sort by distance from reference point
            intersections.sort(Comparator.comparing(ai -> ai.distanceToReferencePoint));
            return intersections;
        });
    }

    /**
     * Parallel count of AABB intersections
     * More efficient than getting all intersections when only count is needed
     * 
     * @param aabb the axis-aligned bounding box to test intersection with
     * @param octree the octree to search
     * @param config parallel execution configuration
     * @return number of cubes intersecting the AABB
     */
    public static <Content> long countAABBIntersectionsParallel(
            AABBIntersectionSearch.AABB aabb, Octree<Content> octree, ParallelConfig config) {
        
        Map<Long, Content> map = octree.getMap();
        if (map.size() < config.parallelismThreshold) {
            // Use sequential version for small datasets
            return AABBIntersectionSearch.countAABBIntersections(aabb, octree);
        }
        
        return executeInPool(config, () -> {
            return map.entrySet().parallelStream()
                .mapToLong(entry -> {
                    Spatial.Cube cube = Octree.toCube(entry.getKey());
                    AABBIntersectionSearch.IntersectionType intersectionType = testAABBIntersection(aabb, cube);
                    return intersectionType != AABBIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE ? 1 : 0;
                })
                .sum();
        });
    }

    /**
     * Batch processing for multiple sphere intersection queries
     * Processes multiple sphere intersection queries in parallel
     * 
     * @param sphereQueries list of sphere queries to test
     * @param octree the octree to search
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param config parallel execution configuration
     * @return map of sphere queries to their intersection results
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> Map<SphereIntersectionSearch.SphereQuery, List<SphereIntersectionSearch.SphereIntersection<Content>>> 
            batchSphereIntersections(List<SphereIntersectionSearch.SphereQuery> sphereQueries, Octree<Content> octree, 
                                    Point3f referencePoint, ParallelConfig config) {
        
        if (sphereQueries.size() < config.parallelismThreshold / 10) {
            // Use sequential processing for small batch sizes
            return SphereIntersectionSearch.batchSphereIntersections(sphereQueries, octree, referencePoint);
        }
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        return executeInPool(config, () -> {
            return sphereQueries.parallelStream()
                .collect(Collectors.toConcurrentMap(
                    query -> query,
                    query -> sphereIntersectedAllParallel(query.center, query.radius, octree, referencePoint, config)
                ));
        });
    }

    /**
     * Batch processing for multiple AABB intersection queries
     * Processes multiple AABB intersection queries in parallel
     * 
     * @param aabbs list of AABBs to test
     * @param octree the octree to search
     * @param referencePoint reference point for distance calculations (positive coordinates only)
     * @param config parallel execution configuration
     * @return map of AABBs to their intersection results
     * @throws IllegalArgumentException if reference point has negative coordinates
     */
    public static <Content> Map<AABBIntersectionSearch.AABB, List<AABBIntersectionSearch.AABBIntersection<Content>>> 
            batchAABBIntersections(List<AABBIntersectionSearch.AABB> aabbs, Octree<Content> octree, 
                                  Point3f referencePoint, ParallelConfig config) {
        
        if (aabbs.size() < config.parallelismThreshold / 10) {
            // Use sequential processing for small batch sizes
            return AABBIntersectionSearch.batchAABBIntersections(aabbs, octree, referencePoint);
        }
        
        validatePositiveCoordinates(referencePoint, "referencePoint");
        
        return executeInPool(config, () -> {
            return aabbs.parallelStream()
                .collect(Collectors.toConcurrentMap(
                    aabb -> aabb,
                    aabb -> aabbIntersectedAllParallel(aabb, octree, referencePoint, config)
                ));
        });
    }

    /**
     * Test sphere-cube intersection using optimized sphere-AABB intersection algorithm
     * Based on "Real-Time Rendering" by Akenine-Möller, Haines, and Hoffman
     * 
     * @param sphereCenter center of the sphere
     * @param sphereRadius radius of the sphere
     * @param cube the cube to test
     * @return intersection type
     */
    private static SphereIntersectionSearch.IntersectionType testSphereIntersection(Point3f sphereCenter, float sphereRadius, Spatial.Cube cube) {
        float cubeMinX = cube.originX();
        float cubeMinY = cube.originY();
        float cubeMinZ = cube.originZ();
        float cubeMaxX = cube.originX() + cube.extent();
        float cubeMaxY = cube.originY() + cube.extent();
        float cubeMaxZ = cube.originZ() + cube.extent();
        
        // Calculate squared distance from sphere center to closest point on cube
        float dx = Math.max(0, Math.max(cubeMinX - sphereCenter.x, sphereCenter.x - cubeMaxX));
        float dy = Math.max(0, Math.max(cubeMinY - sphereCenter.y, sphereCenter.y - cubeMaxY));
        float dz = Math.max(0, Math.max(cubeMinZ - sphereCenter.z, sphereCenter.z - cubeMaxZ));
        
        float distanceSquared = dx * dx + dy * dy + dz * dz;
        float radiusSquared = sphereRadius * sphereRadius;
        
        // No intersection if distance to closest point > radius
        if (distanceSquared > radiusSquared) {
            return SphereIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE;
        }
        
        // Check if cube is completely inside sphere
        // Calculate distance from sphere center to farthest corner of cube
        float farthestDx = Math.max(Math.abs(cubeMinX - sphereCenter.x), Math.abs(cubeMaxX - sphereCenter.x));
        float farthestDy = Math.max(Math.abs(cubeMinY - sphereCenter.y), Math.abs(cubeMaxY - sphereCenter.y));
        float farthestDz = Math.max(Math.abs(cubeMinZ - sphereCenter.z), Math.abs(cubeMaxZ - sphereCenter.z));
        
        float farthestDistanceSquared = farthestDx * farthestDx + farthestDy * farthestDy + farthestDz * farthestDz;
        
        if (farthestDistanceSquared <= radiusSquared) {
            return SphereIntersectionSearch.IntersectionType.COMPLETELY_INSIDE;
        }
        
        // Cube partially intersects sphere
        return SphereIntersectionSearch.IntersectionType.INTERSECTING;
    }

    /**
     * Test AABB-cube intersection using standard AABB-AABB intersection algorithm
     * 
     * @param aabb the axis-aligned bounding box
     * @param cube the cube to test
     * @return intersection type
     */
    private static AABBIntersectionSearch.IntersectionType testAABBIntersection(AABBIntersectionSearch.AABB aabb, Spatial.Cube cube) {
        float cubeMinX = cube.originX();
        float cubeMinY = cube.originY();
        float cubeMinZ = cube.originZ();
        float cubeMaxX = cube.originX() + cube.extent();
        float cubeMaxY = cube.originY() + cube.extent();
        float cubeMaxZ = cube.originZ() + cube.extent();
        
        // Check for no intersection (separating axis test)
        if (cubeMaxX < aabb.minX || cubeMinX > aabb.maxX ||
            cubeMaxY < aabb.minY || cubeMinY > aabb.maxY ||
            cubeMaxZ < aabb.minZ || cubeMinZ > aabb.maxZ) {
            return AABBIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE;
        }
        
        // Check if cube completely contains AABB
        if (cubeMinX <= aabb.minX && cubeMaxX >= aabb.maxX &&
            cubeMinY <= aabb.minY && cubeMaxY >= aabb.maxY &&
            cubeMinZ <= aabb.minZ && cubeMaxZ >= aabb.maxZ) {
            return AABBIntersectionSearch.IntersectionType.CONTAINS_AABB;
        }
        
        // Check if cube is completely inside AABB
        if (cubeMinX >= aabb.minX && cubeMaxX <= aabb.maxX &&
            cubeMinY >= aabb.minY && cubeMaxY <= aabb.maxY &&
            cubeMinZ >= aabb.minZ && cubeMaxZ <= aabb.maxZ) {
            return AABBIntersectionSearch.IntersectionType.COMPLETELY_INSIDE;
        }
        
        // Cube partially intersects AABB
        return AABBIntersectionSearch.IntersectionType.INTERSECTING;
    }
    
    // Helper record for volume bounds
    private record VolumeBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    }
}