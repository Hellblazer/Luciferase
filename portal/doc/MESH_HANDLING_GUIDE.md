# Mesh Handling Guide

## Overview

The Portal module's mesh handling system provides comprehensive support for 3D polygon meshes, including loading from files, procedural generation, and complex geometric operations.

## Core Concepts

### Mesh Representation

The system uses an indexed face representation where:

- Vertices are stored as a list of 3D points (Vector3d)
- Faces reference vertices by index
- Normals can be per-vertex or per-face
- Supports arbitrary polygon faces (not limited to triangles)

### File Format Support

#### OBJ Format

- Full support for vertices, normals, texture coordinates, and faces
- Handles complex polygons (automatically triangulated for rendering)
- Preserves material and group information

#### STL Format

- Binary and ASCII STL support
- Automatic normal calculation if not provided
- Efficient loading of large mesh files

## Basic Usage

### Loading a Mesh

```java

// Load from OBJ file
Mesh mesh = MeshLoader.loadObj("model.obj");

// Load from STL file
Mesh mesh = MeshLoader.loadStl("model.stl");

// Create JavaFX MeshView for rendering
MeshView view = MeshLoader.loadMeshView("model.obj");

```text

### Creating Meshes Programmatically

```java

// Create a simple cube
Cube cube = new Cube(1.0); // 1.0 = edge length
Mesh cubeMesh = cube.getMesh();

// Create an icosahedron
Icosahedron ico = new Icosahedron(2.0);
Mesh icoMesh = ico.getMesh();

// Create a sphere approximation
Icosphere sphere = new Icosphere(1.0, 3); // radius=1.0, subdivisions=3
Mesh sphereMesh = sphere.getMesh();

```text

### Working with Faces

```java

Mesh mesh = // ... load or create mesh

// Iterate through faces
for (Face face : mesh.getFaces()) {
    // Get vertex indices
    int[] vertices = face.getV();
    
    // Get actual vertex positions
    Vector3d v0 = mesh.getVertices().get(vertices[0]);
    
    // Calculate face centroid
    Vector3d centroid = face.calculateCentroid();
    
    // Get face normal
    Vector3d normal = face.calculateNormal();
}

```text

## Conway Operations

The system supports all standard Conway polyhedron operations:

### Basic Operations

```java

Polyhedron poly = new Cube(1.0);

// Ambo (rectification) - creates vertices at edge midpoints
Polyhedron ambo = poly.ambo();

// Dual - swaps faces and vertices
Polyhedron dual = poly.dual();

// Truncate - cuts off vertices
Polyhedron truncated = poly.truncate();

// Kis - adds pyramids to faces
Polyhedron kis = poly.kis();

```text

### Advanced Operations

```java

// Expand (bevel) - separates faces and edges
Polyhedron expanded = poly.expand();

// Gyro - creates pentagonal faces
Polyhedron gyro = poly.gyro();

// Propeller - twists the polyhedron
Polyhedron propeller = poly.propeller();

// Snub - creates snub polyhedron
Polyhedron snub = poly.snub();

// Reflect - creates mirror image
Polyhedron reflected = poly.reflect();

```text

### Chaining Operations

```java

// Create a truncated icosahedron (soccer ball)
Polyhedron soccerBall = new Icosahedron(1.0)
    .truncate();

// Create a geodesic sphere
Polyhedron geodesic = new Icosahedron(1.0)
    .subdivide(3)  // Goldberg subdivision
    .normalize(1.0); // Project to sphere

```text

## Mesh Topology

### Adjacency Structures

```java

Mesh mesh = // ... your mesh

// Build edge-to-face adjacency
EdgeToAdjacentFace edgeAdj = new EdgeToAdjacentFace(mesh);
List<Face> adjacentFaces = edgeAdj.getAdjacentFaces(edge);

// Build face-to-face adjacency
FaceToAdjacentFace faceAdj = new FaceToAdjacentFace(mesh);
List<Face> neighbors = faceAdj.getAdjacentFaces(face);

// Build vertex-to-face adjacency
VertexToAdjacentFace vertexAdj = new VertexToAdjacentFace(mesh);
List<Face> facesAroundVertex = vertexAdj.getAdjacentFaces(vertexIndex);

```text

### Ordered Adjacency

```java

// Get ordered edges around a vertex
OrderedVertexToAdjacentEdge orderedEdges = 
    new OrderedVertexToAdjacentEdge(mesh);
List<Edge> edgeRing = orderedEdges.getOrderedEdges(vertexIndex);

// Get ordered faces around a vertex
OrderedVertexToAdjacentFace orderedFaces = 
    new OrderedVertexToAdjacentFace(mesh);
List<Face> faceRing = orderedFaces.getOrderedFaces(vertexIndex);

```text

## Mesh Operations

### Normal Generation

```java

// Generate vertex normals (smooth shading)
mesh.generateNormals();

// Access generated normals
List<Vector3d> normals = mesh.getNormals();

```text

### Triangulation

```java

// Convert to JavaFX TriangleMesh (triangulates automatically)
TriangleMesh triMesh = mesh.toTriangleMesh();

// Manual triangulation of a face
Face quad = // ... 4-vertex face
List<int[]> triangles = quad.toTriangles();

```text

### Export

```java

// Export to OBJ format
String objContent = mesh.toObj();
Files.write(Paths.get("output.obj"), objContent.getBytes());

```text

## Performance Tips

1. **Use Indexed Representation**: Share vertices between faces to reduce memory
2. **Batch Operations**: Perform multiple operations before converting to rendering format
3. **Level of Detail**: Use subdivision levels appropriate for viewing distance
4. **Cache Adjacency**: Reuse adjacency structures when performing multiple queries

## Common Patterns

### Creating Custom Polyhedra

```java

public class MyPolyhedron extends Polyhedron {
    public MyPolyhedron() {
        // Define vertices
        addVertex(new Vector3d(0, 0, 0));
        addVertex(new Vector3d(1, 0, 0));
        // ... more vertices
        
        // Define faces (counterclockwise winding)
        addFace(new int[]{0, 1, 2});
        // ... more faces
        
        // Generate normals
        generateNormals();
    }
}

```text

### Processing Mesh Geometry

```java

// Scale a mesh
for (Vector3d vertex : mesh.getVertices()) {
    vertex.scale(2.0); // Double the size
}

// Center a mesh at origin
Vector3d centroid = mesh.calculateCentroid();
for (Vector3d vertex : mesh.getVertices()) {
    vertex.sub(centroid);
}

```text

## Visualization Integration

```java

// Create mesh and convert to JavaFX
Mesh mesh = new Icosphere(1.0, 4);
TriangleMesh triMesh = mesh.toTriangleMesh();

// Create 3D shape for scene
MeshView meshView = new MeshView(triMesh);
meshView.setMaterial(new PhongMaterial(Color.BLUE));
meshView.setDrawMode(DrawMode.FILL);

// Add to scene
group.getChildren().add(meshView);

```text
