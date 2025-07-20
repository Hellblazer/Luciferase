/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the 3D Incremental Voronoi system
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.sentry;

/**
 * Validation framework for ensuring invariants are maintained in the Delaunay triangulation.
 * This class can be enabled during testing/debugging to catch violations early.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class ValidationManager {
    
    private final MutableGrid grid;
    private boolean validationEnabled = false;
    private GeometricPredicates predicates;
    
    public ValidationManager(MutableGrid grid) {
        this.grid = grid;
        // Use the same predicates as the grid for validation
        this.predicates = grid.getPredicates();
    }
    
    /**
     * Enable or disable validation.
     * When enabled, validation will be performed after each operation.
     */
    public void enableValidation(boolean enable) {
        this.validationEnabled = enable;
    }
    
    /**
     * Set the geometric predicates to use for validation.
     * By default, exact predicates are used.
     */
    public void setPredicates(GeometricPredicates predicates) {
        this.predicates = predicates;
    }
    
    /**
     * Get the geometric predicates being used for validation.
     */
    public GeometricPredicates getPredicates() {
        return predicates;
    }
    
    /**
     * Validate all invariants of the Delaunay triangulation.
     * 
     * @throws AssertionError if any invariant is violated
     */
    public void validateInvariants() {
        if (!validationEnabled) {
            return;
        }
        
        validateDelaunayProperty();
        validateVertexTetrahedronBidirectionalConsistency();
        validateTetrahedronNeighborConsistency();
        validateConvexHull();
    }
    
    /**
     * Validate that the Delaunay property holds for all tetrahedra.
     * No vertex should be inside the circumsphere of any tetrahedron.
     */
    private void validateDelaunayProperty() {
        int violationCount = 0;
        
        for (Tetrahedron t : grid.tetrahedrons()) {
            if (t.isDeleted() || t.isDegenerate()) {
                continue;
            }
            
            // Get vertices of tetrahedron
            Vertex a = t.getA();
            Vertex b = t.getB();
            Vertex c = t.getC();
            Vertex d = t.getD();
            
            // Check all other vertices
            for (Vertex v : grid) {
                if (v == a || v == b || v == c || v == d) {
                    continue; // Skip vertices of this tetrahedron
                }
                
                // Test if vertex is inside circumsphere
                double result = predicates.inSphere(
                    a.x, a.y, a.z,
                    b.x, b.y, b.z,
                    c.x, c.y, c.z,
                    d.x, d.y, d.z,
                    v.x, v.y, v.z
                );
                
                if (result > 0) {
                    violationCount++;
                    if (violationCount == 1) {
                        System.err.println("Delaunay violation detected: vertex " + v + 
                            " is inside circumsphere of tetrahedron " + t);
                    }
                }
            }
        }
        
        if (violationCount > 0) {
            throw new AssertionError("Delaunay property violated: " + violationCount + 
                " vertices found inside circumspheres");
        }
    }
    
    /**
     * Validate that vertex-tetrahedron references are bidirectionally consistent.
     */
    private void validateVertexTetrahedronBidirectionalConsistency() {
        // Check that every vertex has a valid adjacent tetrahedron
        for (Vertex v : grid) {
            if (v.getAdjacent() == null) {
                throw new AssertionError("Vertex " + v + " missing adjacent tetrahedron reference");
            }
            
            if (v.getAdjacent().isDeleted()) {
                throw new AssertionError("Vertex " + v + " references deleted tetrahedron");
            }
            
            if (!v.getAdjacent().includes(v)) {
                throw new AssertionError("Inconsistent vertex-tetrahedron reference: " +
                    "vertex " + v + " references tetrahedron that doesn't contain it");
            }
        }
        
        // Check that every tetrahedron vertex has proper adjacent reference
        for (Tetrahedron t : grid.tetrahedrons()) {
            if (t.isDeleted()) {
                continue;
            }
            
            checkVertexAdjacent(t.getA(), t);
            checkVertexAdjacent(t.getB(), t);
            checkVertexAdjacent(t.getC(), t);
            checkVertexAdjacent(t.getD(), t);
        }
    }
    
    private void checkVertexAdjacent(Vertex v, Tetrahedron t) {
        if (v == null) {
            return; // Skip null vertices (boundary vertices)
        }
        
        if (v.getAdjacent() == null) {
            throw new AssertionError("Vertex " + v + " in tetrahedron " + t + 
                " has null adjacent reference");
        }
    }
    
    /**
     * Validate that tetrahedron neighbor relationships are consistent.
     */
    private void validateTetrahedronNeighborConsistency() {
        for (Tetrahedron t : grid.tetrahedrons()) {
            if (t.isDeleted()) {
                continue;
            }
            
            for (V v : V.values()) {
                Tetrahedron neighbor = t.getNeighbor(v);
                if (neighbor != null && !neighbor.isDeleted()) {
                    // Find which face of neighbor points back to t
                    V neighborV = neighbor.ordinalOf(t);
                    if (neighborV == null) {
                        throw new AssertionError("Inconsistent neighbor relationship: " +
                            "tetrahedron " + t + " has neighbor " + neighbor + 
                            " but neighbor doesn't point back");
                    }
                    
                    // Verify the shared face has 3 common vertices
                    OrientedFace face1 = t.getFace(v);
                    OrientedFace face2 = neighbor.getFace(neighborV);
                    
                    int commonVertices = 0;
                    for (Vertex v1 : face1) {
                        for (Vertex v2 : face2) {
                            if (v1 == v2) {
                                commonVertices++;
                                break;
                            }
                        }
                    }
                    
                    if (commonVertices != 3) {
                        throw new AssertionError("Neighbor faces don't share 3 vertices: " +
                            "only " + commonVertices + " common vertices between faces");
                    }
                }
            }
        }
    }
    
    /**
     * Validate that the convex hull is properly maintained.
     */
    private void validateConvexHull() {
        // Check that boundary tetrahedra have exactly one null neighbor
        int boundaryTetrahedra = 0;
        
        for (Tetrahedron t : grid.tetrahedrons()) {
            if (t.isDeleted()) {
                continue;
            }
            
            int nullNeighbors = 0;
            for (V v : V.values()) {
                if (t.getNeighbor(v) == null) {
                    nullNeighbors++;
                }
            }
            
            if (nullNeighbors > 0) {
                boundaryTetrahedra++;
                if (nullNeighbors != 1) {
                    throw new AssertionError("Boundary tetrahedron " + t + 
                        " has " + nullNeighbors + " null neighbors (expected 1)");
                }
            }
        }
        
        if (boundaryTetrahedra == 0) {
            throw new AssertionError("No boundary tetrahedra found - convex hull not properly formed");
        }
    }
    
    /**
     * Create a validation report without throwing exceptions.
     * Useful for debugging and analysis.
     */
    public ValidationReport createReport() {
        ValidationReport report = new ValidationReport();
        
        // Count Delaunay violations
        for (Tetrahedron t : grid.tetrahedrons()) {
            if (t.isDeleted() || t.isDegenerate()) {
                continue;
            }
            
            Vertex a = t.getA();
            Vertex b = t.getB();
            Vertex c = t.getC();
            Vertex d = t.getD();
            
            for (Vertex v : grid) {
                if (v == a || v == b || v == c || v == d) {
                    continue;
                }
                
                double result = predicates.inSphere(
                    a.x, a.y, a.z,
                    b.x, b.y, b.z,
                    c.x, c.y, c.z,
                    d.x, d.y, d.z,
                    v.x, v.y, v.z
                );
                
                if (result > 0) {
                    report.delaunayViolations++;
                }
            }
        }
        
        // Count vertex reference issues
        for (Vertex v : grid) {
            if (v.getAdjacent() == null) {
                report.missingVertexReferences++;
            } else if (v.getAdjacent().isDeleted()) {
                report.staleVertexReferences++;
            } else if (!v.getAdjacent().includes(v)) {
                report.inconsistentVertexReferences++;
            }
        }
        
        // Count neighbor consistency issues
        for (Tetrahedron t : grid.tetrahedrons()) {
            if (t.isDeleted()) {
                continue;
            }
            
            for (V v : V.values()) {
                Tetrahedron neighbor = t.getNeighbor(v);
                if (neighbor != null && !neighbor.isDeleted()) {
                    V neighborV = neighbor.ordinalOf(t);
                    if (neighborV == null) {
                        report.inconsistentNeighborRelations++;
                    }
                }
            }
        }
        
        // Count degenerate tetrahedra
        for (Tetrahedron t : grid.tetrahedrons()) {
            if (!t.isDeleted() && t.isDegenerate()) {
                report.degenerateTetrahedra++;
            }
        }
        
        report.totalVertices = grid.size();
        report.totalTetrahedra = 0;
        for (Tetrahedron t : grid.tetrahedrons()) {
            if (!t.isDeleted()) {
                report.totalTetrahedra++;
            }
        }
        
        return report;
    }
    
    /**
     * Validation report containing statistics about the triangulation.
     */
    public static class ValidationReport {
        public int totalVertices = 0;
        public int totalTetrahedra = 0;
        public int delaunayViolations = 0;
        public int missingVertexReferences = 0;
        public int staleVertexReferences = 0;
        public int inconsistentVertexReferences = 0;
        public int inconsistentNeighborRelations = 0;
        public int degenerateTetrahedra = 0;
        
        @Override
        public String toString() {
            return String.format(
                "Validation Report:\n" +
                "  Total vertices: %d\n" +
                "  Total tetrahedra: %d\n" +
                "  Delaunay violations: %d\n" +
                "  Missing vertex references: %d\n" +
                "  Stale vertex references: %d\n" +
                "  Inconsistent vertex references: %d\n" +
                "  Inconsistent neighbor relations: %d\n" +
                "  Degenerate tetrahedra: %d",
                totalVertices, totalTetrahedra, delaunayViolations,
                missingVertexReferences, staleVertexReferences, inconsistentVertexReferences,
                inconsistentNeighborRelations, degenerateTetrahedra
            );
        }
        
        public boolean isValid() {
            return delaunayViolations == 0 &&
                   missingVertexReferences == 0 &&
                   staleVertexReferences == 0 &&
                   inconsistentVertexReferences == 0 &&
                   inconsistentNeighborRelations == 0;
        }
    }
}