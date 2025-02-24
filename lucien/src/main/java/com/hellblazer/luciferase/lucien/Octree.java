package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.NavigableMap;

/**
 * @author hal.hildebrand
 **/
public class Octree<Content> {

    private final NavigableMap<Long, Content> map;

    public Octree(NavigableMap map) {
        this.map = map;
    }

    public static Spatial.Cube toCube(long index) {
        var point = MortonCurve.decode(index);
        return new Spatial.Cube(point[0], point[1], point[2], Constants.lengthAtLevel(Constants.toLevel(index)));
    }

    public Content get(long key) {
        return map.get(key);
    }

    public long insert(Point3f point, byte level, Content value) {
        var length = Constants.lengthAtLevel(level);
        var index = MortonCurve.encode((int) (Math.floor(point.x / length) * length),
                                       (int) (Math.floor(point.y / length) * length),
                                       (int) (Math.floor(point.z / length) * length));
        map.put(index, value);
        return index;
    }

    public Spatial.Cube locate(long index) {
        return Octree.toCube(index);
    }

    record Hexahedron<Data>(long index, Data cell) {
        public Spatial.Cube toCube() {
            return Octree.toCube(index);
        }
    }
}
