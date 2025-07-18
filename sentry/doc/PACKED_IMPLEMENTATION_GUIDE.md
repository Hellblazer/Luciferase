# Packed Implementation Guide

## Overview

The packed implementation provides a memory-efficient alternative to the object-oriented Delaunay tetrahedralization. By using primitive arrays and integer indices instead of object references, it achieves significant memory savings and improved cache locality.

## Architecture

### Core Data Structures

```java
public class PackedGrid {
    // Vertex coordinates packed as [x0,y0,z0, x1,y1,z1, ...]
    private FloatArrayList vertices;
    
    // Tetrahedron vertices packed as [v0,v1,v2,v3, v0,v1,v2,v3, ...]
    private IntArrayList tetrahedra;
    
    // Adjacent tetrahedra packed as [n0,n1,n2,n3, n0,n1,n2,n3, ...]
    private IntArrayList adjacent;
    
    // Object pooling for deleted tetrahedra
    private Deque<Integer> freed;
    
    // Constants
    private static final int VERTICES_PER_TET = 4;
    private static final int COORDS_PER_VERTEX = 3;
}
```

### Memory Layout

#### Vertex Storage
```
Index:  0   1   2   3   4   5   6   7   8  ...
Data:  [x0, y0, z0, x1, y1, z1, x2, y2, z2, ...]
        └─ vertex 0─┘ └─ vertex 1─┘ └─ vertex 2─┘
```

#### Tetrahedron Storage
```
Index:  0   1   2   3   4   5   6   7   8  ...
Data:  [v0, v1, v2, v3, v0, v1, v2, v3, ...]
        └─── tet 0 ───┘ └─── tet 1 ───┘
```

#### Adjacency Storage
```
Index:  0   1   2   3   4   5   6   7   8  ...
Data:  [n0, n1, n2, n3, n0, n1, n2, n3, ...]
        └─── tet 0 ───┘ └─── tet 1 ───┘

Where n0 = neighbor opposite vertex 0, etc.
```

## Key Operations

### Vertex Access

```java
// Get vertex coordinates
float getX(int vertex) {
    return vertices.get(vertex * COORDS_PER_VERTEX);
}

float getY(int vertex) {
    return vertices.get(vertex * COORDS_PER_VERTEX + 1);
}

float getZ(int vertex) {
    return vertices.get(vertex * COORDS_PER_VERTEX + 2);
}

// Add new vertex
int addVertex(float x, float y, float z) {
    int index = vertices.size() / COORDS_PER_VERTEX;
    vertices.add(x);
    vertices.add(y);
    vertices.add(z);
    return index;
}
```

### Tetrahedron Access

```java
// Get tetrahedron vertices
int getVertex(int tet, int corner) {
    return tetrahedra.get(tet * VERTICES_PER_TET + corner);
}

// Get neighbor tetrahedron
int getNeighbor(int tet, int face) {
    return adjacent.get(tet * VERTICES_PER_TET + face);
}

// Set neighbor relationship
void setNeighbor(int tet, int face, int neighbor) {
    adjacent.set(tet * VERTICES_PER_TET + face, neighbor);
}
```

### Object Pooling

```java
// Allocate new tetrahedron
int allocateTetrahedron() {
    if (!freed.isEmpty()) {
        return freed.poll();  // Reuse freed slot
    }
    
    // Allocate new space
    int index = tetrahedra.size() / VERTICES_PER_TET;
    for (int i = 0; i < VERTICES_PER_TET; i++) {
        tetrahedra.add(-1);  // Invalid vertex
        adjacent.add(-1);    // No neighbor
    }
    return index;
}

// Free tetrahedron for reuse
void freeTetrahedron(int tet) {
    // Mark as invalid
    for (int i = 0; i < VERTICES_PER_TET; i++) {
        tetrahedra.set(tet * VERTICES_PER_TET + i, -1);
        adjacent.set(tet * VERTICES_PER_TET + i, -1);
    }
    freed.offer(tet);
}
```

## Bistellar Flips

### 1→4 Flip Implementation

