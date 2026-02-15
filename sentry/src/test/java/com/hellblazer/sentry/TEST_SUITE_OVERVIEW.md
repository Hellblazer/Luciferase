# Sentry Test Suite Overview

This document describes the comprehensive test suite for validating the MutableGrid and Grid functionality with a focus on Delaunay constraint validation.

## Test Files

### 1. MutableGridTest.java

The main test class containing:

- **smokin()**: Original smoke test with 2048 points and motion simulation
- **testBasicTracking()**: Basic vertex insertion and tracking
- **testContainment()**: Point containment validation within grid bounds
- **testDelaunayPropertySmallSet()**: Delaunay validation with controlled small point sets
- **testVertexRemoval()**: Vertex untracking functionality
- **testRebuild()**: Grid rebuild after vertex movement
- **testTetrahedronProperties()**: Individual tetrahedron validation
- **testVertexConnectivity()**: Vertex neighbor relationships and symmetry
- **testLocationQueries()**: Point location within tetrahedralization
- **testEdgeCases()**: Handling of degenerate cases (collinear, coplanar points)
- **testFlipOperations()**: Validation that flips maintain Delaunay property
- **testModeratePerformance()**: Performance scaling tests
- **testVoronoiRegions()**: Voronoi region computation
- **testConcurrentModificationSafety()**: Thread safety during iteration
- **testManualFlipValidation()**: Manual validation with known point configurations

### 2. DelaunayValidationTest.java

Focused Delaunay constraint validation with manual calculations:

- **testRegularTetrahedron()**: Validation with perfect regular tetrahedron
- **testCoSphericalPoints()**: Handling of points on same sphere
- **testGridPoints()**: Regular 3D grid validation
- **testDegenerateCases()**: Collinear and coplanar point handling
- **testFlipScenarios()**: 2-3 and 3-2 flip validation
- **testVertexMetrics()**: Detailed vertex property validation
- **testScalingBehavior()**: Performance scaling analysis

### 3. FlipAlgorithmValidationTest.java

Detailed flip algorithm validation:

- **test1to4Flip()**: Basic 1->4 flip operation
- **testFlipTopology()**: Topological consistency after flips
- **testFlip4to1()**: 4->1 flip validation
- **testCascadingFlips()**: Multiple cascading flip scenarios
- **testEarQueueManagement()**: Ear queue processing during flips
- **testDegenerateFlipCases()**: Handling of near-degenerate configurations

## Helper Methods Added

### Tetrahedron.java

- **circumsphereRadius()**: Calculate circumsphere radius
- **volume()**: Calculate tetrahedron volume
- **isDegenerate()**: Check for near-zero volume
- **minDihedralAngle()**: Calculate minimum dihedral angle for quality
- **isDelaunay()**: Validate Delaunay property against vertex set
- **getValidationMetrics()**: Comprehensive validation metrics

### Vertex.java

- **getStarSize()**: Count incident tetrahedra
- **isOnConvexHull()**: Check if vertex is on convex hull
- **getAverageEdgeLength()**: Calculate average edge length to neighbors
- **getValidationMetrics()**: Vertex validation metrics
- **hasValidStar()**: Validate star connectivity

## Validation Approach

1. **Delaunay Property**: For each tetrahedron, verify no other vertex lies within its circumsphere
2. **Topological Consistency**: Verify bidirectional neighbor relationships
3. **Geometric Validity**: Check for degenerate tetrahedra (near-zero volume)
4. **Connectivity**: Ensure all vertices are properly connected in the tetrahedralization
5. **Flip Correctness**: Validate that flip operations maintain all constraints

## Test Results

The tests reveal:

- The implementation correctly handles most cases
- Some Delaunay violations occur with specific point configurations
- The tests successfully identify these violations for debugging
- Performance scales well with point count
- Degenerate cases are handled without crashes

## Usage

Run all tests:

```bash

mvn test -pl sentry

```

Run specific test class:

```bash

mvn test -Dtest=MutableGridTest -pl sentry
mvn test -Dtest=DelaunayValidationTest -pl sentry
mvn test -Dtest=FlipAlgorithmValidationTest -pl sentry

```

Run specific test method:

```bash

mvn test -Dtest=MutableGridTest#testDelaunayPropertySmallSet -pl sentry

```
