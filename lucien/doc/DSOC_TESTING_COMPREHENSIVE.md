# Dynamic Scene Occlusion Culling - Comprehensive Testing Guide

## Overview

This guide provides thorough testing strategies for validating a Dynamic Scene Occlusion Culling (DSOC) implementation, including unit tests, integration tests, performance benchmarks, and edge case validation.

## Unit Testing

### 1. Temporal Bounding Volume (TBV) Tests

#### TBV Creation Tests
```cpp
TEST(TBVCreation, BasicCreation) {
    DynamicObject obj;
    obj.position = Vector3(0, 0, 0);
    obj.velocity = Vector3(1, 0, 0);
    obj.max_velocity = 5.0f;
    
    TBV tbv = CreateTBV_Explicit(obj, 30);
    
    ASSERT_EQ(tbv.object_id, obj.id);
    ASSERT_EQ(tbv.validity_duration, 30);
    ASSERT_TRUE(tbv.bounding_volume.contains(obj.position));
    
    // Verify expansion for movement
    Vector3 max_future_pos = obj.position + obj.velocity * 30;
    ASSERT_TRUE(tbv.bounding_volume.contains(max_future_pos));
}

TEST(TBVCreation, AdaptiveExpiration) {
    // Test stationary object gets long duration
    DynamicObject stationary;
    stationary.velocity = Vector3(0, 0, 0);
    TBV tbv1 = CreateTBV_Implicit(stationary);
    ASSERT_GE(tbv1.validity_duration, 100);
    
    // Test fast object gets short duration
    DynamicObject fast;
    fast.velocity = Vector3(10, 0, 0);
    TBV tbv2 = CreateTBV_Implicit(fast);
    ASSERT_LE(tbv2.validity_duration, 20);
}

TEST(TBVCreation, BoundsValidation) {
    DynamicObject obj;
    obj.bounds = AABB(Vector3(-1,-1,-1), Vector3(1,1,1));
    
    for (int frames = 10; frames <= 100; frames += 10) {
        TBV tbv = CreateTBV_Explicit(obj, frames);
        
        // Verify TBV encompasses object bounds
        ASSERT_TRUE(tbv.bounding_volume.contains(obj.bounds));
        
        // Verify TBV grows with duration
        if (frames > 10) {
            TBV tbv_prev = CreateTBV_Explicit(obj, frames - 10);
            ASSERT_GE(tbv.bounding_volume.volume(), 
                     tbv_prev.bounding_volume.volume());
        }
    }
}
```

#### TBV Expiration Tests
```cpp
TEST(TBVExpiration, TimeBasedExpiration) {
    TBV tbv;
    tbv.validity_start = 100;
    tbv.validity_duration = 30;
    
    ASSERT_FALSE(tbv.is_expired(110));  // Not expired
    ASSERT_FALSE(tbv.is_expired(129));  // Still valid
    ASSERT_TRUE(tbv.is_expired(130));   // Expired
    ASSERT_TRUE(tbv.is_expired(150));   // Well past expiration
}

TEST(TBVExpiration, NeverExpireTBV) {
    TBV tbv;
    tbv.validity_duration = TBV::NEVER_EXPIRE;
    
    ASSERT_FALSE(tbv.is_expired(INT_MAX));
}
```

### 2. Spatial Structure Update Tests

