package com.hellblazer.luciferase.simulation;

import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.api.Kronos;
import com.hellblazer.luciferase.simulation.Cursor;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Smoke test for VolumeAnimator using SpatialIndex-backed cursors.
 *
 * @author hal.hildebrand
 **/
public class SmokeTest {
    @Test
    public void smokin() throws Exception {
        var sites = new ArrayList<Cursor>();
        var animator = new VolumeAnimator("foo");

        var radius = 16000.0f;
        var center = new Point3f(radius + 100, radius + 100, radius + 100);
        var entropy = new Random(0x666);
        var pop = 256;
        while (sites.size() < pop) {
            for (var p : getRandomPoints(entropy, pop, radius)) {
                p.add(center);
                if (sites.size() == pop) {
                    break;
                }
                var track = animator.track(p);
                if (track != null) {
                    sites.add(track);
                }
            }
        }

        for (var site : sites) {
            var ent = new Ent(site);
            ent.random(entropy);
        }

        animator.start();
        var duration = 10;
        Thread.sleep(TimeUnit.SECONDS.toMillis(duration));
        var frame = animator.getFrame();
        System.out.println("Population: " + sites.size());
        System.out.printf("average frame rate: %s%n", frame.getFrameCount() / duration);
        System.out.printf("average frame duration: %s ms%n",
                          (frame.getCumulativeDurations() / frame.getFrameCount()) / 1E6);
        System.out.printf("average delay: %s ms%n", (frame.getCumulativeDelay() / frame.getFrameCount()) / 1E6);
    }

    /**
     * Generate random points within a sphere of given radius.
     */
    private List<Point3f> getRandomPoints(Random entropy, int count, float radius) {
        var points = new ArrayList<Point3f>();
        for (int i = 0; i < count; i++) {
            float x = (entropy.nextFloat() * 2 - 1) * radius;
            float y = (entropy.nextFloat() * 2 - 1) * radius;
            float z = (entropy.nextFloat() * 2 - 1) * radius;
            points.add(new Point3f(x, y, z));
        }
        return points;
    }

    @Entity
    private class Ent extends MovableActor {
        public Ent(Cursor cursor) {
            super(cursor);
        }

        public void random(Random entropy) {
            var sleep = entropy.nextInt(100);
            Kronos.sleep(TimeUnit.NANOSECONDS.convert(sleep, TimeUnit.MILLISECONDS));
            moveBy(randomPoint(entropy, -5f, 5f));
            random(entropy);
        }

        private Point3f randomPoint(Random entropy, float min, float max) {
            float range = max - min;
            return new Point3f(
                min + entropy.nextFloat() * range,
                min + entropy.nextFloat() * range,
                min + entropy.nextFloat() * range
            );
        }
    }
}
