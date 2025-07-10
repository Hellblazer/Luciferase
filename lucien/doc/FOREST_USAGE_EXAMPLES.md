# Forest Usage Examples for Lucien

## Introduction

This document provides practical examples of how to use the forest functionality in Lucien for various spatial indexing scenarios.

## Example 1: City-Scale Simulation

Simulating a city with multiple districts, each as a separate tree:

```java
// Create a forest for a city simulation
ForestConfig config = new ForestConfig()
    .withGhostZones(true, 10.0f)  // 10 meter ghost zones
    .withOverlappingTrees(false);

Forest<MortonKey, Long, Building> cityForest = new Forest<>(config);

// Add trees for different districts
// Downtown - high detail
Vector3f downtownOrigin = new Vector3f(0, 0, 0);
Vector3f downtownSize = new Vector3f(2000, 2000, 500);
Octree downtownTree = new Octree(downtownOrigin, downtownSize, 15); // max level 15
cityForest.addTree(downtownTree, new BoundingBox(downtownOrigin, downtownSize));

// Residential area - medium detail
Vector3f residentialOrigin = new Vector3f(2100, 0, 0);
Vector3f residentialSize = new Vector3f(5000, 5000, 200);
Octree residentialTree = new Octree(residentialOrigin, residentialSize, 12); // max level 12
cityForest.addTree(residentialTree, new BoundingBox(residentialOrigin, residentialSize));

// Industrial zone - lower detail
Vector3f industrialOrigin = new Vector3f(0, 2100, 0);
Vector3f industrialSize = new Vector3f(3000, 3000, 100);
Octree industrialTree = new Octree(industrialOrigin, industrialSize, 10); // max level 10
cityForest.addTree(industrialTree, new BoundingBox(industrialOrigin, industrialSize));

// Add buildings
cityForest.insert(buildingId1, new Vector3f(500, 500, 0), downtownSkyscraper);
cityForest.insert(buildingId2, new Vector3f(3000, 1000, 0), residentialHouse);
cityForest.insert(buildingId3, new Vector3f(1000, 3000, 0), factory);

// Query across entire city
List<Building> nearbyBuildings = cityForest.findWithinDistance(
    new Vector3f(2050, 1050, 50), 100.0f);
```

## Example 2: Multi-Resolution Ocean Simulation

Ocean simulation with different resolutions for different depths:

```java
// Create a forest for ocean simulation with depth-based resolution
Forest<TetreeKey, UUID, WaterParticle> oceanForest = new Forest<>();

// Surface layer - high resolution for wave simulation
Vector3f surfaceOrigin = new Vector3f(0, 0, -10);
Vector3f surfaceSize = new Vector3f(10000, 10000, 10);
Tetree surfaceLayer = new Tetree(surfaceOrigin, surfaceSize, 16);
oceanForest.addTree(surfaceLayer, new BoundingBox(surfaceOrigin, surfaceSize));

// Mid-water layer - medium resolution
Vector3f midOrigin = new Vector3f(0, 0, -100);
Vector3f midSize = new Vector3f(10000, 10000, 90);
Tetree midLayer = new Tetree(midOrigin, midSize, 12);
oceanForest.addTree(midLayer, new BoundingBox(midOrigin, midSize));

// Deep water - low resolution
Vector3f deepOrigin = new Vector3f(0, 0, -1000);
Vector3f deepSize = new Vector3f(10000, 10000, 900);
Tetree deepLayer = new Tetree(deepOrigin, deepSize, 8);
oceanForest.addTree(deepLayer, new BoundingBox(deepOrigin, deepSize));

// Simulate particles
for (WaterParticle particle : particles) {
    oceanForest.insert(particle.getId(), particle.getPosition(), particle);
}

// Find neighbors for pressure calculation
WaterParticle p = particles.get(0);
List<WaterParticle> neighbors = oceanForest.findKNearestNeighbors(
    p.getPosition(), 50);
```

## Example 3: Space Simulation with Multiple Celestial Bodies

Simulating space around multiple planets:

