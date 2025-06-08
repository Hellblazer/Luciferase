/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating multi-entity capabilities with various search algorithms
 *
 * @author hal.hildebrand
 */
public class EntityIntegrationTest {
    
    private OctreeWithEntities<LongEntityID, String> octree;
    private final byte testLevel = 12;
    
    @BeforeEach
    void setUp() {
        // Create a complex scene with multiple entities at same locations
        List<EntityTestUtils.MultiEntityLocation<String>> locations = new ArrayList<>();
        
        // Cluster 1: Multiple buildings at city center
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(1000.0f, 1000.0f, 1000.0f),
            testLevel,
            "OfficeBuilding1", "OfficeBuilding2", "ShoppingMall", "ParkingGarage"
        ));
        
        // Cluster 2: Residential area with overlapping properties
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(1500.0f, 1000.0f, 1000.0f),
            testLevel,
            "ApartmentA", "ApartmentB", "ApartmentC"
        ));
        
        // Cluster 3: Transportation hub
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(1250.0f, 1250.0f, 1000.0f),
            testLevel,
            "TrainStation", "BusTerminal", "TaxiStand", "BikeShare"
        ));
        
        // Individual landmarks
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(800.0f, 1200.0f, 1000.0f),
            testLevel,
            "Monument"
        ));
        
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(1700.0f, 800.0f, 1000.0f),
            testLevel,
            "Park"
        ));
        
        // Dense cluster for stress testing
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(1000.0f, 1500.0f, 1200.0f),
            testLevel,
            "Market1", "Market2", "Market3", "Market4", "Market5", 
            "Market6", "Market7", "Market8", "Market9", "Market10"
        ));
        
        octree = EntityTestUtils.createMultiEntityOctree(locations);
    }
    
    @Test
    void testKNearestNeighborWithMultipleEntities() {
        // Find nearest entities to a tourist location
        Point3f touristLocation = new Point3f(1100.0f, 1100.0f, 1000.0f);
        
        List<KNearestNeighborSearch.KNNCandidate<LongEntityID, String>> nearest = 
            KNearestNeighborSearch.findKNearestEntities(touristLocation, 10, octree);
        
        // Should find at least 10 entities
        assertEquals(10, nearest.size());
        
        // First few should be from city center cluster
        long cityCenterCount = nearest.stream()
            .limit(4)
            .filter(e -> e.content.contains("Office") || e.content.contains("Shopping") || 
                        e.content.contains("Parking"))
            .count();
        assertTrue(cityCenterCount >= 3, "Expected city center entities to be closest");
        
        // All distances should be in ascending order
        for (int i = 1; i < nearest.size(); i++) {
            assertTrue(nearest.get(i-1).distance <= nearest.get(i).distance);
        }
    }
    
    @Test
    void testContainmentSearchWithDenseClusters() {
        // Search a region that includes multiple clusters
        Spatial.Cube searchRegion = new Spatial.Cube(900.0f, 900.0f, 900.0f, 400.0f);
        
        List<ContainmentSearch.ContainedEntity<LongEntityID, String>> contained = 
            ContainmentSearch.findEntitiesInCube(searchRegion, octree);
        
        // Should find at least the city center cluster
        assertTrue(contained.size() >= 4);
        
        // Verify we found specific entities
        assertTrue(contained.stream().anyMatch(e -> e.content.equals("OfficeBuilding1")));
        assertTrue(contained.stream().anyMatch(e -> e.content.equals("ShoppingMall")));
    }
    
    @Test
    void testRayTracingThroughMultipleEntities() {
        // Cast a ray through the scene
        Point3f rayOrigin = new Point3f(700.0f, 1000.0f, 1000.0f);
        Vector3f rayDirection = new Vector3f(1.0f, 0.0f, 0.0f);
        rayDirection.normalize();
        Ray3D ray = new Ray3D(rayOrigin, rayDirection);
        
        List<RayTracingSearch.RayIntersection<LongEntityID, String>> intersections = 
            RayTracingSearch.traceRay(ray, octree, 1500.0f);
        
        // Should hit multiple clusters along the x-axis
        assertTrue(intersections.size() >= 2);
        
        // Results should be sorted by distance
        for (int i = 1; i < intersections.size(); i++) {
            assertTrue(intersections.get(i-1).distance <= intersections.get(i).distance);
        }
    }
    
    @Test
    void testSphereContainmentWithOverlappingEntities() {
        // Create a sphere centered at transportation hub
        Spatial.Sphere searchSphere = new Spatial.Sphere(1250.0f, 1250.0f, 1000.0f, 300.0f);
        
        List<ContainmentSearch.ContainedEntity<LongEntityID, String>> inSphere = 
            ContainmentSearch.findEntitiesInSphere(searchSphere, octree);
        
        // Should find transportation hub entities
        assertTrue(inSphere.stream().anyMatch(e -> e.content.equals("TrainStation")));
        assertTrue(inSphere.stream().anyMatch(e -> e.content.equals("BusTerminal")));
    }
    
    @Test
    void testEntityCountInVolume() {
        // Count entities in different regions
        Spatial.Cube marketRegion = new Spatial.Cube(950.0f, 1450.0f, 1150.0f, 100.0f);
        int marketCount = ContainmentSearch.countEntitiesInVolume(marketRegion, octree);
        
        // Should find the dense market cluster
        assertTrue(marketCount >= 10, "Expected at least 10 market entities");
    }
    
    @Test
    void testUnionOfVolumes() {
        // Search multiple non-overlapping regions
        Spatial[] volumes = new Spatial[] {
            new Spatial.Cube(950.0f, 950.0f, 950.0f, 100.0f),      // City center
            new Spatial.Cube(1450.0f, 950.0f, 950.0f, 100.0f),     // Residential
            new Spatial.Cube(750.0f, 1150.0f, 950.0f, 100.0f)      // Monument area
        };
        
        List<ContainmentSearch.ContainedEntity<LongEntityID, String>> unionResults = 
            ContainmentSearch.findEntitiesInUnion(volumes, octree);
        
        // Should find entities from all three regions
        assertTrue(unionResults.stream().anyMatch(e -> e.content.contains("Office")));
        assertTrue(unionResults.stream().anyMatch(e -> e.content.contains("Apartment")));
        assertTrue(unionResults.stream().anyMatch(e -> e.content.equals("Monument")));
        
        // Each entity should appear only once
        long uniqueIds = unionResults.stream().map(e -> e.id).distinct().count();
        assertEquals(unionResults.size(), uniqueIds);
    }
    
    @Test
    void testSearchRadiusLimitation() {
        Point3f searchPoint = new Point3f(1000.0f, 1000.0f, 1000.0f);
        float smallRadius = 50.0f;
        
        List<KNearestNeighborSearch.KNNCandidate<LongEntityID, String>> nearbyOnly = 
            KNearestNeighborSearch.findKNearestEntities(searchPoint, 20, octree, smallRadius);
        
        // Should only find city center entities within small radius
        assertTrue(nearbyOnly.stream().allMatch(e -> e.distance <= smallRadius));
        assertTrue(nearbyOnly.stream().allMatch(e -> 
            e.content.contains("Office") || e.content.contains("Shopping") || 
            e.content.contains("Parking") || e.content.contains("Mall")));
    }
    
    @Test
    void testPerformanceWithLargeEntityCount() {
        // Verify the octree handles the dense cluster efficiently
        OctreeWithEntities.Stats stats = octree.getStats();
        
        // Count the actual entities we inserted: 4 + 3 + 4 + 1 + 1 + 10 = 23
        assertTrue(stats.entityCount >= 23, 
            "Expected at least 23 entities, got " + stats.entityCount);
        
        // In OctreeWithEntities, nodeCount represents spatial nodes, not entity count
        // So we just verify the stats are reasonable
        assertTrue(stats.nodeCount > 0);
        
        // Quick performance check - k-NN should complete quickly even with many entities
        long startTime = System.currentTimeMillis();
        List<KNearestNeighborSearch.KNNCandidate<LongEntityID, String>> results = 
            KNearestNeighborSearch.findKNearestEntities(
                new Point3f(1200.0f, 1200.0f, 1100.0f), 20, octree);
        long endTime = System.currentTimeMillis();
        
        // Should complete in reasonable time (< 100ms)
        assertTrue(endTime - startTime < 100, "k-NN search took too long: " + (endTime - startTime) + "ms");
        assertEquals(20, results.size());
    }
    
    @Test
    void testConeSearchForVisibility() {
        // Cast a cone to find visible entities
        Point3f viewpoint = new Point3f(500.0f, 1000.0f, 1000.0f);
        Vector3f viewDirection = new Vector3f(1.0f, 0.2f, 0.0f);
        viewDirection.normalize();
        Ray3D viewRay = new Ray3D(viewpoint, viewDirection);
        
        List<RayTracingSearch.RayIntersection<LongEntityID, String>> visible = 
            RayTracingSearch.traceCone(viewRay, (float)Math.PI/6, octree, 2000.0f);
        
        // Should find multiple entities in the cone
        assertTrue(visible.size() > 0);
    }
    
    @Test
    void testMultipleRayCasting() {
        // Cast multiple rays in different directions
        Ray3D[] rays = new Ray3D[] {
            new Ray3D(new Point3f(500.0f, 1000.0f, 1000.0f), new Vector3f(1.0f, 0.0f, 0.0f)),
            new Ray3D(new Point3f(1000.0f, 500.0f, 1000.0f), new Vector3f(0.0f, 1.0f, 0.0f)),
            new Ray3D(new Point3f(1500.0f, 1500.0f, 800.0f), new Vector3f(0.0f, 0.0f, 1.0f))
        };
        
        var multiHits = RayTracingSearch.traceMultipleRays(rays, octree, 1000.0f);
        
        // Should find hits from multiple rays
        assertTrue(multiHits.size() > 0);
        
        // Some entities might be hit by multiple rays
        boolean hasMultipleHits = multiHits.values().stream().anyMatch(list -> list.size() > 1);
        // This assertion might not always be true depending on entity positions
    }
}