package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.primeMover.Kronos;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.sentry.Cursor;
import com.hellblazer.sentry.Vertex;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * @author hal.hildebrand
 **/
public class SmokeTest {
    @Test
    public void smokin() throws Exception {
        var sites = new java.util.ArrayList<Vertex>();
        var animator = new VolumeAnimator("foo", Constants.ROOT_SIMPLEX, new java.util.Random(0x666));

        var radius = 16000.0f;
        var center = new Point3f(radius + 100, radius + 100, radius + 100);
        var entropy = new java.util.Random(0x666);
        while (sites.size() < 256) {
            for (var p : Vertex.getRandomPoints(entropy, 256, radius, true)) {
                p.add(center);
                if (sites.size() == 256) {
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
        System.out.printf("average frame rate: %s", frame.getFrameCount() / duration).println();
        System.out.printf("average frame rebuild: %s ms",
                          (frame.getCumulativeDurations() / frame.getFrameCount()) / 1E6).println();
        System.out.printf("average delay: %s ms", (frame.getCumulativeDelay() / frame.getFrameCount()) / 1E6).println();
    }

    @Entity
    private class Ent extends MovableActor {
        public Ent(Cursor cursor) {
            super(cursor);
        }

        public void random(Random entropy) {
            var sleep = entropy.nextInt(100);
            Kronos.sleep(TimeUnit.NANOSECONDS.convert(sleep, TimeUnit.MILLISECONDS));
            moveBy(Vertex.randomPoint(entropy, -5f, 5f));
            random(entropy);
        }
    }
}