#### Octree LCA Tests
```cpp
TEST(OctreeLCA, SameNode) {
    Octree octree(AABB(Vector3(-100), Vector3(100)));
    Vector3 pos1(10, 10, 10);
    Vector3 pos2(15, 15, 15);
    
    OctreeNode* lca = FindLCA(octree, pos1, pos2);
    ASSERT_TRUE(lca->bounds.contains(pos1));
    ASSERT_TRUE(lca->bounds.contains(pos2));
    
    // Both positions should be in same child
    ASSERT_EQ(GetChildContaining(lca, pos1), 
              GetChildContaining(lca, pos2));
}

TEST(OctreeLCA, DifferentQuadrants) {
    Octree octree(AABB(Vector3(-100), Vector3(100)));
    Vector3 pos1(-50, -50, -50);  // Bottom-left-back
    Vector3 pos2(50, 50, 50);      // Top-right-front
    
    OctreeNode* lca = FindLCA(octree, pos1, pos2);
    ASSERT_EQ(lca, octree.root);  // LCA should be root
}

TEST(OctreeLCA, MovementEfficiency) {
    Octree octree(AABB(Vector3(-1000), Vector3(1000)));
    
    // Small movement should have deep LCA
    Vector3 pos1(100, 100, 100);
    Vector3 pos2(101, 100, 100);
    OctreeNode* lca_small = FindLCA(octree, pos1, pos2);
    
    // Large movement should have shallow LCA
    Vector3 pos3(100, 100, 100);
    Vector3 pos4(900, 900, 900);
    OctreeNode* lca_large = FindLCA(octree, pos3, pos4);
    
    ASSERT_GT(GetNodeDepth(lca_small), GetNodeDepth(lca_large));
}
```

#### Update Operation Tests
```cpp
TEST(SpatialUpdate, ObjectMovement) {
    Octree octree(AABB(Vector3(-100), Vector3(100)));
    DynamicObject obj;
    obj.position = Vector3(0, 0, 0);
    
    InsertObject(octree, obj);
    OctreeNode* initial_node = FindNodeContaining(octree, obj);
    ASSERT_NE(initial_node, nullptr);
    
    // Move object
    Vector3 old_pos = obj.position;
    obj.position = Vector3(50, 50, 50);
    UpdateOctreeForMovement(octree, obj, old_pos, obj.position);
    
    // Verify object is in new location
    OctreeNode* new_node = FindNodeContaining(octree, obj);
    ASSERT_NE(new_node, nullptr);
    ASSERT_TRUE(new_node->bounds.contains(obj.position));
    
    // Verify object removed from old location
    ASSERT_FALSE(NodeContainsObject(initial_node, obj));
}
```

### 3. Visibility State Management Tests

```cpp
TEST(VisibilityState, StateTransitions) {
    DynamicObject obj;
    obj.state = VISIBLE;
    
    // Visible -> Hidden transition
    obj.state = HIDDEN_WITH_TBV;
    ASSERT_EQ(obj.state, HIDDEN_WITH_TBV);
    ASSERT_TRUE(obj.should_create_tbv());
    
    // Hidden with TBV -> Expired
    obj.active_tbv = new TBV();
    obj.active_tbv->validity_duration = 0;  // Instant expiration
    ASSERT_TRUE(obj.needs_update());
    
    // Expired -> Visible
    obj.state = VISIBLE;
    ASSERT_FALSE(obj.should_create_tbv());
}

TEST(VisibilityState, ConcurrentStateAccess) {
    // Test thread safety of state transitions
    DynamicObject obj;
    std::atomic<int> visibility_count(0);
    std::atomic<int> hidden_count(0);
    
    auto worker = [&](int thread_id) {
        for (int i = 0; i < 1000; i++) {
            if (thread_id % 2 == 0) {
                obj.set_state_safe(VISIBLE);
                visibility_count++;
            } else {
                obj.set_state_safe(HIDDEN_WITH_TBV);
                hidden_count++;
            }
        }
    };
    
    std::vector<std::thread> threads;
    for (int i = 0; i < 10; i++) {
        threads.emplace_back(worker, i);
    }
    
    for (auto& t : threads) t.join();
    
    ASSERT_EQ(visibility_count + hidden_count, 10000);
}
```

## Integration Testing

### 1. Full Pipeline Tests

