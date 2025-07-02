# Bey Tetrahedral Subdivision Method Reference

## Implementation Status

**Implemented in Lucien (June 28, 2025)**:
- `Tet.geometricSubdivide()` - Returns 8 children with 100% geometric containment
- `Tet.subdivisionCoordinates()` - Provides V3 = anchor + (h,h,h) compatible vertices
- `BeySubdivision.subdivide()` - Core subdivision algorithm implementation
- Performance: ~0.04 μs per operation, 5.5x faster than grid-based methods

See [TETRAHEDRAL_SUBDIVISION_SUCCESS.md](./TETRAHEDRAL_SUBDIVISION_SUCCESS.md) for implementation details.

## Core Data Structure

```java
public class Tet {
    int level;      // Refinement level
    int x, y, z;    // Anchor node coordinates  
    int type;       // Type (0-5)
}
```

## Subdivision Tables

### Table 4.1: Child Types

For parent type b, the types of its 8 children in Bey order:

| Parent Type | T0 | T1 | T2 | T3 | T4 | T5 | T6 | T7 |
|-------------|----|----|----|----|----|----|----|----|
| 0           | 0  | 0  | 0  | 0  | 4  | 5  | 2  | 1  |
| 1           | 1  | 1  | 1  | 1  | 3  | 2  | 5  | 0  |
| 2           | 2  | 2  | 2  | 2  | 0  | 1  | 4  | 3  |
| 3           | 3  | 3  | 3  | 3  | 5  | 4  | 1  | 2  |
| 4           | 4  | 4  | 4  | 4  | 2  | 3  | 0  | 5  |
| 5           | 5  | 5  | 5  | 5  | 1  | 0  | 3  | 4  |

### Table 4.2: TM Order Mapping

Maps Bey order to TM order for each parent type:

| Parent Type | TM[0] | TM[1] | TM[2] | TM[3] | TM[4] | TM[5] | TM[6] | TM[7] |
|-------------|-------|-------|-------|-------|-------|-------|-------|-------|
| 0           | T0    | T1    | T4    | T7    | T2    | T3    | T6    | T5    |
| 1           | T0    | T1    | T5    | T7    | T2    | T3    | T6    | T4    |
| 2           | T0    | T3    | T4    | T7    | T1    | T2    | T6    | T5    |
| 3           | T0    | T1    | T6    | T7    | T2    | T3    | T4    | T5    |
| 4           | T0    | T3    | T5    | T7    | T1    | T2    | T4    | T6    |
| 5           | T0    | T3    | T6    | T7    | T2    | T1    | T4    | T5    |

## Key Algorithms

### Compute Tetrahedron Vertices

```java
int[][] computeVertices(Tet T) {
    int[][] X = new int[4][3];
    int h = 1 << (30 - T.level);  // 2^(L-level)

    // Anchor node
    X[0] = { T.x, T.y, T.z };

    // Compute other vertices based on type
    int i = T.type / 2;
    int j = (T.type % 2 == 0) ? (i + 2) % 3 : (i + 1) % 3;

    X[1] = X[0] + h * e_i;     // e_i = unit vector in direction i
    X[2] = X[1] + h * e_j;     // e_j = unit vector in direction j  
    X[3] = { X[0][0] + h, X[0][1] + h, X[0][2] + h };

    return X;
}
```

### Bey Subdivision Rule

The 8 children subdivide the parent tetrahedron as follows:

- **T0-T3**: Corner children (at original vertices)
- **T4-T7**: Octahedral children (subdivide inner octahedron)

Child anchor nodes based on parent vertices (x0, x1, x2, x3):

- T0: x0
- T1: (x0 + x1)/2
- T2: (x0 + x2)/2
- T3: (x0 + x3)/2
- T4: (x0 + x1)/2
- T5: (x0 + x1)/2
- T6: (x0 + x2)/2
- T7: (x0 + x2)/2

### Subdivision Method

```java
public static Tet[] subdivide(Tet parent) {
    Tet[] children = new Tet[8];
    int childLevel = parent.level + 1;

    // Create 8 children in Bey order
    Tet[] beyChildren = new Tet[8];
    for (int i = 0; i < 8; i++) {
        beyChildren[i] = createChild(parent, i, childLevel);
    }

    // Reorder to TM order using Table 4.2
    for (int tmIndex = 0; tmIndex < 8; tmIndex++) {
        int beyIndex = BEY_ORDER[parent.type][tmIndex];
        children[tmIndex] = beyChildren[beyIndex];
    }

    return children;
}

private static Tet createChild(Tet parent, int beyIndex, int childLevel) {
    // Get anchor based on which child (0-7)
    int[] anchor = computeChildAnchor(parent, beyIndex);

    // Get type from Table 4.1
    int childType = CHILD_TYPES[parent.type][beyIndex];

    return new Tet(childLevel, anchor[0], anchor[1], anchor[2], childType);
}
```

## Key Properties

1. **8 children exactly partition parent volume** (no gaps, no overlaps)
2. **Corner children (T0-T3) inherit parent's type**
3. **Children are returned in TM (space-filling curve) order**
4. **All tetrahedra have vertex 3 at anchor + (h,h,h)** where h = 2^(L-level)
5. **Subdivision preserves numerical stability** - no degenerate tetrahedra
