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

    default boolean intersects(aabt bounds) {
        var vb = bounds.toVolumeBounds();
        return intersects(vb.minX(), vb.minY(), vb.minZ(), vb.maxX(), vb.maxY(), vb.maxZ());
    }

    record Cube(float originX, float originY, float originZ, float extent) implements Spatial {

        /**
         * Create a Cube from a Morton index
         *
         * @param mortonIndex the Morton index encoding position and level
         */
        public Cube(long mortonIndex) {
            this(decode(mortonIndex));
        }

        // Private constructor that takes decoded values
        private Cube(DecodedMorton decoded) {
            this(decoded.x, decoded.y, decoded.z, decoded.extent);
        }

        // Helper to decode morton index
        private static DecodedMorton decode(long mortonIndex) {
            var point = com.hellblazer.luciferase.geometry.MortonCurve.decode(mortonIndex);
            byte level = Constants.toLevel(mortonIndex);
            return new DecodedMorton(point[0], point[1], point[2], Constants.lengthAtLevel(level));
        }

        @Override
        public boolean containedBy(aabt bounds) {
            // Check all 8 corners of this axis-aligned cube against the convex aabt volume
            float maxX = this.originX + this.extent;
            float maxY = this.originY + this.extent;
            float maxZ = this.originZ + this.extent;
            return bounds.contains(originX, originY, originZ)
                   && bounds.contains(maxX, originY, originZ)
                   && bounds.contains(originX, maxY, originZ)
                   && bounds.contains(maxX, maxY, originZ)
                   && bounds.contains(originX, originY, maxZ)
                   && bounds.contains(maxX, originY, maxZ)
                   && bounds.contains(originX, maxY, maxZ)
                   && bounds.contains(maxX, maxY, maxZ);
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
            // AABB intersection test between two boxes
            return !(this.originX + this.extent < originX || this.originX > extentX
                     || this.originY + this.extent < originY || this.originY > extentY
                     || this.originZ + this.extent < originZ || this.originZ > extentZ);
        }

        // Helper record for decoded values
        private record DecodedMorton(float x, float y, float z, float extent) {
        }
    }

    record Sphere(float centerX, float centerY, float centerZ, float radius) implements Spatial {
        @Override
        public boolean containedBy(aabt bounds) {
            // Conservative approximation using AABB of sphere vs aabt bounds.
            // Note: this may report containment when sphere protrudes outside tetrahedral bounds.
            var vb = bounds.toVolumeBounds();
            return this.centerX - this.radius >= vb.minX() && this.centerX + this.radius <= vb.maxX()
            && this.centerY - this.radius >= vb.minY() && this.centerY + this.radius <= vb.maxY()
            && this.centerZ - this.radius >= vb.minZ() && this.centerZ + this.radius <= vb.maxZ();
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
        public boolean containedBy(aabt bounds) {
            // Check all 8 corners of this axis-aligned parallelepiped
            return bounds.contains(originX, originY, originZ)
                   && bounds.contains(extentX, originY, originZ)
                   && bounds.contains(originX, extentY, originZ)
                   && bounds.contains(extentX, extentY, originZ)
                   && bounds.contains(originX, originY, extentZ)
                   && bounds.contains(extentX, originY, extentZ)
                   && bounds.contains(originX, extentY, extentZ)
                   && bounds.contains(extentX, extentY, extentZ);
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
        public boolean containedBy(aabt bounds) {
            // Check if all vertices are within the aabt bounds
            return bounds.contains(a.x, a.y, a.z) && bounds.contains(b.x, b.y, b.z)
            && bounds.contains(c.x, c.y, c.z) && bounds.contains(d.x, d.y, d.z);
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

            // Simple vertex-based test - could be enhanced with edge/face intersection tests
        }

        private boolean vertexInAABB(Tuple3f vertex, float minX, float minY, float minZ, float maxX, float maxY,
                                     float maxZ) {
            return vertex.x >= minX && vertex.x <= maxX && vertex.y >= minY && vertex.y <= maxY && vertex.z >= minZ
            && vertex.z <= maxZ;
        }
    }

    record aabb(float originX, float originY, float originZ, float extentX, float extentY, float extentZ)
    implements Spatial {
        @Override
        public boolean containedBy(aabt bounds) {
            // Check all 8 corners of this AABB
            return bounds.contains(originX, originY, originZ)
                   && bounds.contains(extentX, originY, originZ)
                   && bounds.contains(originX, extentY, originZ)
                   && bounds.contains(extentX, extentY, originZ)
                   && bounds.contains(originX, originY, extentZ)
                   && bounds.contains(extentX, originY, extentZ)
                   && bounds.contains(originX, extentY, extentZ)
                   && bounds.contains(extentX, extentY, extentZ);
        }

        @Override
        public boolean intersects(float originX, float originY, float originZ, float extentX, float extentY,
                                  float extentZ) {
            return !(this.extentX < originX || this.originX > extentX || this.extentY < originY
                     || this.originY > extentY || this.extentZ < originZ || this.originZ > extentZ);
        }
    }

    /**
     * Axis aligned bounding tetrahedron. A behavioral interface for tetrahedral bounding volumes that can
     * test containment and intersection using actual geometry. The canonical implementation for AABB-style
     * bounds is {@link aabt.Box}; {@link Tet} also implements this interface for tetrahedral bounds.
     */
    interface aabt extends Spatial {

        /**
         * Answer true if the given point is contained within this bounding volume
         */
        boolean contains(float px, float py, float pz);

        /**
         * Answer true if the given bounding volume is completely contained within this volume.
         * Checks all vertices of {@code other} against this volume's containment test.
         */
        boolean containsBound(aabt other);

        /**
         * Answer true if this bounding volume intersects the given bounding volume.
         */
        boolean intersectsBound(aabt other);

        /**
         * Return the vertices of this bounding volume as a float[][] array.
         * Each inner array has 3 elements: [x, y, z].
         */
        float[][] vertices();

        /**
         * Return the axis-aligned bounding box of this volume.
         */
        VolumeBounds toVolumeBounds();

        /**
         * Convenience accessor returning the minimum X coordinate (from AABB).
         */
        default float originX() {
            return toVolumeBounds().minX();
        }

        /**
         * Convenience accessor returning the minimum Y coordinate (from AABB).
         */
        default float originY() {
            return toVolumeBounds().minY();
        }

        /**
         * Convenience accessor returning the minimum Z coordinate (from AABB).
         */
        default float originZ() {
            return toVolumeBounds().minZ();
        }

        /**
         * Convenience accessor returning the maximum X coordinate (from AABB).
         */
        default float extentX() {
            return toVolumeBounds().maxX();
        }

        /**
         * Convenience accessor returning the maximum Y coordinate (from AABB).
         */
        default float extentY() {
            return toVolumeBounds().maxY();
        }

        /**
         * Convenience accessor returning the maximum Z coordinate (from AABB).
         */
        default float extentZ() {
            return toVolumeBounds().maxZ();
        }

        /**
         * Axis-aligned bounding box implementation of {@link aabt}. Stores min (origin) and max (extent)
         * coordinates directly, providing exact AABB containment tests.
         */
        record Box(float originX, float originY, float originZ, float extentX, float extentY, float extentZ)
        implements aabt {

            @Override
            public boolean contains(float px, float py, float pz) {
                return px >= originX && px <= extentX && py >= originY && py <= extentY && pz >= originZ
                && pz <= extentZ;
            }

            @Override
            public boolean containsBound(aabt other) {
                for (var v : other.vertices()) {
                    if (!contains(v[0], v[1], v[2])) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean intersectsBound(aabt other) {
                return toVolumeBounds().intersects(other.toVolumeBounds());
            }

            @Override
            public float[][] vertices() {
                return new float[][] { { originX, originY, originZ }, { extentX, originY, originZ },
                                       { originX, extentY, originZ }, { extentX, extentY, originZ },
                                       { originX, originY, extentZ }, { extentX, originY, extentZ },
                                       { originX, extentY, extentZ }, { extentX, extentY, extentZ } };
            }

            @Override
            public VolumeBounds toVolumeBounds() {
                return new VolumeBounds(originX, originY, originZ, extentX, extentY, extentZ);
            }

            @Override
            public boolean containedBy(aabt bounds) {
                // Check all 8 corners: sufficient for AABB containment in any convex volume
                return bounds.contains(originX, originY, originZ)
                       && bounds.contains(extentX, originY, originZ)
                       && bounds.contains(originX, extentY, originZ)
                       && bounds.contains(extentX, extentY, originZ)
                       && bounds.contains(originX, originY, extentZ)
                       && bounds.contains(extentX, originY, extentZ)
                       && bounds.contains(originX, extentY, extentZ)
                       && bounds.contains(extentX, extentY, extentZ);
            }

            @Override
            public boolean intersects(float oX, float oY, float oZ, float eX, float eY, float eZ) {
                return !(extentX < oX || originX > eX || extentY < oY || originY > eY || extentZ < oZ
                         || originZ > eZ);
            }
        }
    }
}