```cpp
TEST(DSOCIntegration, BasicRenderLoop) {
    // Setup scene
    Scene scene;
    scene.add_static_objects(generate_room_geometry());
    
    std::vector<DynamicObject> moving_objects;
    for (int i = 0; i < 100; i++) {
        moving_objects.push_back(create_moving_chair(i));
    }
    
    Camera camera(Vector3(0, 5, 0), Vector3(0, 0, 1));
    DSOC_System dsoc(scene);
    
    // Run multiple frames
    std::vector<int> visible_counts;
    for (int frame = 0; frame < 100; frame++) {
        // Update object positions
        for (auto& obj : moving_objects) {
            obj.update_position(frame);
        }
        
        // Render frame
        auto visible = dsoc.render_frame(moving_objects, camera);
        visible_counts.push_back(visible.size());
        
        // Verify all visible objects are in view frustum
        for (auto& obj : visible) {
            ASSERT_TRUE(camera.frustum.contains(obj->bounds));
        }
    }
    
    // Verify temporal coherence
    for (int i = 1; i < visible_counts.size(); i++) {
        int change = abs(visible_counts[i] - visible_counts[i-1]);
        ASSERT_LE(change, 10);  // Gradual visibility changes
    }
}
```

### 2. TBV Integration Tests

```cpp
TEST(TBVIntegration, HiddenObjectTracking) {
    Scene scene = create_test_scene();
    DSOC_System dsoc(scene);
    
    // Place object behind wall
    DynamicObject obj;
    obj.position = Vector3(10, 0, 0);  // Behind wall at x=5
    
    Camera camera(Vector3(0, 0, 0), Vector3(1, 0, 0));
    
    // First frame - object should be hidden
    auto visible1 = dsoc.render_frame({obj}, camera);
    ASSERT_EQ(visible1.size(), 0);
    ASSERT_EQ(obj.state, HIDDEN_WITH_TBV);
    ASSERT_NE(obj.active_tbv, nullptr);
    
    // Move object but keep hidden
    obj.position = Vector3(10, 5, 0);
    auto visible2 = dsoc.render_frame({obj}, camera);
    ASSERT_EQ(visible2.size(), 0);
    
    // Verify spatial structure wasn't updated
    ASSERT_FALSE(dsoc.was_spatially_updated(obj.id));
    
    // Move object into view
    obj.position = Vector3(3, 0, 0);  // In front of wall
    auto visible3 = dsoc.render_frame({obj}, camera);
    ASSERT_EQ(visible3.size(), 1);
    ASSERT_EQ(obj.state, VISIBLE);
    ASSERT_TRUE(dsoc.was_spatially_updated(obj.id));
}
```

## Black Box Testing

### 1. Correctness Tests (Without Implementation Knowledge)

```cpp
TEST(BlackBox, VisibilityCorrectness) {
    // Test that visible objects are always rendered
    auto test_scene = [](Vector3 camera_pos, Vector3 object_pos) {
        DSOC_System dsoc;
        Camera camera(camera_pos);
        DynamicObject obj;
        obj.position = object_pos;
        
        auto visible = dsoc.render_frame({obj}, camera);
        
        // If object is in frustum and not occluded, must be visible
        if (camera.frustum.contains(obj.bounds) && 
            !is_occluded(camera_pos, object_pos)) {
            ASSERT_EQ(visible.size(), 1);
        }
    };
    
    // Test various positions
    test_scene(Vector3(0,0,0), Vector3(5,0,0));    // Front
    test_scene(Vector3(0,0,0), Vector3(-5,0,0));   // Behind
    test_scene(Vector3(0,0,0), Vector3(0,5,0));    // Above
    test_scene(Vector3(0,0,0), Vector3(0,-5,0));   // Below
}

TEST(BlackBox, TemporalConsistency) {
    DSOC_System dsoc;
    Camera static_camera(Vector3(0,0,0));
    
    // Object moving in circle
    DynamicObject obj;
    std::set<int> visible_frames;
    
    for (int frame = 0; frame < 360; frame++) {
        float angle = frame * M_PI / 180.0;
        obj.position = Vector3(cos(angle) * 10, 0, sin(angle) * 10);
        
        auto visible = dsoc.render_frame({obj}, static_camera);
        if (!visible.empty()) {
            visible_frames.insert(frame);
        }
    }
    
    // Verify visibility is continuous (no flickering)
    auto it = visible_frames.begin();
    int prev = *it++;
    while (it != visible_frames.end()) {
        int curr = *it++;
        ASSERT_LE(curr - prev, 2);  // No gaps > 2 frames
        prev = curr;
    }
}
```