```java
// Create forest for solar system simulation
Forest<MortonKey, Long, SpaceObject> solarSystem = new Forest<>();

// Earth and near-Earth space
Vector3f earthCenter = new Vector3f(150e9f, 0, 0); // 1 AU from sun
float earthRegionSize = 1e9f; // 1 million km cube
Octree earthRegion = new Octree(
    earthCenter.sub(earthRegionSize/2), 
    new Vector3f(earthRegionSize, earthRegionSize, earthRegionSize),
    20); // Very high detail for satellites
solarSystem.addTree(earthRegion, createBoundingBox(earthCenter, earthRegionSize));

// Mars and near-Mars space
Vector3f marsCenter = new Vector3f(230e9f, 0, 0); // 1.5 AU from sun
Octree marsRegion = new Octree(
    marsCenter.sub(earthRegionSize/2),
    new Vector3f(earthRegionSize, earthRegionSize, earthRegionSize),
    18);
solarSystem.addTree(marsRegion, createBoundingBox(marsCenter, earthRegionSize));

// Asteroid belt - multiple smaller trees
for (int i = 0; i < 100; i++) {
    float angle = (float)(i * 2 * Math.PI / 100);
    float radius = 3.0e11f + (float)(Math.random() * 1e11); // 2-3 AU
    Vector3f asteroidPos = new Vector3f(
        radius * Math.cos(angle),
        radius * Math.sin(angle),
        (float)(Math.random() - 0.5) * 1e10f);
    
    Octree asteroidRegion = new Octree(
        asteroidPos.sub(1e8f),
        new Vector3f(2e8f, 2e8f, 2e8f),
        12);
    solarSystem.addTree(asteroidRegion, createBoundingBox(asteroidPos, 2e8f));
}

// Track spacecraft traveling between planets
SpaceCraft craft = new SpaceCraft();
Vector3f craftPos = craft.getPosition();

// Efficient collision detection - only checks relevant trees
List<SpaceObject> nearbyObjects = solarSystem.findWithinDistance(craftPos, 1000.0f);
```

## Example 4: Temporal Forest for Animation

Using forest to manage time-stepped animation data:

```java
// Create a temporal forest where each tree represents a time step
public class TemporalForest {
    private final Forest<MortonKey, Long, AnimatedObject> forest;
    private final Map<Float, Integer> timeToTreeMap;
    
    public TemporalForest(float startTime, float endTime, float timeStep) {
        this.forest = new Forest<>();
        this.timeToTreeMap = new HashMap<>();
        
        // Create a tree for each time step
        float currentTime = startTime;
        while (currentTime <= endTime) {
            Vector3f origin = new Vector3f(0, 0, 0);
            Vector3f size = new Vector3f(1000, 1000, 1000);
            
            Octree timeTree = new Octree(origin, size);
            int treeId = forest.addTree(timeTree, new BoundingBox(origin, size));
            timeToTreeMap.put(currentTime, treeId);
            
            currentTime += timeStep;
        }
    }
    
    // Add object state at specific time
    public void addObjectState(float time, Long objectId, 
                              Vector3f position, AnimatedObject state) {
        Integer treeId = timeToTreeMap.get(time);
        if (treeId != null) {
            forest.insertInTree(treeId, objectId, position, state);
        }
    }
    
    // Interpolate object position between time steps
    public AnimatedObject getObjectAtTime(Long objectId, float time) {
        // Find surrounding time steps
        float prevTime = findPreviousTime(time);
        float nextTime = findNextTime(time);
        
        // Get states from both trees
        AnimatedObject prevState = forest.findInTree(
            timeToTreeMap.get(prevTime), 
            obj -> obj.getId().equals(objectId)).get(0);
        AnimatedObject nextState = forest.findInTree(
            timeToTreeMap.get(nextTime),
            obj -> obj.getId().equals(objectId)).get(0);
        
        // Interpolate
        float alpha = (time - prevTime) / (nextTime - prevTime);
        return AnimatedObject.interpolate(prevState, nextState, alpha);
    }
}
```

## Example 5: Load-Balanced Particle Simulation

Dynamic load balancing across multiple trees:

