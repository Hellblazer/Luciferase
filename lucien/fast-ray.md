# Fast Ray-Tetrahedron Intersection Using Plücker Coordinates

**Authors:** Nikos Platis and Theoharis Theoharis  
**Institution:** University of Athens, Department of Informatics & Telecommunications  
**Publication:** Journal of Graphics Tools, January 2003

## Abstract

This paper presents an algorithm for ray-tetrahedron intersection using Plücker coordinates to represent rays and
tetrahedron edges. The algorithm provides robust, efficient intersection testing with significant performance
improvements over existing methods.

## Key Concepts

### Problem Definition

- **Input:** A ray (defined by point P and direction L) and a tetrahedron (4 vertices: V₀, V₁, V₂, V₃)
- **Output:**
    - Intersection points (Pₑₙₜₑᵣ, Pₗₑₐᵥₑ)
    - Barycentric coordinates
    - Parametric distances along ray

### Tetrahedron Notation

```
Vertices: V₀, V₁, V₂, V₃
Faces: F₃(V₀V₁V₂), F₂(V₁V₀V₃), F₁(V₂V₃V₀), F₀(V₃V₂V₁)
Face Fᵢ vertices: V⁰ᵢ, V¹ᵢ, V²ᵢ (e.g., F₂: V⁰₂=V₁, V¹₂=V₀, V²₂=V₃)
Face Fᵢ edges: e⁰ᵢ(V¹ᵢV²ᵢ), e¹ᵢ(V²ᵢV⁰ᵢ), e²ᵢ(V⁰ᵢV¹ᵢ)
```

## Plücker Coordinates

### Definition

For a ray r with point P and direction L:

```
πᵣ = {L : L × P} = {Uᵣ : Vᵣ}
```

### Key Property - Permuted Inner Product

```
πᵣ ⊙ πₛ = Uᵣ · Vₛ + Uₛ · Vᵣ
```

**Geometric Interpretation:**

- `πᵣ ⊙ πₛ > 0` ⟺ s goes counterclockwise around r
- `πᵣ ⊙ πₛ < 0` ⟺ s goes clockwise around r
- `πᵣ ⊙ πₛ = 0` ⟺ s intersects or is parallel to r

## Ray-Triangle Intersection Test

For ray r and triangle Δ(V₀,V₁,V₂) with edges e₀(V₁V₂), e₁(V₂V₀), e₂(V₀V₁):

```
r intersects (enters) Δ ⟺ πᵣ ⊙ πₑᵢ ≥ 0 ∀i and ∃j : πᵣ ⊙ πₑⱼ ≠ 0
r intersects (leaves) Δ ⟺ πᵣ ⊙ πₑᵢ ≤ 0 ∀i and ∃j : πᵣ ⊙ πₑⱼ ≠ 0
r is coplanar with Δ ⟺ πᵣ ⊙ πₑᵢ = 0 ∀i
```

### Barycentric Coordinates

```
wᵏᵢ = πᵣ ⊙ πₑᵢ
uᵏᵢ = wᵏᵢ / Σ³ᵢ₌₀ wᵏᵢ
```

## Algorithm Implementation

### Basic Algorithm

```pseudo
Fₑₙₜₑᵣ = nil
Fₗₑₐᵥₑ = nil

for i = 3, 2, 1, 0 do
    Compute σ⁰ᵢ, σ¹ᵢ, σ²ᵢ  // signs of πᵣ ⊙ π^i_j
    
    if ((σ⁰ᵢ ≠ 0) or (σ¹ᵢ ≠ 0) or (σ²ᵢ ≠ 0))
        if ((Fₑₙₜₑᵣ == nil) and (σ⁰ᵢ ≥ 0) and (σ¹ᵢ ≥ 0) and (σ²ᵢ ≥ 0))
            Fₑₙₜₑᵣ = Fᵢ
        else if ((Fₗₑₐᵥₑ == nil) and (σ⁰ᵢ ≤ 0) and (σ¹ᵢ ≤ 0) and (σ²ᵢ ≤ 0))
            Fₗₑₐᵥₑ = Fᵢ
        end if
    end if
end for
```

Where:

```
σʲᵢ = sign(πᵣ ⊙ πʲᵢ) = {1 if > 0, 0 if = 0, -1 if < 0}
```

### Optimized Algorithm

#### Early Termination Optimizations:

1. **Stop when both faces found:** Exit loop when Fₑₙₜₑᵣ and Fₗₑₐᵥₑ both identified
2. **Test only 3 faces:** If 3 faces don't intersect, 4th won't either
3. **Reuse edge computations:** Each edge shared by 2 faces, compute once

#### Sign Test Optimization:

```pseudo
Compute σ⁰ᵢ and σ¹ᵢ
if ((σ⁰ᵢ == σ¹ᵢ) or (σ⁰ᵢ == 0) or (σ¹ᵢ == 0))
    Compute σ²ᵢ
    
    // Find face orientation
    σⁱ = σ⁰ᵢ
    if (σⁱ == 0) σⁱ = σ¹ᵢ
    if (σⁱ == 0) σⁱ = σ²ᵢ
    
    if ((σⁱ ≠ 0) and ((σ²ᵢ == σⁱ) or (σ²ᵢ == 0)))
        if (σⁱ > 0)
            Fₑₙₜₑᵣ = Fᵢ
        else
            Fₗₑₐᵥₑ = Fᵢ
        end if
    end if
end if
```

## Performance Results

The paper shows performance comparisons against:

- Haines' ray-convex polyhedron algorithm
- Möller/Trumbore ray-triangle based approach

**Key findings:**

- Plücker coordinate method outperforms alternatives in all test cases
- Section 3.2 optimizations provide significant speedup
- Performance advantage holds for 0% to 100% intersection rates
- Method particularly efficient for tetrahedral mesh ray tracing

## Implementation Notes for Claude Code

### Data Structures Needed:

```cpp
struct Point3D { float x, y, z; };
struct Vector3D { float x, y, z; };
struct Ray { Point3D origin; Vector3D direction; };
struct Tetrahedron { Point3D vertices[4]; };
struct PluckerCoord { Vector3D U, V; };  // {direction : direction × point}
```

### Key Functions:

1. `computePluckerCoords(Ray r) -> PluckerCoord`
2. `permutedInnerProduct(PluckerCoord a, PluckerCoord b) -> float`
3. `rayTetrahedronIntersect(Ray r, Tetrahedron t) -> IntersectionResult`

### Edge Cases to Handle:

- Ray coplanar with face (all σʲᵢ = 0)
- Ray intersecting edge/vertex (some σʲᵢ = 0)
- No intersection
- Ray inside tetrahedron

## References & Further Reading

The paper includes sample C++ implementation available at:
`http://www.acm.org/jgt/papers/PlatisTheoharis03`

**Related algorithms:**

- Haines ray-convex polyhedron intersection
- Möller/Trumbore ray-triangle intersection
- Liang-Barsky line clipping

**Mathematical background:**

- Plücker coordinates and oriented projective geometry
- Barycentric coordinate systems
- Computational geometry intersection algorithms