### 2. Behavioral Tests

```cpp
TEST(BlackBox, MultiObjectBehavior) {
    DSOC_System dsoc;
    Camera camera(Vector3(0,10,0), Vector3(0,-1,0));  // Top-down
    
    // Create grid of objects
    std::vector<DynamicObject> objects;
    for (int x = -10; x <= 10; x += 2) {
        for (int z = -10; z <= 10; z += 2) {
            DynamicObject obj;
            obj.position = Vector3(x, 0, z);
            objects.push_back(obj);
        }
    }
    
    // All should be visible from top
    auto visible = dsoc.render_frame(objects, camera);
    ASSERT_EQ(visible.size(), objects.size());
    
    // Add occluding object
    DynamicObject occluder;
    occluder.position = Vector3(0, 5, 0);
    occluder.bounds = AABB(Vector3(-15, 4, -15), Vector3(15, 6, 15));
    objects.push_back(occluder);
    
    // Now only occluder visible
    visible = dsoc.render_frame(objects, camera);
    ASSERT_EQ(visible.size(), 1);
}
```

## White Box Testing

### 1. Algorithm-Specific Tests

```cpp
TEST(WhiteBox, LCAOptimization) {
    Octree octree(AABB(Vector3(-1000), Vector3(1000)));
    DynamicObject obj;
    
    // Instrument update function to count nodes touched
    int nodes_touched = 0;
    auto instrumented_update = [&](Octree& tree, DynamicObject& o, 
                                  Vector3 old_pos, Vector3 new_pos) {
        nodes_touched = 0;
        UpdateOctreeForMovement_Instrumented(tree, o, old_pos, 
                                           new_pos, nodes_touched);
    };
    
    // Test small movement
    obj.position = Vector3(100, 100, 100);
    InsertObject(octree, obj);
    
    Vector3 old_pos = obj.position;
    obj.position = Vector3(101, 100, 100);  // Move 1 unit
    instrumented_update(octree, obj, old_pos, obj.position);
    int small_move_nodes = nodes_touched;
    
    // Test large movement
    old_pos = obj.position;
    obj.position = Vector3(900, 900, 900);  // Move across octree
    instrumented_update(octree, obj, old_pos, obj.position);
    int large_move_nodes = nodes_touched;
    
    // Small movements should touch fewer nodes
    ASSERT_LT(small_move_nodes, large_move_nodes);
    ASSERT_LT(small_move_nodes, 10);  // Should be very localized
}

TEST(WhiteBox, TBVMerging) {
    DSOC_System dsoc;
    dsoc.config.tbv_merge_distance = 5.0f;
    
    // Create cluster of hidden objects
    std::vector<DynamicObject> objects;
    for (int i = 0; i < 10; i++) {
        DynamicObject obj;
        obj.position = Vector3(i * 0.5, 0, 0);  // Closely spaced
        obj.state = HIDDEN_WITH_TBV;
        objects.push_back(obj);
    }
    
    dsoc.create_tbvs(objects);
    
    // Verify TBVs were merged
    int tbv_count = dsoc.get_active_tbv_count();
    ASSERT_LT(tbv_count, 10);  // Should be merged
    ASSERT_GT(tbv_count, 0);   // But not all merged
}
```

### 2. Data Structure Validation