```java
void flip1to4(int tet, int newVertex) {
    // Get original vertices
    int v0 = getVertex(tet, 0);
    int v1 = getVertex(tet, 1);
    int v2 = getVertex(tet, 2);
    int v3 = getVertex(tet, 3);
    
    // Get original neighbors
    int n0 = getNeighbor(tet, 0);
    int n1 = getNeighbor(tet, 1);
    int n2 = getNeighbor(tet, 2);
    int n3 = getNeighbor(tet, 3);
    
    // Reuse original tetrahedron for first new one
    setVertices(tet, v0, v1, v2, newVertex);
    
    // Allocate three new tetrahedra
    int tet1 = allocateTetrahedron();
    int tet2 = allocateTetrahedron();
    int tet3 = allocateTetrahedron();
    
    setVertices(tet1, v0, v1, newVertex, v3);
    setVertices(tet2, v0, newVertex, v2, v3);
    setVertices(tet3, newVertex, v1, v2, v3);
    
    // Update internal adjacencies
    setNeighbor(tet, 3, n3);
    setNeighbor(tet1, 2, n2);
    setNeighbor(tet2, 1, n1);
    setNeighbor(tet3, 0, n0);
    
    // ... update remaining adjacencies
}
```

### 2→3 Flip Implementation

```java
void flip2to3(int tet1, int tet2, int face1, int face2, int newVertex) {
    // Extract shared edge vertices
    int[] edge = getSharedEdge(tet1, face1, tet2, face2);
    int a = edge[0], b = edge[1];
    
    // Get opposite vertices
    int c = getOppositeVertex(tet1, face1);
    int d = getOppositeVertex(tet2, face2);
    
    // Allocate third tetrahedron
    int tet3 = allocateTetrahedron();
    
    // Create three new tetrahedra around the edge
    setVertices(tet1, a, b, c, newVertex);
    setVertices(tet2, a, b, newVertex, d);
    setVertices(tet3, a, newVertex, c, d);
    
    // Update adjacencies...
}
```

## Geometric Predicates

### Orientation Test

```java
float orient3d(int v0, int v1, int v2, int v3) {
    // Extract coordinates
    float ax = getX(v0), ay = getY(v0), az = getZ(v0);
    float bx = getX(v1), by = getY(v1), bz = getZ(v1);
    float cx = getX(v2), cy = getY(v2), cz = getZ(v2);
    float dx = getX(v3), dy = getY(v3), dz = getZ(v3);
    
    // Compute 3x3 determinant
    float adx = ax - dx, ady = ay - dy, adz = az - dz;
    float bdx = bx - dx, bdy = by - dy, bdz = bz - dz;
    float cdx = cx - dx, cdy = cy - dy, cdz = cz - dz;
    
    return adx * (bdy * cdz - bdz * cdy)
         + ady * (bdz * cdx - bdx * cdz)
         + adz * (bdx * cdy - bdy * cdx);
}
```

### InSphere Test

```java
float insphere(int tet, int testVertex) {
    int v0 = getVertex(tet, 0);
    int v1 = getVertex(tet, 1);
    int v2 = getVertex(tet, 2);
    int v3 = getVertex(tet, 3);
    
    // Fast approximate test using circumcenter
    float[] cc = circumcenter(v0, v1, v2, v3);
    float r2 = radiusSquared(cc, v0);
    
    float dx = getX(testVertex) - cc[0];
    float dy = getY(testVertex) - cc[1];
    float dz = getZ(testVertex) - cc[2];
    
    return r2 - (dx*dx + dy*dy + dz*dz);
}
```

## Point Location

### Stochastic Walk

```java
int locate(float x, float y, float z, int startTet) {
    int current = (startTet >= 0) ? startTet : randomTetrahedron();
    
    while (true) {
        boolean inside = true;
        
        // Check each face
        for (int face = 0; face < 4; face++) {
            if (!isInside(current, face, x, y, z)) {
                // Move to neighbor
                int neighbor = getNeighbor(current, face);
                if (neighbor >= 0) {
                    current = neighbor;
                    inside = false;
                    break;
                }
            }
        }
        
        if (inside) return current;
    }
}
```

