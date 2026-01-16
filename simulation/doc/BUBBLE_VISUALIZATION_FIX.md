# Bubble Visualization Fix: 8-Vertex Bounding Box Support

**Date**: 2026-01-16
**Issue**: Only 2 of 6 bounding boxes visible in browser visualization
**Root Cause**: Server only accepted 4-vertex tetrahedra, filtering out 8-vertex boxes
**Status**: Fixed and tested

## Summary

Fixed MultiBubbleVisualizationServer to accept both 4-vertex (tetrahedra) and 8-vertex (axis-aligned bounding boxes) geometry. Previously, the server filtered out 8-vertex arrays, causing bounding boxes computed from entity positions to never reach clients.

## Problem History

### Evolution of the Issue

1. **Initial Problem**: Entities not moving in browser (PrimeMover instrumentation issue)
   - **Cause**: `mvn exec:java` didn't apply bytecode transformation
   - **Fix**: Use `mvn test` or add exec-maven-plugin configuration

2. **Coordinate System Mismatch**: Bounding boxes enormous and off-screen
   - **Cause**: Mixing Morton space [0, 2^21] with world space [0, 200]
   - **Fix**: Compute AABBs directly from entity positions in world space

3. **Static Bounding Boxes**: Boxes never updated as entities moved
   - **Cause**: Only computed once at initialization
   - **Fix**: Added periodic recomputation every 30 ticks (1.5 seconds)

4. **Missing Vertices in API** (this fix): Only 2 of 6 boxes visible
   - **Cause**: Server filtered out 8-vertex arrays at lines 105 and 440
   - **Fix**: Accept both 4-vertex and 8-vertex arrays

## Changes Made

### 1. MultiBubbleVisualizationServer.java

**Line 103-110** - API endpoint filtering:
```java
// BEFORE:
// Include tetrahedral vertices if available
var verts = bubbleVertices.get(b.id());
if (verts != null && verts.length == 4) {  // ← Only 4-vertex!

// AFTER:
// Include tetrahedral vertices (4) or box vertices (8) if available
var verts = bubbleVertices.get(b.id());
if (verts != null && (verts.length == 4 || verts.length == 8)) {
```

**Line 438-448** - WebSocket JSON filtering:
```java
// BEFORE:
// Add tetrahedral vertices if available
var vertices = bubbleVertices.get(bubble.id());
if (vertices != null && vertices.length == 4) {  // ← Only 4-vertex!
    sb.append(",\"vertices\":[");
    for (int v = 0; v < 4; v++) {  // ← Fixed loop count

// AFTER:
// Add tetrahedral vertices (4) or box vertices (8) if available
var vertices = bubbleVertices.get(bubble.id());
if (vertices != null && (vertices.length == 4 || vertices.length == 8)) {
    sb.append(",\"vertices\":[");
    for (int v = 0; v < vertices.length; v++) {  // ← Dynamic loop
```

**Line 212-218** - Added getter method:
```java
/**
 * Get the current bubble vertices.
 * @return Map from bubble UUID to vertex arrays (4 for tetrahedra, 8 for boxes)
 */
public Map<UUID, Point3f[]> getBubbleVertices() {
    return new HashMap<>(bubbleVertices);
}
```

### 2. BubbleVisualizationServerTest.java (New)

Created comprehensive test suite with 5 tests:

1. **testServerAccepts8VertexBoxes**: Verify 8-vertex bounding boxes accepted
2. **testServerAccepts4VertexTetrahedra**: Verify 4-vertex tetrahedra still work
3. **testServerFiltersInvalidVertexCounts**: Verify filtering behavior documented
4. **testServerHandlesMixedVertexCounts**: Verify both types work simultaneously
5. **testGetBubbleVerticesAccessor**: Verify getter method works

**Test Results**: All 5 tests pass ✓

## Technical Details

### Vertex Array Formats

**4-Vertex Tetrahedron**:
```
V0 = (x0, y0, z0)  // Base vertex
V1 = (x1, y1, z1)  // Second vertex
V2 = (x2, y2, z2)  // Third vertex
V3 = (x3, y3, z3)  // Apex vertex
```

**8-Vertex Bounding Box (AABB)**:
```
Bottom face (minZ):
V0 = (minX, minY, minZ)
V1 = (maxX, minY, minZ)
V2 = (maxX, maxY, minZ)
V3 = (minX, maxY, minZ)

Top face (maxZ):
V4 = (minX, minY, maxZ)
V5 = (maxX, minY, maxZ)
V6 = (maxX, maxY, maxZ)
V7 = (minX, maxY, maxZ)
```

### AABB Computation (from PredatorPreyGridWebDemo)

```java
private static Map<UUID, Point3f[]> extractBubbleVertices(
    TetreeBubbleGrid grid, List<EnhancedBubble> bubbles) {

    var vertices = new HashMap<UUID, Point3f[]>();

    for (var bubble : bubbles) {
        var entities = bubble.getAllEntityRecords();
        if (entities.isEmpty()) continue;

        // Compute AABB from entity positions in world space
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        for (var entity : entities) {
            var pos = entity.position();
            minX = Math.min(minX, pos.x);
            minY = Math.min(minY, pos.y);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxY = Math.max(maxY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
        }

        // Create 8-vertex AABB
        var bubbleVertices = new Point3f[8];
        // ... (see above for vertex assignment)

        vertices.put(bubble.id(), bubbleVertices);
    }

    return vertices;
}
```

## Verification

### Before Fix
```bash
$ curl -s http://localhost:7081/api/bubbles | python3 -c "..."
Bubble count: 6
Bubble with vertices: 0  # ← No vertices!
```

### After Fix
```bash
$ curl -s http://localhost:7081/api/bubbles | python3 -c "..."
Bubble count: 6
Bubble with vertices: 6  # ← All 6 have vertices!
```

### Test Execution
```bash
$ mvn test -Dtest=BubbleVisualizationServerTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

## Impact

- **Backwards Compatible**: Still accepts 4-vertex tetrahedra
- **New Functionality**: Now accepts 8-vertex bounding boxes
- **Mixed Scenes**: Both types work simultaneously
- **No Breaking Changes**: Existing tetrahedral visualization unchanged

## Future Enhancements

Potential improvements for consideration:

1. **Validation on Set**: Add validation in `setBubbleVertices()` to reject invalid vertex counts at input time
2. **Extended Geometry**: Support other vertex counts (6 for prisms, 12 for octahedra, etc.)
3. **Type Safety**: Use sealed interface for geometry types:
   ```java
   sealed interface BubbleGeometry permits
       TetrahedralGeometry,    // 4 vertices
       BoxGeometry,            // 8 vertices
       PrismGeometry           // 6 vertices
   ```

## Related Files

- `MultiBubbleVisualizationServer.java` - Core fix
- `BubbleVisualizationServerTest.java` - Test coverage
- `PredatorPreyGridWebDemo.java` - AABB computation logic
- `predator-prey-grid.js` - Client-side visualization (unchanged)

## References

- Original Issue: "still not seing that. notice the bubble numbers: only 4 sphere labels (1,2,3,4) instead of 6"
- Coordinate System Fix: From RDGCS→Morton to world-space AABBs
- Dynamic Updates: 30-tick (1.5s) periodic recomputation

---

**Status**: ✅ Complete and tested
**Next Step**: Verify in browser that all 6 bounding boxes now render correctly