```cpp
TEST(WhiteBox, SpatialStructureIntegrity) {
    DSOC_System dsoc;
    
    // Add many objects
    std::vector<DynamicObject> objects;
    for (int i = 0; i < 1000; i++) {
        objects.push_back(create_random_object());
    }
    
    // Render several frames with movement
    for (int frame = 0; frame < 10; frame++) {
        for (auto& obj : objects) {
            obj.position += obj.velocity;
        }
        dsoc.render_frame(objects, Camera());
    }
    
    // Validate octree structure
    bool valid = dsoc.validate_spatial_structure([](OctreeNode* node) {
        // All objects in node should be within bounds
        for (auto* obj : node->dynamic_objects) {
            if (!node->bounds.contains(obj->bounds)) {
                return false;
            }
        }
        
        // All TBVs should be within bounds
        for (auto* tbv : node->temporal_volumes) {
            if (!node->bounds.contains(tbv->bounding_volume)) {
                return false;
            }
        }
        
        // Node bounds should be minimal
        AABB tight_bounds = compute_tight_bounds(node);
        float waste = node->bounds.volume() - tight_bounds.volume();
        if (waste / node->bounds.volume() > 0.5) {
            return false;  // Too much wasted space
        }
        
        return true;
    });
    
    ASSERT_TRUE(valid);
}
```

## Edge Case Testing

### 1. Extreme Movement Tests

```cpp
TEST(EdgeCases, Teleportation) {
    DSOC_System dsoc;
    DynamicObject obj;
    obj.position = Vector3(0, 0, 0);
    obj.max_velocity = 1.0f;
    
    // Normal frame
    dsoc.render_frame({obj}, Camera());
    
    // Teleport far away (violates max_velocity)
    obj.position = Vector3(1000, 1000, 1000);
    dsoc.render_frame({obj}, Camera());
    
    // System should handle gracefully
    ASSERT_TRUE(dsoc.validate_spatial_structure());
    ASSERT_NO_THROW(dsoc.render_frame({obj}, Camera()));
}

TEST(EdgeCases, ZeroVelocityMovement) {
    DSOC_System dsoc;
    DynamicObject obj;
    obj.velocity = Vector3(0, 0, 0);  // Claims to be stationary
    obj.position = Vector3(0, 0, 0);
    
    dsoc.render_frame({obj}, Camera());
    ASSERT_EQ(obj.state, HIDDEN_WITH_TBV);
    
    // But actually moves
    obj.position = Vector3(10, 0, 0);
    dsoc.render_frame({obj}, Camera());
    
    // Should still work correctly
    ASSERT_TRUE(dsoc.validate_spatial_structure());
}

TEST(EdgeCases, OscillatingObject) {
    DSOC_System dsoc;
    DynamicObject obj;
    
    // Oscillate rapidly
    for (int frame = 0; frame < 100; frame++) {
        obj.position = Vector3(sin(frame) * 10, 0, 0);
        dsoc.render_frame({obj}, Camera());
    }
    
    // TBV should eventually encompass full range
    if (obj.active_tbv) {
        ASSERT_TRUE(obj.active_tbv->bounding_volume.contains(
            Vector3(-10, 0, 0)));
        ASSERT_TRUE(obj.active_tbv->bounding_volume.contains(
            Vector3(10, 0, 0)));
    }
}
```

### 2. Boundary Condition Tests

```cpp
TEST(EdgeCases, SceneBoundaryBehavior) {
    AABB scene_bounds(Vector3(-100), Vector3(100));
    DSOC_System dsoc(scene_bounds);
    
    // Object at boundary
    DynamicObject obj1;
    obj1.position = Vector3(99.9, 0, 0);
    ASSERT_NO_THROW(dsoc.render_frame({obj1}, Camera()));
    
    // Object outside boundary
    DynamicObject obj2;
    obj2.position = Vector3(101, 0, 0);
    ASSERT_NO_THROW(dsoc.render_frame({obj2}, Camera()));
    
    // Object moving across boundary
    DynamicObject obj3;
    for (float x = 95; x < 105; x += 1) {
        obj3.position = Vector3(x, 0, 0);
        ASSERT_NO_THROW(dsoc.render_frame({obj3}, Camera()));
    }
}

TEST(EdgeCases, MassiveObjectCount) {
    DSOC_System dsoc;
    std::vector<DynamicObject> objects;
    
    // Create more objects than reasonable
    for (int i = 0; i < 100000; i++) {
        DynamicObject obj;
        obj.position = random_position();
        objects.push_back(obj);
    }
    
    // Should handle gracefully (possibly with degraded performance)
    auto start = std::chrono::high_resolution_clock::now();
    dsoc.render_frame(objects, Camera());
    auto end = std::chrono::high_resolution_clock::now();
    
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>
                   (end - start).count();
    
    // Should complete in reasonable time even with many objects
    ASSERT_LT(duration, 1000);  // Less than 1 second
}
```

