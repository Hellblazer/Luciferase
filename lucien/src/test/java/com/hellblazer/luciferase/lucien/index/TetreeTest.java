package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Tet;
import com.hellblazer.luciferase.lucien.Tetree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
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
        var indexes = new ArrayList<Long>();
        Tet tet;
        Tet testTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        assertEquals(0, testTet.index());
        testTet = new Tet(0, 0, 0, (byte) 1, (byte) 0);
        assertEquals(0, testTet.index());

        tet = tetree.locate(new Point3f(500, 1000, 0), (byte) 19);
        assertEquals(1, tet.type());
        indexes.add(tet.index());
        assertEquals(145528082076402177L, indexes.getLast());

        tet = tetree.locate(new Point3f(0, 0, 0), (byte) 20);
        assertEquals(1, tet.type());
        indexes.add(tet.index());
        assertEquals(0, indexes.getLast());

        tet = tetree.locate(new Point3f(0, 0, 100), (byte) 21);
        assertEquals(1, tet.type());
        indexes.add(tet.index());
        assertEquals(847723465146368L, indexes.getLast());

        tet = tetree.locate(new Point3f(0, 100, 0), (byte) 17);
        assertEquals(3, tet.type());
        indexes.add(tet.index());
        assertEquals(65792, indexes.getLast());

        tet = tetree.locate(new Point3f(100, 0, 0), (byte) 16);
        assertEquals(0, tet.type());
        indexes.add(tet.index());
        assertEquals(257, indexes.getLast());

        tet = tetree.locate(new Point3f(0, 200, 0), (byte) 15);
        assertEquals(3, tet.type());
        indexes.add(tet.index());
        assertEquals(257, indexes.getLast());

        tet = tetree.locate(new Point3f(0, 0, 2000), (byte) 20);
        assertEquals(1, tet.type());
        indexes.add(tet.index());
        assertEquals(217020505612354307L, indexes.getLast());

        tet = tetree.locate(new Point3f(100, 100, 0), (byte) 21);
        assertEquals(1, tet.type());
        indexes.add(tet.index());
        assertEquals(1412872442019840L, indexes.getLast());

        tet = tetree.locate(new Point3f(0, 100, 100), (byte) 13);
        assertEquals(3, tet.type());
        indexes.add(tet.index());
        assertEquals(0, indexes.getLast());
    }
}
