package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.geometry.Geometry;

import javax.vecmath.Point3i;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;
import java.util.stream.Stream;

import static com.hellblazer.luciferase.lucien.Constants.*;

/**
 * A tetrahedron in the mesh from the paper:
 * <a href="https://arxiv.org/abs/1509.04627">A tetrahedral space-filling curve for non-conforming adaptive meshes</a>
 * <p>
 * <img src="reference-simplexes.png" alt="reference simplexes">
 * </p>
 *
 * @author hal.hildebrand
 **/
public record Tet(int x, int y, int z, byte l, byte type) {

    public Tet(int level, int type) {
        this((byte) level, (byte) type);
    }

    public Tet(byte level, byte type) {
        this(ROOT_SIMPLEX.x, ROOT_SIMPLEX.y, ROOT_SIMPLEX.z, level, type);
    }

    public Tet(Point3i cubeId, int level, int type) {
        this(cubeId, (byte) level, (byte) type);
    }

    public Tet(Point3i cubeId, byte level, byte type) {
        this(cubeId.x, cubeId.y, cubeId.z, level, type);
    }

    public static boolean contains(Point3i[] vertices, Tuple3f point) {
        // wrt face CDB
        if (orientation(point, vertices[2], vertices[3], vertices[1]) > 0) {
            return false;
        }
        // wrt face DCA
        if (orientation(point, vertices[3], vertices[2], vertices[0]) > 0) {
            return false;
        }
        // wrt face BDA
        if (orientation(point, vertices[1], vertices[3], vertices[0]) > 0.0d) {
            return false;
        }
        // wrt face BAC
        return orientation(point, vertices[1], vertices[0], vertices[2]) <= 0;
    }

    public static double orientation(Tuple3i query, Tuple3i a, Tuple3i b, Tuple3i c) {
        var result = Geometry.leftOfPlane(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, query.x, query.y, query.z);
        return Math.signum(result);
    }

    public static double orientation(Tuple3f query, Tuple3i a, Tuple3i b, Tuple3i c) {
        var result = Geometry.leftOfPlane(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, query.x, query.y, query.z);
        return Math.signum(result);
    }

    /**
     * Calculate the tetrahedral refinement level from a space-filling curve index
     *
     * @param index - the tetrahedral SFC index
     * @return the refinement level
     */
    public static byte tetLevelFromIndex(long index) {
        if (index == 0) {
            return 0;
        }
        // Each level uses 3 bits, so level = ceil(log2(index+1) / 3)
        int significantBits = 64 - Long.numberOfLeadingZeros(index);
        return (byte) ((significantBits + 2) / 3);
    }

    /**
     * @param index - the consecutive index of the tetrahedron
     * @return the Tet corresponding to the consecutive index
     */
    public static Tet tetrahedron(long index) {
        return tetrahedron(index, tetLevelFromIndex(index));
    }

    /**
     * @param index - the consecutive index of the tetrahedron
     * @return the Tet corresponding to the consecutive index
     */
    public static Tet tetrahedron(long index, byte level) {

        byte type = 0;
        int childrenM1 = 7;
        var coordinates = new int[3];

        for (int i = 1; i <= level; i++) {
            var offsetCoords = getMaxRefinementLevel() - i;
            var offsetIndex = level - i;
            // Get the local index of T's ancestor on level i
            var localIndex = (int) ((index >> (3 * offsetIndex)) & childrenM1);
            // get the type and cube-id of T's ancestor on level i
            var cid = PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID[type][localIndex];
            type = PARENT_TYPE_LOCAL_INDEX_TO_TYPE[type][localIndex];
            coordinates[0] |= (cid & 1) > 0 ? 1 << offsetCoords : 0;
            coordinates[1] |= (cid & 2) > 0 ? 1 << offsetCoords : 0;
            coordinates[2] |= (cid & 4) > 0 ? 1 << offsetCoords : 0;
        }
        return new Tet(coordinates[0], coordinates[1], coordinates[2], level, type);
    }