### 3. Numerical Precision Tests

```cpp
TEST(EdgeCases, FloatingPointPrecision) {
    DSOC_System dsoc;
    
    // Very small movements
    DynamicObject obj;
    obj.position = Vector3(0, 0, 0);
    
    for (int i = 0; i < 1000; i++) {
        obj.position += Vector3(1e-6, 1e-6, 1e-6);
        dsoc.render_frame({obj}, Camera());
    }
    
    // Accumulated error should not break system
    ASSERT_TRUE(dsoc.validate_spatial_structure());
    
    // Very large coordinates
    obj.position = Vector3(1e6, 1e6, 1e6);
    ASSERT_NO_THROW(dsoc.render_frame({obj}, Camera()));
}
```

## Performance Testing

### 1. Benchmark Configuration

```cpp
struct BenchmarkScene {
    int static_object_count;
    int dynamic_object_count;
    int visible_dynamic_count;
    float occlusion_ratio;  // 0.0 = no occlusion, 1.0 = full occlusion
    
    Scene generate() {
        // Implementation based on paper's test methodology
        Scene scene;
        
        // Add interconnected rooms (static geometry)
        for (int i = 0; i < static_object_count; i++) {
            scene.add_static(generate_room_with_furniture());
        }
        
        // Add dynamic objects
        int hidden_count = dynamic_object_count - visible_dynamic_count;
        
        // Place visible objects
        for (int i = 0; i < visible_dynamic_count; i++) {
            scene.add_dynamic(place_in_visible_area());
        }
        
        // Place hidden objects
        for (int i = 0; i < hidden_count; i++) {
            scene.add_dynamic(place_in_occluded_area());
        }
        
        return scene;
    }
};
```

### 2. Performance Test Suite

```cpp
TEST(Performance, ScalabilityWithHiddenObjects) {
    // Based on paper's Figure 12c and 13b
    const int STATIC_POLYGONS = 16250;
    const int VISIBLE_DYNAMIC = 2;
    
    std::vector<int> hidden_counts = {0, 10, 20, 40, 60, 80, 100};
    std::map<std::string, std::vector<double>> results;
    
    for (int hidden : hidden_counts) {
        BenchmarkScene config{
            .static_object_count = STATIC_POLYGONS,
            .dynamic_object_count = VISIBLE_DYNAMIC + hidden,
            .visible_dynamic_count = VISIBLE_DYNAMIC,
            .occlusion_ratio = 0.8f
        };
        
        Scene scene = config.generate();
        
        // Test each algorithm
        results["ZB"].push_back(benchmark_z_buffer(scene));
        results["HZB"].push_back(benchmark_hierarchical_z_buffer(scene));
        results["TBVe"].push_back(benchmark_tbv_explicit(scene));
        results["TBVi"].push_back(benchmark_tbv_implicit(scene));
    }
    
    // Verify DSOC maintains constant performance
    auto tbv_times = results["TBVe"];
    double variance = calculate_variance(tbv_times);
    ASSERT_LT(variance, 0.1);  // Low variance = constant performance
    
    // Verify DSOC outperforms naive approaches
    for (size_t i = hidden_counts.size() / 2; i < hidden_counts.size(); i++) {
        ASSERT_LT(results["TBVe"][i], results["ZB"][i]);
        ASSERT_LT(results["TBVe"][i], results["HZB"][i]);
    }
}

TEST(Performance, FrameRateAnalysis) {
    // Test maintaining interactive frame rates
    Scene complex_scene = generate_complex_scene(
        100000,  // static polygons
        1000     // dynamic objects
    );
    
    DSOC_System dsoc;
    
    int frames_rendered = 0;
    auto start = std::chrono::steady_clock::now();
    
    while (std::chrono::steady_clock::now() - start < 
           std::chrono::seconds(1)) {
        update_dynamic_objects(complex_scene);
        dsoc.render_frame(complex_scene, Camera());
        frames_rendered++;
    }
    
    // Should maintain interactive frame rate
    ASSERT_GE(frames_rendered, 10);  // At least 10 FPS
}
```

