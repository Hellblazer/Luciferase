# Structure-of-Arrays (SoA) Design for Delaunay Tetrahedralization

## Design Principles

1. **Cache Efficiency**: Group similar data together
2. **Memory Density**: Minimize padding and overhead
3. **Index-Based**: Use integer indices instead of pointers
4. **Parallel-Friendly**: Enable SIMD operations where possible
5. **Allocation-Free**: Pre-allocate and reuse memory

## Core Data Structures

### 1. Vertex Storage
```java
class VertexStorage {
    FloatArrayList coordinates;  // x0,y0,z0, x1,y1,z1, ...
    IntArrayList adjacent;       // Adjacent tetrahedron for each vertex
    IntArrayList next;          // Linked list of vertices
    int headVertex = -1;
    int tailVertex = -1;
    Deque<Integer> freeVertices; // Reusable vertex slots
}
```

### 2. Tetrahedron Storage
```java
class TetrahedronStorage {
    IntArrayList vertices;    // a0,b0,c0,d0, a1,b1,c1,d1, ...
    IntArrayList neighbors;   // nA0,nB0,nC0,nD0, nA1,nB1,nC1,nD1, ...
    Deque<Integer> freeTets;  // Reusable tetrahedron slots
    int tetrahedronCount = 0;
}
```

### 3. Packed Grid Structure
```java
class PackedGrid {
    // Vertex data
    FloatArrayList vertices;      // Vertex coordinates
    
    // Tetrahedron data  
    IntArrayList tetrahedra;      // 4 vertex indices per tet
    IntArrayList adjacent;        // 4 neighbor indices per tet
    
    // Free list management
    Deque<Integer> freedTets;     // Reusable tetrahedron indices
    
    // Grid metadata
    final Vertex[] fourCorners;   // Encompassing tetrahedron corners
    int lastTet = 0;             // Last accessed tetrahedron
}
```

## Index Conventions

### Vertex Indexing
- Index `i` refers to vertex at coordinates:
  - x = vertices[i * 3 + 0]
  - y = vertices[i * 3 + 1]  
  - z = vertices[i * 3 + 2]

### Tetrahedron Indexing
- Index `t` refers to tetrahedron with:
  - Vertices: tetrahedra[t * 4 + 0..3]
  - Neighbors: adjacent[t * 4 + 0..3]
- Special value -1 represents null/invalid

### Face Indexing
- Face 0: Opposite vertex A (contains B,C,D)
- Face 1: Opposite vertex B (contains A,C,D)
- Face 2: Opposite vertex C (contains A,B,D)
- Face 3: Opposite vertex D (contains A,B,C)

## Memory Layout

### Vertex Array Layout
```
[x0][y0][z0][x1][y1][z1][x2][y2][z2]...
 └─vertex 0─┘ └─vertex 1─┘ └─vertex 2─┘
```

### Tetrahedron Array Layout
```
[a0][b0][c0][d0][a1][b1][c1][d1]...
 └─────tet 0────┘ └─────tet 1────┘
```

### Adjacency Array Layout
```
[nA0][nB0][nC0][nD0][nA1][nB1][nC1][nD1]...
 └──────tet 0──────┘ └──────tet 1──────┘
```

## Operations Design

### 1. Creation
```java
int allocateTetrahedron() {
    Integer freed = freedTets.pollLast();
    if (freed != null) {
        return freed;
    }
    int newIndex = tetrahedra.size() / 4;
    tetrahedra.add(0, 0, 0, 0);  // Reserve space
    adjacent.add(-1, -1, -1, -1); // No neighbors initially
    return newIndex;
}
```

### 2. Access Pattern
```java
class TetrahedronProxy {
    final int index;
    
    int a() { return tetrahedra.get(index * 4 + 0); }
    int b() { return tetrahedra.get(index * 4 + 1); }
    int c() { return tetrahedra.get(index * 4 + 2); }
    int d() { return tetrahedra.get(index * 4 + 3); }
    
    int getNeighbor(int face) { 
        return adjacent.get(index * 4 + face); 
    }
}
```

### 3. Bidirectional Update
```java
void setNeighbor(int tet1, int face1, int tet2, int face2) {
    // Set forward reference
    adjacent.set(tet1 * 4 + face1, tet2);
    
    // Set reverse reference if valid
    if (tet2 >= 0) {
        adjacent.set(tet2 * 4 + face2, tet1);
    }
}
```

### 4. Deletion Handling
```java
void deleteTetrahedron(int tetIndex) {
    // Clear neighbor references to this tet
    for (int face = 0; face < 4; face++) {
        int neighbor = adjacent.get(tetIndex * 4 + face);
        if (neighbor >= 0) {
            // Find and clear reverse reference
            for (int nFace = 0; nFace < 4; nFace++) {
                if (adjacent.get(neighbor * 4 + nFace) == tetIndex) {
                    adjacent.set(neighbor * 4 + nFace, -1);
                    break;
                }
            }
        }
    }
    
    // Mark as deleted
    for (int i = 0; i < 4; i++) {
        tetrahedra.set(tetIndex * 4 + i, -1);
        adjacent.set(tetIndex * 4 + i, -1);
    }
    
    // Add to free list
    freedTets.add(tetIndex);
}
```

## Critical Design Decisions

### 1. Proxy Objects vs Direct Access
- Use lightweight proxy objects for clean API
- Proxies hold only index, no data
- All data access through arrays

### 2. Deletion Strategy
- Soft deletion with free list
- Maintains index stability
- Enables efficient reuse

### 3. Neighbor Finding
- Must search for reverse reference
- O(1) forward lookup, O(4) reverse lookup
- Alternative: store face indices (more memory)

### 4. Vertex Management
- Separate from tetrahedron storage
- Enables vertex-centric operations
- Maintains vertex-tetrahedron adjacency

## Performance Considerations

### Cache Optimization
- Sequential access patterns for bulk operations
- Hot data (vertices, tetrahedra) in separate arrays
- Cold data (free lists) separate

### SIMD Opportunities
- Orientation tests on multiple points
- Bulk coordinate transformations
- Parallel neighbor updates

### Memory Allocation
- Pre-allocate based on expected size
- Grow arrays in chunks, not individually
- Reuse deleted slots aggressively

## Implementation Phases

1. **Phase 1**: Basic structure and allocation
2. **Phase 2**: Access methods and proxies
3. **Phase 3**: Modification operations
4. **Phase 4**: Complex algorithms (locate, flip)
5. **Phase 5**: Optimization and tuning