    /**
     * @param volume - the enclosing volume
     * @return the Stream of indexes in the SFC locating the Tets bounded by the volume
     */
    public Stream<Long> boundedBy(Spatial volume) {
        return null;
    }

    /**
     * @param volume the volume to contain
     * @return the Stream of indexes in the SFC locating the Tets that minimally bound the volume
     */
    public Stream<Long> bounding(Spatial volume) {
        return null;
    }

    /**
     * @param i - the child id
     * @return the i-th child (in Bey's order) of the receiver
     */
    public Tet child(byte i) {
        var coords = coordinates();
        var j = 0;
        if (i == 1 || i == 4 || i == 5) {
            j = 1;
        } else if (i == 2 || i == 6 || i == 7) {
            j = 2;
        }
        if (i == 3) {
            j = 3;
        }
        return new Tet((coords[0].x + coords[j].x) / 2, (coords[0].y + coords[j].y) / 2,
                       (coords[0].z + coords[j].z) / 2, (byte) (l + 1), TYPE_TO_TYPE_OF_CHILD[type][i]);
    }

    /**
     * @param i - the Tet Morton child id
     * @return the i-th child (in Tet Morton order) of the receiver
     */
    public Tet childTM(byte i) {
        if (l == getMaxRefinementLevel()) {
            throw new IllegalArgumentException(
            "No children at maximum refinement level: %s".formatted(getMaxRefinementLevel()));
        }
        return child(TYPE_TO_TYPE_OF_CHILD_MORTON[type][i]);
    }

    /* A routine to compute the type of t's ancestor of level "level",
     * if its type at an intermediate level is already known.
     * If "level" equals t's level then t's type is returned.
     * It is not allowed to call this function with "level" greater than t->level.
     * This method runs in O(t->level - level).
     */
    public byte computeType(byte level) {
        assert (0 <= level && level <= l);

        if (level == l) {
            return type;
        }
        if (level == 0) {
            /* TODO: the type of the root tet is hardcoded to 0
             *       maybe once we want to allow the root tet to have different types */
            return 0;
        }

        byte type = this.type;
        for (byte i = l; i > level; i--) {
            /* compute type as the type of T^{i+1}, that is T's ancestor of level i+1 */
            type = CUBE_ID_TYPE_TO_PARENT_TYPE[cubeId(i)][type];
        }
        return type;
    }

    public boolean contains(Tuple3f point) {
        return contains(vertices(), point);
    }

    /**
     * Answer the 3D coordinates of the tetrahedron represented by the receiver
     *
     * @return the 3D coordinates of the tetrahedron described by the receiver in CCW order
     */
    public Point3i[] coordinates() {
        var coords = new Point3i[4];
        coords[0] = new Point3i(x, y, z);
        var h = length();
        var i = type / 2;
        var j = (i + ((type % 2 == 0) ? 2 : 1)) % 3;

        if (i == 0) {
            coords[1] = new Point3i(coords[0].x + h, coords[0].y, coords[0].z);
        } else if (i == 1) {
            coords[1] = new Point3i(coords[0].x, coords[0].y + h, coords[0].z);
        } else if (i == 2) {
            coords[1] = new Point3i(coords[0].x, coords[0].y, coords[0].z + h);
        }

        if (j == 0) {
            coords[2] = new Point3i(coords[1].x + h, coords[1].y, coords[1].z);
        } else if (j == 1) {
            coords[2] = new Point3i(coords[1].x, coords[1].y + h, coords[1].z);
        } else if (j == 2) {
            coords[2] = new Point3i(coords[1].x, coords[1].y, coords[1].z + h);
        }

        coords[3] = new Point3i(coords[1].x + h, coords[1].y + h, coords[1].z + h);
        return coords;
    }

