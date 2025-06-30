# Spatial Index Visualization Plan

**Status**: ACTIVE - Current Project Focus  
**Created**: June 29, 2025  
**Module**: portal  
**Integration**: lucien module (Octree/Tetree)

## 🎯 Project Goal

Create comprehensive JavaFX 3D visualization capabilities for the spatial index structures (Octree and Tetree) from the lucien module, enabling developers and users to:
- Visualize the hierarchical structure of spatial indices
- See entity positions and node occupancy
- Interact with the spatial data structures
- Debug and understand spatial queries
- Analyze performance characteristics

## 📋 Current Status Assessment

### Existing Foundation
- ✅ JavaFX 3D infrastructure in portal module
- ✅ Basic polyhedra rendering (Cube, Tetrahedron, etc.)
- ✅ Grid visualization framework (CubicGrid)
- ✅ Limited OctreeInspector implementation
- ❌ No proper Octree integration with lucien
- ❌ No Tetree visualization
- ❌ No interactive features
- ❌ No query visualization

### Integration Points
1. **lucien.octree**: `Octree<ID, Content>`, `Octant`, `MortonKey`
2. **lucien.tetree**: `Tetree<ID, Content>`, `Tet`, `TetreeKey`
3. **lucien.core**: `AbstractSpatialIndex`, `SpatialKey`, `EntityManager`
4. **portal.tree**: Existing OctreeView/TetreeView (needs refactoring)

## 🏗️ Architecture Design

### Core Visualization Components

#### 1. Base Classes

```java
// Abstract base for spatial index visualization
public abstract class SpatialIndexView<Key extends SpatialKey<Key>, ID, Content> {
    protected AbstractSpatialIndex<Key, ID, Content> spatialIndex;
    protected Group sceneRoot;
    protected Map<Key, Node> nodeVisuals;
    protected Map<ID, Node> entityVisuals;
    
    // Core visualization methods
    public abstract void updateVisualization();
    public abstract void highlightNode(Key nodeKey);
    public abstract void showLevel(int level);
    public abstract void visualizeQuery(SpatialQuery query);
}
```

#### 2. Octree Visualization

```java
public class OctreeVisualization<ID, Content> extends SpatialIndexView<MortonKey, ID, Content> {
    private Octree<ID, Content> octree;
    private Map<Integer, Color> levelColors;
    private boolean showEmptyNodes = false;
    private boolean showEntityPositions = true;
    
    // Visualization methods
    private Box createOctantVisual(Octant octant, int level);
    private void renderOctreeLevel(int level);
    private void animateSubdivision(MortonKey parentKey);
}
```

#### 3. Tetree Visualization

```java
public class TetreeVisualization<ID, Content> extends SpatialIndexView<TetreeKey, ID, Content> {
    private Tetree<ID, Content> tetree;
    private Map<Integer, Color> levelColors;
    private boolean showTetrahedralDecomposition = true;
    
    // Visualization methods
    private MeshView createTetVisual(Tet tet, int level);
    private void renderTetreeLevel(int level);
    private void showBeyerDecomposition();
}
```

### Visual Design Specifications

#### Node Rendering
1. **Octree Nodes (Octants)**:
   - Wireframe cubes with semi-transparent faces
   - Color-coded by level (gradient from blue→red)
   - Opacity indicates entity density
   - Edge thickness shows node importance

2. **Tetree Nodes (Tets)**:
   - Wireframe tetrahedra with semi-transparent faces
   - Color-coded by type (0-5) and level
   - Highlight characteristic tetrahedron decomposition
   - Show centroid markers

#### Entity Visualization
- Spheres or points for entity positions
- Color by entity ID or content type
- Size proportional to entity bounds
- Connectors to containing node

#### Interactive Features
1. **Navigation**:
   - Mouse controls for camera orbit/pan/zoom
   - Keyboard shortcuts for level navigation
   - Click to select nodes/entities
   - Hover for information overlay

2. **Filtering**:
   - Show/hide levels
   - Filter by entity type
   - Toggle empty nodes
   - Adjust transparency

3. **Query Visualization**:
   - Range queries: semi-transparent box/sphere
   - k-NN: highlighted nearest neighbors with distance lines
   - Ray traversal: animated ray with intersected nodes
   - Collision detection: highlight overlapping entities

