# S0-S5 Tetrahedral Decomposition Quick Reference

## Cube Vertex Numbering
```
      6 -------- 7
     /|         /|
    / |        / |
   4 -------- 5  |
   |  |       |  |
   |  2 ------|--3
   | /        | /
   |/         |/
   0 -------- 1

Vertex coordinates (for cube with size h at origin):
V0 = (0, 0, 0)
V1 = (h, 0, 0)
V2 = (0, h, 0)
V3 = (h, h, 0)
V4 = (0, 0, h)
V5 = (h, 0, h)
V6 = (0, h, h)
V7 = (h, h, h)
```

## The 6 Tetrahedra (S0-S5)

All tetrahedra share vertices V0 and V7.

### Type 0 (S0): Tetrahedron with vertices {0, 1, 3, 7}
- V0 = (0, 0, 0) = anchor
- V1 = (h, 0, 0) = anchor + (h, 0, 0)
- V3 = (h, h, 0) = anchor + (h, h, 0)
- V7 = (h, h, h) = anchor + (h, h, h)

### Type 1 (S1): Tetrahedron with vertices {0, 2, 3, 7}
- V0 = (0, 0, 0) = anchor
- V2 = (0, h, 0) = anchor + (0, h, 0)
- V3 = (h, h, 0) = anchor + (h, h, 0)
- V7 = (h, h, h) = anchor + (h, h, h)

### Type 2 (S2): Tetrahedron with vertices {0, 4, 5, 7}
- V0 = (0, 0, 0) = anchor
- V4 = (0, 0, h) = anchor + (0, 0, h)
- V5 = (h, 0, h) = anchor + (h, 0, h)
- V7 = (h, h, h) = anchor + (h, h, h)

### Type 3 (S3): Tetrahedron with vertices {0, 4, 6, 7}
- V0 = (0, 0, 0) = anchor
- V4 = (0, 0, h) = anchor + (0, 0, h)
- V6 = (0, h, h) = anchor + (0, h, h)
- V7 = (h, h, h) = anchor + (h, h, h)

### Type 4 (S4): Tetrahedron with vertices {0, 1, 5, 7}
- V0 = (0, 0, 0) = anchor
- V1 = (h, 0, 0) = anchor + (h, 0, 0)
- V5 = (h, 0, h) = anchor + (h, 0, h)
- V7 = (h, h, h) = anchor + (h, h, h)

### Type 5 (S5): Tetrahedron with vertices {0, 2, 6, 7}
- V0 = (0, 0, 0) = anchor
- V2 = (0, h, 0) = anchor + (0, h, 0)
- V6 = (0, h, h) = anchor + (0, h, h)
- V7 = (h, h, h) = anchor + (h, h, h)

## Verification Properties

1. **Shared Vertices**: All 6 tetrahedra share V0 (origin) and V7 (opposite corner)
2. **No Gaps**: Every point in the cube is in exactly one tetrahedron
3. **No Overlaps**: Tetrahedra only share faces, edges, or vertices
4. **Volume**: Each tetrahedron has volume h³/6, total = h³

## Visual Pattern

Looking at which vertices each type uses:
- Type 0: Bottom face edge (0-1) + top corner (3,7)
- Type 1: Bottom face edge (0-2) + top corner (3,7)
- Type 2: Vertical edge (0-4) + top edge (5,7)
- Type 3: Vertical edge (0-4) + top edge (6,7)
- Type 4: Bottom vertex + diagonal edge (1-5) + top corner (7)
- Type 5: Bottom vertex + diagonal edge (2-6) + top corner (7)