    /**
     * @return the cube id of t's ancestor of level "level"
     */
    public byte cubeId(byte level) {
        if (level < 0 || level > getMaxRefinementLevel()) {
            throw new IllegalArgumentException("Illegal level: " + level);
        }
        if (level == 0 || level > l) {
            return 0;
        }
        int h = 1 << (getMaxRefinementLevel() - level);
        byte id = 0;
        id |= ((x & h) > 0 ? (byte) 1 : 0);
        id |= ((y & h) > 0 ? (byte) 2 : 0);
        id |= ((z & h) > 0 ? (byte) 4 : 0);
        return id;
    }

    /**
     * @param volume - the volume to enclose
     * @return - index in the SFC of the minimum Tet enclosing the volume
     */
    public long enclosing(Spatial volume) {
        return 0L;
    }

    /**
     * @param point - the point to enclose
     * @param level - refinement level for enclosure
     * @return the simplex at the provided
     */
    public long enclosing(Tuple3f point, byte level) {
        return 0L;
    }

    public FaceNeighbor faceNeighbor(int face) {
        final var h = length();
        return switch (type) {
            case 0 -> switch (face) {
                case 0 -> new FaceNeighbor((byte) 3, new Tet(x + h, y, z, l, (byte) 4));
                case 1 -> new FaceNeighbor((byte) 1, new Tet(x, y, z, l, (byte) 5));
                case 2 -> new FaceNeighbor((byte) 2, new Tet(x, y, z, l, (byte) 1));
                case 3 -> new FaceNeighbor((byte) 0, new Tet(x, y - h, z, l, (byte) 2));
                default -> throw new IllegalStateException("face must be {0..3}: %s".formatted(face));
            };
            case 1 -> switch (face) {
                case 0 -> new FaceNeighbor((byte) 3, new Tet(x + h, y, z, l, (byte) 3));
                case 1 -> new FaceNeighbor((byte) 1, new Tet(x, y, z, l, (byte) 2));
                case 2 -> new FaceNeighbor((byte) 2, new Tet(x, y, z, l, (byte) 0));
                case 3 -> new FaceNeighbor((byte) 0, new Tet(x, y, z - h, l, (byte) 5));
                default -> throw new IllegalStateException("face must be {0..3}: %s".formatted(face));
            };
            case 2 -> switch (face) {
                case 0 -> new FaceNeighbor((byte) 3, new Tet(x, y + h, z, l, (byte) 0));
                case 1 -> new FaceNeighbor((byte) 1, new Tet(x, y, z, l, (byte) 1));
                case 2 -> new FaceNeighbor((byte) 2, new Tet(x, y, z, l, (byte) 3));
                case 3 -> new FaceNeighbor((byte) 0, new Tet(x, y, z - h, l, (byte) 4));
                default -> throw new IllegalStateException("face must be {0..3}: %s".formatted(face));
            };
            case 3 -> switch (face) {
                case 0 -> new FaceNeighbor((byte) 3, new Tet(x, y + h, z, l, (byte) 5));
                case 1 -> new FaceNeighbor((byte) 1, new Tet(x, y, z, l, (byte) 4));
                case 2 -> new FaceNeighbor((byte) 2, new Tet(x, y, z, l, (byte) 2));
                case 3 -> new FaceNeighbor((byte) 0, new Tet(x - h, y, z, l, (byte) 1));
                default -> throw new IllegalStateException("face must be {0..3}: %s".formatted(face));
            };
            case 4 -> switch (face) {
                case 0 -> new FaceNeighbor((byte) 3, new Tet(x, y, z + h, l, (byte) 2));
                case 1 -> new FaceNeighbor((byte) 1, new Tet(x, y, z, l, (byte) 3));
                case 2 -> new FaceNeighbor((byte) 2, new Tet(x, y, z, l, (byte) 5));
                case 3 -> new FaceNeighbor((byte) 0, new Tet(x - h, y, z, l, (byte) 0));
                default -> throw new IllegalStateException("face must be {0..3}: %s".formatted(face));
            };
            case 5 -> switch (face) {
                case 0 -> new FaceNeighbor((byte) 3, new Tet(x, y, z + h, l, (byte) 1));
                case 1 -> new FaceNeighbor((byte) 1, new Tet(x, y, z, l, (byte) 0));
                case 2 -> new FaceNeighbor((byte) 2, new Tet(x, y, z, l, (byte) 4));
                case 3 -> new FaceNeighbor((byte) 0, new Tet(x, y - h, z, l, (byte) 3));
                default -> throw new IllegalStateException("face must be {0..3}: %s".formatted(face));
            };
            default -> throw new IllegalStateException("type must be {0..5}: %s".formatted(type));
        };
    }

