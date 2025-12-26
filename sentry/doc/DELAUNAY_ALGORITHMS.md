# Delaunay Tetrahedralization Algorithms

## Overview

This document provides detailed explanations of the algorithms used in the Sentry module for 3D Delaunay tetrahedralization, including geometric predicates, bistellar flips, and kinetic updates.

## Fundamental Concepts

### Delaunay Property

A tetrahedralization is Delaunay if no vertex lies inside the circumsphere of any tetrahedron. This property ensures:

- Maximizes minimum angles (avoids slivers)
- Unique for non-degenerate point sets
- Dual structure is the Voronoi diagram
- Optimal for interpolation and mesh generation

### Tetrahedron Orientation

Vertices are ordered (a, b, c, d) such that:

```text
orient3d(a, b, c, d) > 0

```

This ensures consistent positive orientation for all geometric predicates.

## Core Algorithms

### 1. Geometric Predicates

#### Orientation Test (orient3d)

Determines which side of plane ABC point D lies on:

```java
double orient3d(Point3f a, Point3f b, Point3f c, Point3f d) {
    return det(
        |ax - dx  ay - dy  az - dz|
        |bx - dx  by - dy  bz - dz|
        |cx - dx  cy - dy  cz - dz|
    );
}

```

- Result > 0: D is above plane ABC
- Result < 0: D is below plane ABC
- Result = 0: D is coplanar with ABC

#### InSphere Test

Determines if point E lies inside circumsphere of tetrahedron ABCD:

```java
double insphere(Point3f a, Point3f b, Point3f c, Point3f d, Point3f e) {
    return det(
        |ax - ex  ay - ey  az - ez  (ax-ex)² + (ay-ey)² + (az-ez)²|
        |bx - ex  by - ey  bz - ez  (bx-ex)² + (by-ey)² + (bz-ez)²|
        |cx - ex  cy - ey  cz - ez  (cx-ex)² + (cy-ey)² + (cz-ez)²|
        |dx - ex  dy - ey  dz - ez  (dx-ex)² + (dy-ey)² + (dz-ez)²|
    );
}

```

- Result > 0: E is inside circumsphere
- Result < 0: E is outside circumsphere
- Result = 0: E is on circumsphere

### 2. Point Location Algorithm

The randomized jump-and-walk algorithm efficiently locates containing tetrahedra:

```java
Tetrahedron locate(Point3f target, Tetrahedron start) {
    Tetrahedron current = (start != null) ? start : randomTetrahedron();
    
    while (true) {
        // Check each face to see if target is outside
        for (OrientedFace face : current.faces()) {
            if (face.orientationOf(target) < 0) {
                // Target is outside this face
                current = face.adjacent();
                break;
            }
        }
        
        // If target is inside all faces, we found it
        if (allFacesContain(current, target)) {
            return current;
        }
    }
}

```

**Optimizations:**
- Start from last query result (temporal locality)
- Use vertex's incident tetrahedron (spatial locality)
- Random starting point prevents worst-case behavior

### 3. Incremental Construction

#### Point Insertion

```java
void insert(Point3f p) {
    // 1. Locate containing tetrahedron
    Tetrahedron tet = locate(p);
    
    // 2. Determine insertion case
    if (tet.contains(p)) {
        // Case 1: Point inside tetrahedron (1→4 flip)
        flip1to4(tet, p);
    } else if (onFace(tet, p)) {
        // Case 2: Point on face (2→3 flip)
        flip2to3(getFace(tet, p), p);
    } else if (onEdge(tet, p)) {
        // Case 3: Point on edge (n→2n flip)
        flipEdge(getEdge(tet, p), p);
    }
    
    // 3. Restore Delaunay property
    propagateDelaunay(newTetrahedra);
}

```

#### 1→4 Flip (Point Inside)

```text
    Before:           After:
       d                 d
      /|\               /|\
     / | \             / p \
    /  |  \           /  |  \
   a---+---c         a---+---c
    \  |  /           \  |  /
     \ | /             \ | /
      \|/               \|/
       b                 b

Creates 4 new tetrahedra: (a,b,c,p), (a,b,p,d), (a,p,c,d), (p,b,c,d)

```

#### 2→3 Flip (Convex Edge)

```text
    Before:                After:
       a                     a
      /|\                   /|\
     / | \                 / | \
    d  |  e               d--p--e
     \ | /                 \ | /
      \|/                   \|/
       b                     b

Transforms 2 tetrahedra sharing face abe into 3 tetrahedra

```

### 4. Delaunay Property Restoration

After insertion, non-Delaunay faces are flipped:

```java
void propagateDelaunay(List<Tetrahedron> modified) {
    Queue<OrientedFace> toCheck = new LinkedList<>();
    
    // Add all faces of modified tetrahedra
    for (Tetrahedron tet : modified) {
        toCheck.addAll(tet.faces());
    }
    
    while (!toCheck.isEmpty()) {
        OrientedFace face = toCheck.poll();
        
        if (!isDelaunay(face)) {
            // Perform appropriate flip
            List<Tetrahedron> flipped = flip(face);
            
            // Add newly created faces to check
            for (Tetrahedron tet : flipped) {
                toCheck.addAll(tet.externalFaces());
            }
        }
    }
}

```