## 📋 Implementation Plan

### Phase 1: Foundation (Week 1)
1. **Create base visualization framework**
   - [ ] Design SpatialIndexView abstract class
   - [ ] Set up scene graph management
   - [ ] Implement level-based rendering system
   - [ ] Create color/material management

2. **Basic Octree visualization**
   - [ ] Implement OctreeVisualization class
   - [ ] Create octant wireframe rendering
   - [ ] Add level-based coloring
   - [ ] Show occupied vs empty nodes

### Phase 2: Tetree Support (Week 2)
1. **Tetree visualization**
   - [ ] Implement TetreeVisualization class
   - [ ] Create tetrahedron mesh generation
   - [ ] Handle 6 characteristic types
   - [ ] Show Beyer decomposition

2. **Entity rendering**
   - [ ] Visualize entity positions
   - [ ] Show entity bounds
   - [ ] Link entities to containing nodes
   - [ ] Support multi-entity nodes

### Phase 3: Interactivity (Week 3)
1. **User controls**
   - [ ] Implement camera controls
   - [ ] Add node selection
   - [ ] Create information panels
   - [ ] Level navigation controls

2. **Filtering and display options**
   - [ ] Level visibility toggles
   - [ ] Entity filtering
   - [ ] Transparency controls
   - [ ] Performance statistics overlay

### Phase 4: Query Visualization (Week 4)
1. **Spatial queries**
   - [ ] Range query visualization
   - [ ] k-NN query animation
   - [ ] Ray traversal display
   - [ ] Collision detection highlights

2. **Advanced features**
   - [ ] Tree modification animation
   - [ ] Performance profiling overlay
   - [ ] Export visualization snapshots
   - [ ] Record interaction sessions

## 🛠️ Technical Requirements

### Dependencies
- JavaFX 24.x for 3D graphics
- lucien module for spatial indices
- javax.vecmath for mathematics
- Java 23+ features

### Performance Considerations
1. **Level-of-Detail (LOD)**:
   - Render only visible nodes
   - Simplify distant geometry
   - Batch similar materials
   - Use instanced rendering for entities

2. **Memory Management**:
   - Lazy creation of visual nodes
   - Dispose unused geometry
   - Limit visualization depth
   - Cache frequently used meshes

3. **Rendering Optimization**:
   - Frustum culling
   - Occlusion culling for dense trees
   - Billboard sprites for distant entities
   - GPU-based picking

## 📊 Success Metrics

1. **Functionality**:
   - ✓ Visualize Octree with up to 100K nodes at 60 FPS
   - ✓ Visualize Tetree with proper tetrahedral decomposition
   - ✓ Interactive navigation with <16ms response time
   - ✓ Query visualization with clear visual feedback

2. **Usability**:
   - ✓ Intuitive controls documented in UI
   - ✓ Clear visual hierarchy
   - ✓ Informative node/entity labels
   - ✓ Smooth animations and transitions

3. **Integration**:
   - ✓ Seamless integration with lucien module
   - ✓ Support for all spatial index operations
   - ✓ Extensible for future index types
   - ✓ Reusable visualization components

## 🚀 Example Usage

```java
// Visualize an Octree
Octree<String, Point3f> octree = new Octree<>(1000.0f);
// ... populate octree ...

OctreeVisualization<String, Point3f> viz = new OctreeVisualization<>(octree);
viz.setShowEmptyNodes(false);
viz.setLevelRange(0, 5);
viz.highlightEntitiesInRange(new Point3f(100, 100, 100), 50.0f);

// Visualize a Tetree
Tetree<Long, EntityData> tetree = new Tetree<>(1000.0f);
// ... populate tetree ...

TetreeVisualization<Long, EntityData> viz = new TetreeVisualization<>(tetree);
viz.showCharacteristicDecomposition();
viz.animateKNNQuery(new Point3f(200, 200, 200), 10);
```

## 📝 Next Steps

1. Begin with Phase 1 foundation implementation
2. Create OctreeVisualization class extending the existing framework
3. Refactor existing OctreeInspector as reference
4. Build interactive demo application
5. Document visualization API

This plan provides a comprehensive roadmap for implementing spatial index visualization in the portal module, enabling powerful debugging and analysis capabilities for the Luciferase spatial data structures.