    /**
     * @return the consecutive index of the receiver on the space filling curve
     */
    public long index() {
        return index(l);
    }

    public long index(byte level) {
        long id = 0;

        assert (0 <= level && level <= getMaxRefinementLevel());

        /* If the given level is bigger than t's level
         * we first fill up with the ids of t's descendants at t's
         * origin with the same type as t */
        if (level > l) {
            // For now, just handle the case where level == l
            // TODO: implement the descendant case
            return index(l);
        }

        // Build the index by working backwards through the SFC tree
        // We need to find the sequence of parent types that led to this tetrahedron
        return calculateIndexFromPath(level);
    }

    public long intersecting(Spatial volume) {
        return 0L;
    }

    /**
     * @return the length of an edge at the given level, in integer coordinates
     */
    public int length() {
        return 1 << (getMaxRefinementLevel() - l);
    }

    /**
     * @return the parent Tet
     */
    public Tet parent() {
        int h = length();
        return new Tet(x & ~h, y & ~h, z & ~h, (byte) (l - 1), CUBE_ID_TYPE_TO_PARENT_TYPE[cubeId(l)][type]);
    }

    public Point3i[] vertices() {
        var origin = new Point3i(x, y, z);
        var vertices = new Point3i[4];
        int i = 0;
        for (var vertex : Constants.SIMPLEX_STANDARD[type()]) {
            vertices[i] = new Point3i(vertex.x, vertex.y, vertex.z);
            vertices[i].scaleAdd(length(), origin);
            i++;
        }
        return vertices;
    }

    /**
     * Calculate the SFC index by working backwards through the parent chain and determining the local index at each
     * level.
     */
    private long calculateIndexFromPath(byte targetLevel) {
        if (targetLevel == 0) {
            return 0; // Root tetrahedron
        }

        // Build array of tetrahedra from root to this level
        Tet[] path = new Tet[targetLevel + 1];
        Tet current = this;

        // Fill path from target level back to root
        for (int i = targetLevel; i >= 0; i--) {
            path[i] = current;
            if (i > 0) {
                current = current.parent();
            }
        }

        long index = 0;

        // For each level from 1 to target, find the local index
        for (int i = 1; i <= targetLevel; i++) {
            Tet parentTet = path[i - 1];
            Tet childTet = path[i];

            // Find which local index corresponds to going from parent to child
            byte localIndex = findLocalIndexForTransition(parentTet, childTet);

            // Place the local index at the correct bit position
            int bitPosition = 3 * (targetLevel - i);
            index |= (long) localIndex << bitPosition;
        }

        return index;
    }

    /**
     * Find the local index that transitions from parent to child in the SFC.
     */
    private byte findLocalIndexForTransition(Tet parent, Tet child) {
        byte childCubeId = child.cubeId(child.l());

        // Check all possible local indices for this parent type
        for (byte localIndex = 0; localIndex < 8; localIndex++) {
            byte expectedCubeId = PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID[parent.type()][localIndex];
            byte expectedType = PARENT_TYPE_LOCAL_INDEX_TO_TYPE[parent.type()][localIndex];

            if (expectedCubeId == childCubeId && expectedType == child.type()) {
                return localIndex;
            }
        }

        throw new IllegalStateException(
        "Could not find local index for transition from parent (%d,%d,%d) type=%d to child (%d,%d,%d) type=%d".formatted(
        parent.x(), parent.y(), parent.z(), parent.type(), child.x(), child.y(), child.z(), child.type()));
    }

    public record FaceNeighbor(byte face, Tet tet) {
    }
}
