# S0-S5 Point Classification Pattern Analysis

## Research Findings

From the comprehensive testing, I've discovered the key patterns that govern S0-S5 point classification:

### **Critical Insights**

1. **Coordinate Dominance Pattern**: 
   - **X-dominant regions** (x > y, x > z): Contained by S0, S4
   - **Y-dominant regions** (y > x, y > z): Contained by S1, S5  
   - **Z-dominant regions** (z > x, z > y): Contained by S2, S3

2. **Multiple Containment on Boundaries**:
   - Many points are contained by **multiple tetrahedra simultaneously**
   - This explains the visualization issue: the "first match" algorithm is non-deterministic
   - Points on edges/faces belong to 2+ tetrahedra

3. **Key Geometric Regions**:
   - **All vertices** (V0, V7) and **cube center** are contained by **all 6 tetrahedra**
   - **Face centers** are contained by exactly **2 tetrahedra**
   - **Edge midpoints** show clear patterns based on coordinate dominance

### **Deterministic Classification Strategy**

The research reveals we need a **hierarchical decision tree** that assigns each point to exactly one tetrahedron based on geometric precedence rules:

#### **Primary Classification: Coordinate Dominance**
```
if (x >= y && x >= z) {
    // X-dominant region -> S0 or S4
    return (x + y + z >= 1.5) ? 0 : 4;
} else if (y >= x && y >= z) {
    // Y-dominant region -> S1 or S5  
    return (x + y + z >= 1.5) ? 1 : 5;
} else {
    // Z-dominant region -> S2 or S3
    return (x + y + z >= 1.5) ? 2 : 3;
}
```

#### **Secondary Classification: Diagonal Split**

From the diagonal analysis, the sum `x + y + z` provides a secondary classification:
- **Lower diagonal** (sum < 1.5): Types 3, 4, 5 (closer to origin)
- **Upper diagonal** (sum ≥ 1.5): Types 0, 1, 2 (closer to opposite corner)

### **S0-S5 Tetrahedra Characteristics**

#### **Lower Diagonal Group** (sum < 1.5)
- **S3**: Z-dominant, lower diagonal → vertices (0,0,0), (0,0,1), (0,1,1), (1,1,1)
- **S4**: X-dominant, lower diagonal → vertices (0,0,0), (1,0,0), (1,0,1), (1,1,1)  
- **S5**: Y-dominant, lower diagonal → vertices (0,0,0), (0,1,0), (0,1,1), (1,1,1)

#### **Upper Diagonal Group** (sum ≥ 1.5)
- **S0**: X-dominant, upper diagonal → vertices (0,0,0), (1,0,0), (1,1,0), (1,1,1)
- **S1**: Y-dominant, upper diagonal → vertices (0,0,0), (0,1,0), (1,1,0), (1,1,1)
- **S2**: Z-dominant, upper diagonal → vertices (0,0,0), (0,0,1), (1,0,1), (1,1,1)

### **Algorithm Design**

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

### **Boundary Handling**

The algorithm handles boundary conditions deterministically:

1. **Equal coordinates** (tie-breaking):
   - When `x == y`: X-dominant wins (arbitrary but consistent choice)
   - When `y == z`: Y-dominant wins  
   - When `x == z`: X-dominant wins

2. **Diagonal boundary** (sum == 1.5):
   - Upper diagonal wins (>= comparison)

3. **Corner cases**:
   - Origin (0,0,0): X-dominant, lower diagonal → **S4**
   - Opposite corner (1,1,1): All equal (X wins), upper diagonal → **S0**
   - Center (0.5,0.5,0.5): All equal (X wins), diagonal boundary → **S0**

### **Validation Strategy**

The new algorithm should be tested against the current `contains()` results:

1. **Generate test points** across the cube
2. **Apply new classification** algorithm  
3. **Verify containment** using `tetrahedral.contains(point)`
4. **Ensure 100% success rate** with no ambiguity

This deterministic approach will eliminate the non-deterministic behavior causing the visualization containment problem.

## Next Steps

1. Implement the `classifyPointInCube()` method
2. Update `Tetree.locate()` to use deterministic classification
3. Test extensively against boundary conditions
4. Validate performance improvements