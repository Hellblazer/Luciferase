package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Tetree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author hal.hildebrand
 **/
public class TetreeTest {
    @Test
    public void locating() {
        var contents = new TreeMap<Long, String>();
        var tetree = new Tetree(contents);

        assertEquals(0, tetree.locate(new Point3f(0, 0, 0), (byte) 3).type());
        assertEquals(4, tetree.locate(new Point3f(0, 0, 100), (byte) 3).type());
        assertEquals(2, tetree.locate(new Point3f(0, 100, 0), (byte) 3).type());
        assertEquals(0, tetree.locate(new Point3f(100, 0, 0), (byte) 3).type());
        assertEquals(2, tetree.locate(new Point3f(0, 200, 0), (byte) 3).type());
        assertEquals(4, tetree.locate(new Point3f(0, 0, 2000), (byte) 3).type());
        assertEquals(1, tetree.locate(new Point3f(100, 100, 0), (byte) 3).type());
        assertEquals(3, tetree.locate(new Point3f(0, 100, 100), (byte) 3).type());
        assertEquals(2, tetree.locate(new Point3f(500, 1000, 0), (byte) 3).type());
    }
}