### 3. Comparative Performance Tests

```cpp
TEST(Performance, LCAUpdateEfficiency) {
    // Based on paper's test with moving cube
    Octree octree(AABB(Vector3(-1000), Vector3(1000)));
    
    struct MeasuredUpdate {
        float movement_distance;
        double update_time;
        int nodes_touched;
    };
    
    std::vector<MeasuredUpdate> measurements;
    
    // Test various movement distances
    for (float dist = 0.1f; dist <= 100.0f; dist *= 2) {
        DynamicObject obj;
        obj.position = Vector3(0, 0, 0);
        InsertObject(octree, obj);
        
        Vector3 new_pos = obj.position + Vector3(dist, 0, 0);
        
        auto start = std::chrono::high_resolution_clock::now();
        int nodes = UpdateWithLCA(octree, obj, obj.position, new_pos);
        auto end = std::chrono::high_resolution_clock::now();
        
        measurements.push_back({
            dist,
            std::chrono::duration<double>(end - start).count(),
            nodes
        });
    }
    
    // Verify logarithmic relationship
    for (size_t i = 1; i < measurements.size(); i++) {
        double dist_ratio = measurements[i].movement_distance / 
                           measurements[i-1].movement_distance;
        double time_ratio = measurements[i].update_time / 
                           measurements[i-1].update_time;
        
        // Time should grow slower than distance
        ASSERT_LT(time_ratio, dist_ratio);
    }
}
```

### 4. Memory Performance Tests

```cpp
TEST(Performance, MemoryUsage) {
    DSOC_System dsoc;
    
    // Baseline memory
    size_t baseline = get_memory_usage();
    
    // Add many objects
    std::vector<DynamicObject> objects;
    for (int i = 0; i < 10000; i++) {
        objects.push_back(create_random_object());
    }
    
    // Run several frames to create TBVs
    for (int frame = 0; frame < 100; frame++) {
        dsoc.render_frame(objects, Camera());
    }
    
    size_t peak_memory = get_memory_usage();
    size_t memory_per_object = (peak_memory - baseline) / objects.size();
    
    // Verify reasonable memory usage
    ASSERT_LT(memory_per_object, 1024);  // Less than 1KB per object
    
    // Test TBV pooling effectiveness
    size_t before_clear = get_memory_usage();
    dsoc.clear_all_tbvs();
    size_t after_clear = get_memory_usage();
    
    // Memory should be retained in pools
    ASSERT_GT(after_clear, baseline + (before_clear - baseline) * 0.5);
}
```

## Stress Testing

