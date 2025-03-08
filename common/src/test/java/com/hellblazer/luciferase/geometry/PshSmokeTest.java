package com.hellblazer.luciferase.geometry;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.fail;

public class PshSmokeTest {

    @Test
    //this contains a stress test of of the PshOffsetTable
    public void stress() {
        Random random = new Random(System.currentTimeMillis());
        var n = 64 * 64 * 64;

        //generate random spatial data list for testing only
        var elelist = new ArrayList<Point3i>();
        var added = new HashSet<Point3i>();
        for (int i = 0; i < n; i++) {
            var ele = new Point3i(random.nextInt(56), random.nextInt(56),
                                  random.nextInt(56)); //56 is just arbitrary, arbitrary limit on spatial problem space
            if (added.add(ele)) {
                elelist.add(ele);
            }
        }
        var entropy = new Random(0x666);
        PshOffsetTable table = new PshOffsetTable(elelist, entropy);
        for (int i = 0; i < 10; i++) {
            var timestart = System.currentTimeMillis();
            var ele = new Point3i(random.nextInt(56), random.nextInt(56),
                                  random.nextInt(56)); //56 is just arbitrary, arbitrary limit on spatial problem space
            if (added.add(ele)) {
                elelist.add(ele);
            }
            table.updateOffsets(elelist);
            System.out.println("Time to do an update of offsettable: " + (System.currentTimeMillis() - timestart));
        }

        //check for collisions, there should be none
        var hashCheck = new HashSet<>();
        for (int i = 0; i < elelist.size(); i++) {
            var hash = table.hash(elelist.get(i));
            if (!hashCheck.contains(hash)) {
                hashCheck.add(hash);
            } else {
                fail("hash collision: %s".formatted(hash));
            }

        }
        System.out.println("stress test fin");
    }
}