## Performance Optimizations

### Cache-Friendly Traversal

```java
// Process tetrahedra in sequential order
void processAllTetrahedra() {
    int count = tetrahedra.size() / VERTICES_PER_TET;
    
    for (int tet = 0; tet < count; tet++) {
        // Skip freed tetrahedra
        if (getVertex(tet, 0) < 0) continue;
        
        // Process with good cache locality
        processTetrahedron(tet);
    }
}
```

### Batch Operations

```java
// Insert multiple points efficiently
void insertBatch(float[] points) {
    // Pre-allocate space
    int expectedTets = estimateTetrahedra(points.length / 3);
    ensureCapacity(expectedTets);
    
    // Insert points
    for (int i = 0; i < points.length; i += 3) {
        insert(points[i], points[i+1], points[i+2]);
    }
    
    // Compact arrays if needed
    if (freed.size() > tetrahedra.size() / 8) {
        compact();
    }
}
```

### Memory Compaction

```java
void compact() {
    // Build remapping table
    int[] remap = new int[tetrahedra.size() / VERTICES_PER_TET];
    int writePos = 0;
    
    for (int readPos = 0; readPos < remap.length; readPos++) {
        if (getVertex(readPos, 0) >= 0) {
            remap[readPos] = writePos++;
        } else {
            remap[readPos] = -1;
        }
    }
    
    // Compact arrays
    compactArray(tetrahedra, remap);
    compactArray(adjacent, remap);
    
    // Clear free list
    freed.clear();
}
```

## Usage Examples

### Basic Usage

```java
// Create packed grid
PackedGrid grid = new PackedGrid(1000);

// Add points
int v1 = grid.add(0, 0, 0);
int v2 = grid.add(1, 0, 0);
int v3 = grid.add(0, 1, 0);

// Find containing tetrahedron
int tet = grid.locate(0.5f, 0.5f, 0.5f);

// Iterate tetrahedra
grid.tetrahedronStream().forEach(t -> {
    // Process tetrahedron
});
```

### Voronoi Extraction

```java
// Get Voronoi cell for vertex
List<float[]> voronoiCell = grid.getVoronoiCell(vertex);

// Get all Voronoi edges
grid.voronoiEdgeStream().forEach(edge -> {
    float[] p1 = edge.getStart();
    float[] p2 = edge.getEnd();
    // Process edge
});
```

## Memory Analysis

### Space Usage Per Vertex
- Coordinates: 12 bytes (3 floats)
- Average tetrahedra: ~6 per vertex
- Per tetrahedron: 16 bytes (4 ints) + 16 bytes (4 neighbors)
- Total: ~60 bytes per vertex

### Comparison with Object-Oriented
- Object header: 16 bytes (eliminated)
- Reference: 8 bytes → 4 bytes (int)
- No GC overhead
- Better cache locality

### Practical Limits
- Maximum vertices: ~700 million (2^31 / 3)
- Maximum tetrahedra: ~500 million (2^31 / 4)
- Typical dataset: 1M vertices → 60MB memory

## Best Practices

1. **Pre-allocate Space**: Use capacity hints when possible
2. **Batch Operations**: Insert/delete multiple points together
3. **Periodic Compaction**: Compact when fragmentation exceeds threshold
4. **Reuse Indices**: Keep vertex indices for repeated queries
5. **Local Updates**: Use movement hints for kinetic points

## Troubleshooting

### Common Issues

1. **Index Out of Bounds**: Check for freed tetrahedra
2. **Invalid Neighbors**: Ensure symmetric updates
3. **Memory Growth**: Monitor freed list size
4. **Performance Degradation**: Check fragmentation level

### Debugging Tools

```java
// Validation
boolean isValid() {
    return checkTopology() && 
           checkOrientation() && 
           checkDelaunay();
}

// Statistics
void printStats() {
    System.out.println("Vertices: " + vertexCount());
    System.out.println("Tetrahedra: " + tetrahedronCount());
    System.out.println("Freed slots: " + freed.size());
    System.out.println("Fragmentation: " + fragmentation() + "%");
}
```