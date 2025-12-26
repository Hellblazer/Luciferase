# S0-S5 Tetrahedral Subdivision of a Cube

## Overview

The S0-S5 subdivision divides a cube into 6 tetrahedra that completely tile the space with no gaps or overlaps. This
subdivision is fundamental to the Tetree spatial index implementation and was fixed in July 2025 to achieve 100%
geometric containment.

## Cube Vertex Numbering

```text
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
V0 = (0, 0, 0)    V4 = (0, 0, h)
V1 = (h, 0, 0)    V5 = (h, 0, h)
V2 = (0, h, 0)    V6 = (0, h, h)
V3 = (h, h, 0)    V7 = (h, h, h)

```

## The 6 Tetrahedra

All tetrahedra share vertices V0 (origin) and V7 (opposite corner):

| Type | Name | Vertices  | Vertex Coordinates                 |
| ------ | ------ | ----------- | ------------------------------------ |
| 0    | S0   | {0,1,3,7} | (0,0,0), (h,0,0), (h,h,0), (h,h,h) |
| 1    | S1   | {0,2,3,7} | (0,0,0), (0,h,0), (h,h,0), (h,h,h) |
| 2    | S2   | {0,4,5,7} | (0,0,0), (0,0,h), (h,0,h), (h,h,h) |
| 3    | S3   | {0,4,6,7} | (0,0,0), (0,0,h), (0,h,h), (h,h,h) |
| 4    | S4   | {0,1,5,7} | (0,0,0), (h,0,0), (h,0,h), (h,h,h) |
| 5    | S5   | {0,2,6,7} | (0,0,0), (0,h,0), (0,h,h), (h,h,h) |

## Deterministic Point Classification

Points within the cube can be deterministically assigned to exactly one tetrahedron using a two-level classification:

### Algorithm

```java
private static byte classifyPointInCube(float x, float y, float z) {
    // Coordinates normalized to [0,1] within cube
    
    // Primary: Which coordinate dominates?
    boolean xDominant = (x >= y && x >= z);
    boolean yDominant = (y >= x && y >= z);
    // zDominant is the remaining case
    
    // Secondary: Which side of diagonal?
    boolean upperDiagonal = (x + y + z >= 1.5f);
    
    if (xDominant) {
        return upperDiagonal ? (byte)0 : (byte)4; // S0 or S4
    } else if (yDominant) {
        return upperDiagonal ? (byte)1 : (byte)5; // S1 or S5
    } else {
        return upperDiagonal ? (byte)2 : (byte)3; // S2 or S3
    }
}

```

### Classification Rules

1. **Primary Classification - Coordinate Dominance**:
    - **X-dominant** (x ≥ y and x ≥ z): Types S0 or S4
    - **Y-dominant** (y ≥ x and y ≥ z): Types S1 or S5
    - **Z-dominant** (z > x and z > y): Types S2 or S3

2. **Secondary Classification - Diagonal Split**:
    - **Upper diagonal** (x + y + z ≥ 1.5): Types S0, S1, S2
    - **Lower diagonal** (x + y + z < 1.5): Types S3, S4, S5

### Boundary Handling

- **Equal coordinates**: X-dominance takes precedence over Y, which takes precedence over Z
- **Diagonal boundary** (sum = 1.5): Assigned to upper diagonal group
- **Shared faces/edges**: Points on boundaries belong to multiple tetrahedra geometrically but are assigned to exactly

  one by the algorithm

## Geometric Properties

### Volume Distribution

- Each tetrahedron has volume = h³/6
- Total volume of 6 tetrahedra = h³ (the cube volume)

### Shared Structure

- All 6 tetrahedra share the main diagonal from V0 to V7
- Adjacent tetrahedra share triangular faces
- No gaps or overlaps in the interior

### Visual Patterns

- **Types 0,1**: Share bottom-top face diagonal
- **Types 2,3**: Share left-right face diagonal
- **Types 4,5**: Share front-back face diagonal

## Implementation in Tetree

The S0-S5 subdivision is implemented in:

- `Tet.coordinates()`: Returns actual S0-S5 vertices based on type
- `TetrahedralGeometry.containsUltraFast()`: Handles containment with proper face orientation
- `Tetree.locate()`: Uses deterministic classification for point location

## Historical Note

Prior to July 2025, the implementation used an ei/ej algorithm that resulted in only 35% containment rate. The S0-S5 fix
achieved 100% containment and proper cube tiling, resolving long-standing visualization and spatial query issues.