### 5. Vertex Deletion

The ear-based algorithm removes vertices by collapsing their star:

```java
void delete(Vertex v) {
    // 1. Find star set (incident tetrahedra)
    Set<Tetrahedron> star = v.star();
    
    // 2. Extract boundary triangulation
    List<Triangle> boundary = extractBoundary(star);
    
    // 3. Remove ears iteratively
    while (boundary.size() > 4) {
        Triangle ear = findEar(boundary);
        createTetrahedron(ear, oppositeVertex);
        boundary.remove(ear);
    }
    
    // 4. Fill final hole (4→1 flip)
    fillFinalHole(boundary);
}

```

#### Ear Identification

A boundary triangle is an "ear" if:

1. It's convex (forms valid tetrahedron)
2. No other boundary vertices lie inside its circumsphere

### 6. Kinetic Updates

For moving points, the module supports efficient updates:

```java
void moveVertex(Vertex v, Point3f newLocation) {
    // 1. Check if movement crosses any faces
    List<Tetrahedron> affected = findAffectedTetrahedra(v, newLocation);
    
    if (affected.isEmpty()) {
        // Simple case: update in place
        v.setLocation(newLocation);
    } else {
        // Complex case: remove and reinsert
        Point3f oldLocation = v.getLocation();
        delete(v);
        v.setLocation(newLocation);
        insert(v);
    }
}

```

## Special Cases and Degeneracies

### Coplanar Points

When 4+ points are coplanar:

- Use symbolic perturbation (SoS)
- Maintain consistency across predicates
- Ensure termination of algorithms

### Collinear Points

When 3+ points are collinear:

- Create degenerate tetrahedra temporarily
- Remove during post-processing
- Maintain topological consistency

### Duplicate Points

- Detect using tolerance (epsilon)
- Merge or reject based on policy
- Update references consistently

## Performance Optimizations

### 1. Stochastic Walk

Instead of deterministic traversal:

```java
Tetrahedron stochasticWalk(Point3f target) {
    Tetrahedron current = randomStart();
    Set<Tetrahedron> visited = new HashSet<>();
    
    while (!current.contains(target)) {
        // Choose random unvisited neighbor closer to target
        List<Tetrahedron> candidates = current.neighbors()
            .filter(t -> !visited.contains(t))
            .filter(t -> distance(t, target) < distance(current, target))
            .collect(Collectors.toList());
            
        if (candidates.isEmpty()) {
            // Restart from random location
            current = randomStart();
            visited.clear();
        } else {
            current = randomChoice(candidates);
            visited.add(current);
        }
    }
    
    return current;
}

```

### 2. History DAG

Maintain insertion history for faster point location:

- Each tetrahedron points to its "children"
- Walk down DAG to find current containing tetrahedron
- Amortized O(log n) point location

### 3. Biased Randomized Insertion

Insert points in randomized order biased by spatial distribution:

- Reduces expected number of flips
- Improves cache locality
- Better parallelization potential

## Voronoi Dual Extraction

The Voronoi diagram is the geometric dual of Delaunay:

```java
VoronoiCell computeVoronoiCell(Vertex v) {
    VoronoiCell cell = new VoronoiCell();
    
    // Walk around vertex's star
    for (Tetrahedron tet : v.star()) {
        // Voronoi vertex is circumcenter of tetrahedron
        Point3f voronoiVertex = tet.circumcenter();
        cell.addVertex(voronoiVertex);
        
        // Connect to adjacent cells
        for (OrientedFace face : tet.faces()) {
            if (!face.contains(v)) {
                Tetrahedron adj = face.adjacent();
                if (adj != null) {
                    cell.addEdge(voronoiVertex, adj.circumcenter());
                }
            }
        }
    }
    
    return cell;
}

```

## Robustness Considerations

### Floating Point Issues

- Use tolerance-based comparisons
- Implement consistent tie-breaking
- Consider interval arithmetic for critical operations

### Predicate Consistency

- Ensure orient3d and insphere are consistent
- Use same precision for all geometric computations
- Implement symbolic perturbation if needed

### Topological Invariants

Maintain at all times:

1. Each tetrahedron has exactly 4 neighbors
2. Neighbor relationships are symmetric
3. Vertex-tetrahedron incidence is consistent
4. No overlapping tetrahedra

## References

1. Shewchuk, J. R. "Robust Adaptive Floating-Point Geometric Predicates"
2. Edelsbrunner, H. "Geometry and Topology for Mesh Generation"
3. De Berg, M. et al. "Computational Geometry: Algorithms and Applications"
4. Guibas, L. & Stolfi, J. "Primitives for the Manipulation of General Subdivisions"