```cpp
TEST(StressTest, ContinuousOperation) {
    DSOC_System dsoc;
    std::random_device rd;
    std::mt19937 gen(rd());
    
    // Run for extended period with random operations
    for (int iteration = 0; iteration < 10000; iteration++) {
        std::vector<DynamicObject> objects;
        
        // Random number of objects
        int count = std::uniform_int_distribution<>(1, 1000)(gen);
        for (int i = 0; i < count; i++) {
            objects.push_back(create_random_object());
        }
        
        // Random camera position
        Camera camera(random_position(), random_direction());
        
        // Should never crash or corrupt
        ASSERT_NO_THROW(dsoc.render_frame(objects, camera));
        
        // Periodic validation
        if (iteration % 100 == 0) {
            ASSERT_TRUE(dsoc.validate_spatial_structure());
        }
    }
}

TEST(StressTest, WorstCaseScenario) {
    DSOC_System dsoc;
    
    // All objects visible and moving rapidly
    std::vector<DynamicObject> objects;
    for (int i = 0; i < 1000; i++) {
        DynamicObject obj;
        obj.position = Vector3(i * 0.1f, 0, 0);
        obj.velocity = Vector3(
            random_float(-10, 10),
            random_float(-10, 10),
            random_float(-10, 10)
        );
        objects.push_back(obj);
    }
    
    // Camera that sees everything
    Camera camera;
    camera.far_plane = 10000.0f;
    
    // Should still complete in reasonable time
    auto start = std::chrono::steady_clock::now();
    
    for (int frame = 0; frame < 100; frame++) {
        for (auto& obj : objects) {
            obj.position += obj.velocity;
        }
        dsoc.render_frame(objects, camera);
    }
    
    auto duration = std::chrono::steady_clock::now() - start;
    ASSERT_LT(duration, std::chrono::seconds(10));
}
```

## Regression Testing

```cpp
class DSOCRegressionTest : public ::testing::Test {
protected:
    void SetUp() override {
        // Load golden results from paper's experiments
        load_golden_results("dsoc_golden_results.json");
    }
    
    void verify_against_golden(const std::string& test_name, 
                              const TestResults& results) {
        auto golden = golden_results[test_name];
        
        // Allow 10% deviation from golden results
        ASSERT_NEAR(results.avg_frame_time, golden.avg_frame_time, 
                   golden.avg_frame_time * 0.1);
        ASSERT_NEAR(results.visible_object_count, golden.visible_object_count,
                   golden.visible_object_count * 0.1);
    }
};

TEST_F(DSOCRegressionTest, PaperTestScene1) {
    // Recreate paper's test scene 1: cube in cube
    Scene scene = create_cube_in_cube_scene();
    DSOC_System dsoc;
    
    TestResults results = run_movement_test(scene, dsoc);
    verify_against_golden("cube_in_cube", results);
}

TEST_F(DSOCRegressionTest, PaperTestScene2) {
    // Recreate paper's test scene 2: table for four
    Scene scene = create_table_scene(5745);  // 5745 polygons as in paper
    DSOC_System dsoc;
    
    TestResults results = run_circular_movement_test(scene, dsoc);
    verify_against_golden("table_scene", results);
    
    // Verify specific speedup mentioned in paper
    double speedup = results.baseline_time / results.dsoc_time;
    ASSERT_NEAR(speedup, 2.7, 0.2);  // Paper reports 2.7x speedup
}
```

## Validation Test Utilities

```cpp
// Helper class for thorough validation
class DSOCValidator {
public:
    struct ValidationReport {
        bool passed;
        std::vector<std::string> errors;
        std::map<std::string, double> metrics;
    };
    
    ValidationReport validate_complete_system(DSOC_System& dsoc) {
        ValidationReport report{true, {}, {}};
        
        // Check spatial structure integrity
        if (!validate_spatial_structure(dsoc)) {
            report.passed = false;
            report.errors.push_back("Spatial structure corruption detected");
        }
        
        // Check TBV consistency
        auto tbv_issues = validate_all_tbvs(dsoc);
        if (!tbv_issues.empty()) {
            report.passed = false;
            report.errors.insert(report.errors.end(), 
                               tbv_issues.begin(), tbv_issues.end());
        }
        
        // Check memory leaks
        if (detect_memory_leaks(dsoc)) {
            report.passed = false;
            report.errors.push_back("Memory leak detected");
        }
        
        // Collect metrics
        report.metrics["tbv_count"] = dsoc.get_active_tbv_count();
        report.metrics["tree_depth"] = dsoc.get_max_tree_depth();
        report.metrics["memory_usage"] = dsoc.get_memory_usage();
        
        return report;
    }
};
```

This comprehensive testing guide covers all aspects of validating a DSOC implementation, from unit tests through performance benchmarks, ensuring both correctness and efficiency.