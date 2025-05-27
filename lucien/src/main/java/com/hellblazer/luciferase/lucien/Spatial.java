package com.hellblazer.luciferase.lucien;

import javax.vecmath.Tuple3f;

/**
 * Simple bounding volume api for querying the Tetree
 */
public interface Spatial {

    boolean containedBy(aabt aabt);

    /**
     * Answer true if the axis aligned bounding tetrahedral volume intersects the receiver's volume
     *
     * @return true - if the axis aligned bounding tetrahedral volume intersects the receiver's volume
     */
    boolean intersects(float originX, float originY, float originZ, float extentX, float extentY, float extentZ);

    default boolean intersects(aabt aabp) {
        return intersects(aabp.originX, aabp.originY, aabp.originZ, aabp.extentX, aabp.extentY, aabp.extentZ);
    }

    record Cube(float originX, float originY, float originZ, float extent) implements Spatial {
        @Override
        public boolean containedBy(aabt aabt) {
            // Check if this cube is completely contained within the tetrahedral bounds
            return this.originX >= aabt.originX() && this.originX + this.extent <= aabt.extentX()
            && this.originY >= aabt.originY() && this.originY + this.extent <= aabt.extentY()
            && this.originZ >= aabt.originZ() && this.originZ + this.extent <= aabt.extentZ();
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
            // AABB intersection test between two boxes
            return !(this.originX + this.extent < originX || this.originX > extentX
                     || this.originY + this.extent < originY || this.originY > extentY
                     || this.originZ + this.extent < originZ || this.originZ > extentZ);
        }
    }

    record Sphere(float centerX, float centerY, float centerZ, float radius) implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            // Check if sphere (bounding box) is completely contained within tetrahedral bounds
            return this.centerX - this.radius >= aabp.originX() && this.centerX + this.radius <= aabp.extentX()
            && this.centerY - this.radius >= aabp.originY() && this.centerY + this.radius <= aabp.extentY()
            && this.centerZ - this.radius >= aabp.originZ() && this.centerZ + this.radius <= aabp.extentZ();
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
            // Sphere-AABB intersection test
            float dx = Math.max(0, Math.max(originX - this.centerX, this.centerX - extentX));
            float dy = Math.max(0, Math.max(originY - this.centerY, this.centerY - extentY));
            float dz = Math.max(0, Math.max(originZ - this.centerZ, this.centerZ - extentZ));
            return (dx * dx + dy * dy + dz * dz) <= (this.radius * this.radius);
        }
    }

    /**
     * A volume formed by six parallelograms
     *
     * @param originX
     * @param originY
     * @param originZ
     * @param extentX
     * @param extentY
     * @param extentZ
     */
    record Parallelepiped(float originX, float originY, float originZ, float extentX, float extentY, float extentZ)
    implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            // Check if parallelepiped is completely contained within tetrahedral bounds
            return this.originX >= aabp.originX() && this.extentX <= aabp.extentX() && this.originY >= aabp.originY()
            && this.extentY <= aabp.extentY() && this.originZ >= aabp.originZ() && this.extentZ <= aabp.extentZ();
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
            // AABB intersection test
            return !(this.extentX < originX || this.originX > extentX || this.extentY < originY
                     || this.originY > extentY || this.extentZ < originZ || this.originZ > extentZ);
        }
    }

    record Tetrahedron(Tuple3f a, Tuple3f b, Tuple3f c, Tuple3f d) implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            // Check if all vertices are within tetrahedral bounds
            return vertexInBounds(a, aabp) && vertexInBounds(b, aabp) && vertexInBounds(c, aabp) && vertexInBounds(d,
                                                                                                                   aabp);
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
            // Simplified test: check if any vertex is within the AABB or if AABB center is inside tetrahedron
            // First check vertices against AABB
            return vertexInAABB(a, originX, originY, originZ, extentX, extentY, extentZ) || vertexInAABB(b, originX,
                                                                                                         originY,
                                                                                                         originZ,
                                                                                                         extentX,
                                                                                                         extentY,
                                                                                                         extentZ)
            || vertexInAABB(c, originX, originY, originZ, extentX, extentY, extentZ) || vertexInAABB(d, originX,
                                                                                                     originY, originZ,
                                                                                                     extentX, extentY,
                                                                                                     extentZ);

            // TODO: More sophisticated tetrahedron-AABB intersection test
        }

        private boolean vertexInAABB(Tuple3f vertex, float minX, float minY, float minZ, float maxX, float maxY,
                                     float maxZ) {
            return vertex.x >= minX && vertex.x <= maxX && vertex.y >= minY && vertex.y <= maxY && vertex.z >= minZ
            && vertex.z <= maxZ;
        }

        private boolean vertexInBounds(Tuple3f vertex, aabt bounds) {
            return vertex.x >= bounds.originX() && vertex.x <= bounds.extentX() && vertex.y >= bounds.originY()
            && vertex.y <= bounds.extentY() && vertex.z >= bounds.originZ() && vertex.z <= bounds.extentZ();
        }
    }

    record aabb(float originX, float originY, float originZ, float extentX, float extentY, float extentZ)
    implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            // Check if this AABB is completely contained within tetrahedral bounds
            return this.originX >= aabp.originX() && this.extentX <= aabp.extentX() && this.originY >= aabp.originY()
            && this.extentY <= aabp.extentY() && this.originZ >= aabp.originZ() && this.extentZ <= aabp.extentZ();
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
            return !(this.extentX < originX || this.originX > extentX || this.extentY < originY
                     || this.originY > extentY || this.extentZ < originZ || this.originZ > extentZ);
        }
    }

    /**
     * Axis aligned bounding tetrahedron.  A tetrahedral volume in tetrahedral coordinates
     *
     * @param originX
     * @param originY
     * @param originZ
     * @param extentX
     * @param extentY
     * @param extentZ
     */
    record aabt(float originX, float originY, float originZ, float extentX, float extentY, float extentZ)
    implements Spatial {
        @Override
        public boolean containedBy(aabt aabp) {
            // Check if this tetrahedral volume is completely contained within another tetrahedral volume
            return this.originX >= aabp.originX() && this.extentX <= aabp.extentX() && this.originY >= aabp.originY()
            && this.extentY <= aabp.extentY() && this.originZ >= aabp.originZ() && this.extentZ <= aabp.extentZ();
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
            return !(this.extentX < originX || this.originX > extentX || this.extentY < originY
                     || this.originY > extentY || this.extentZ < originZ || this.originZ > extentZ);
        }
    }
}