```java
// Create a load-balanced forest for particle simulation
public class LoadBalancedForest {
    private final Forest<MortonKey, UUID, Particle> forest;
    private final ForestLoadBalancer<MortonKey, UUID, Particle> loadBalancer;
    
    public LoadBalancedForest(int numTrees) {
        ForestConfig config = new ForestConfig()
            .withPartitionStrategy(new UniformGridStrategy(numTrees));
        
        this.forest = GridForest.createUniformGrid(
            new Vector3f(0, 0, 0),
            new Vector3f(1000, 1000, 1000),
            numTrees, 1, 1);
        
        this.loadBalancer = new ForestLoadBalancer<>(forest);
    }
    
    public void simulateTimeStep(float deltaTime) {
        // Update particles in parallel
        forest.parallelUpdate((particle, treeId) -> {
            // Physics simulation
            particle.updateVelocity(deltaTime);
            particle.updatePosition(deltaTime);
            return particle.getPosition();
        });
        
        // Check load balance
        if (shouldRebalance()) {
            rebalance();
        }
    }
    
    private void rebalance() {
        // Get load metrics for each tree
        Map<Integer, TreeLoadMetrics> metrics = loadBalancer.getAllMetrics();
        
        // Find overloaded and underloaded trees
        List<Integer> overloaded = findOverloadedTrees(metrics);
        List<Integer> underloaded = findUnderloadedTrees(metrics);
        
        // Migrate particles
        for (int fromTree : overloaded) {
            int toTree = selectTargetTree(underloaded, metrics);
            Set<UUID> particlesToMigrate = selectParticlesToMigrate(fromTree);
            loadBalancer.migrateEntities(particlesToMigrate, fromTree, toTree);
        }
    }
}
```

## Example 6: Hybrid Forest with Mixed Tree Types

Using both Octree and Tetree in the same forest:

```java
// Create a hybrid forest for a game world
Forest<SpatialKey<?>, Long, GameObject> gameWorld = new Forest<>();

// Use Octree for regular terrain and buildings
Vector3f terrainOrigin = new Vector3f(0, 0, 0);
Vector3f terrainSize = new Vector3f(5000, 5000, 500);
Octree terrainTree = new Octree(terrainOrigin, terrainSize);
gameWorld.addTree(terrainTree, new BoundingBox(terrainOrigin, terrainSize));

// Use Tetree for underground cave system (better for irregular shapes)
Vector3f caveOrigin = new Vector3f(1000, 1000, -500);
Vector3f caveSize = new Vector3f(2000, 2000, 500);
Tetree caveTree = new Tetree(caveOrigin, caveSize);
gameWorld.addTree(caveTree, new BoundingBox(caveOrigin, caveSize));

// Use high-detail Octree for player's immediate vicinity
public void updatePlayerView(Player player) {
    Vector3f playerPos = player.getPosition();
    
    // Remove old detail tree
    if (playerDetailTreeId != -1) {
        gameWorld.removeTree(playerDetailTreeId);
    }
    
    // Add new detail tree around player
    Vector3f detailOrigin = playerPos.sub(50, 50, 50);
    Vector3f detailSize = new Vector3f(100, 100, 100);
    Octree detailTree = new Octree(detailOrigin, detailSize, 20); // Very high detail
    playerDetailTreeId = gameWorld.addTree(detailTree, 
        new BoundingBox(detailOrigin, detailSize));
    
    // Migrate nearby objects to detail tree
    List<GameObject> nearbyObjects = gameWorld.findWithinDistance(playerPos, 100);
    for (GameObject obj : nearbyObjects) {
        gameWorld.migrateEntity(obj.getId(), playerDetailTreeId);
    }
}
```

## Best Practices

1. **Tree Sizing**: Balance between too many small trees (overhead) and too few large trees (less parallelism)

2. **Ghost Zones**: Use ghost zones for simulations requiring neighbor interactions across tree boundaries

3. **Load Balancing**: Monitor tree loads and rebalance periodically for dynamic simulations

4. **Tree Selection**: Choose appropriate tree types (Octree vs Tetree) based on spatial characteristics

5. **Memory Management**: Remove empty trees and consolidate sparse trees to save memory

## Performance Considerations

- **Query Routing**: Spatial queries are automatically routed to relevant trees
- **Parallel Processing**: Each tree can be processed on a different thread/core
- **Cache Efficiency**: Tree-local operations have better cache locality
- **Scalability**: Forest scales to thousands of trees with minimal overhead

## Conclusion

The forest functionality in Lucien provides flexible spatial indexing for complex, large-scale applications. By managing multiple spatial index trees as a unified structure, it enables efficient handling of multi-resolution, multi-region, and dynamic spatial data.