/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.octree;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hellblazer.luciferase.lucien.SpatialNodeImpl;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bead Luciferase-7wzml.142: rebalanceTree() must not silently discard exceptions.
 * Verifies that a failure inside rebalanceTree produces an ERROR log entry and a
 * success=false result rather than silently swallowing the exception.
 *
 * @author hal.hildebrand
 */
class OctreeBalancerTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger balancerLogger;

    @BeforeEach
    void attachLogAppender() {
        balancerLogger = (Logger) LoggerFactory.getLogger(OctreeBalancer.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        balancerLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        balancerLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void rebalanceTreeLogsExceptionAndReturnsFailureResult() {
        var idGenerator = new SequentialLongIDGenerator();

        // ThrowingOctree overrides getSpatialIndex() to throw once the flag is set,
        // simulating a runtime failure mid-rebalance (inside the rebalanceTree try block).
        var throwingOctree = new ThrowingOctree(idGenerator);
        throwingOctree.insert(new Point3f(10, 10, 10), (byte) 2, "a");
        throwingOctree.insert(new Point3f(20, 20, 20), (byte) 2, "b");

        var balancer = new OctreeBalancer<>(throwingOctree, throwingOctree.getEntityManager(),
                                           (byte) 5, 1);

        // Arm the throw after construction so the ctor's own getSpatialIndex calls succeed.
        throwingOctree.setThrowOnKeySet(true);

        var result = balancer.rebalanceTree();

        // Result must report failure, not success
        assertFalse(result.successful(), "rebalanceTree must return success=false when an exception occurs");
        assertEquals(0, result.nodesCreated(), "nodesCreated must be 0 on failure");

        // Exception must be logged at ERROR level (Luciferase-7wzml.142)
        var errorEvents = logAppender.list.stream()
                                          .filter(e -> e.getLevel() == Level.ERROR)
                                          .toList();
        assertFalse(errorEvents.isEmpty(),
                    "OctreeBalancer must log at ERROR when rebalanceTree catches an exception (Luciferase-7wzml.142)");
        var loggedMsg = errorEvents.get(0).getFormattedMessage();
        assertTrue(loggedMsg.contains("rebalanceTree failed"),
                   "ERROR log must mention 'rebalanceTree failed'; was: " + loggedMsg);
    }

    @Test
    void rebalanceTreeSuccessDoesNotLogError() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, String>(idGenerator, 10, (byte) 5);
        octree.insert(new Point3f(10, 10, 10), (byte) 2, "a");
        var balancer = new OctreeBalancer<>(octree, octree.getEntityManager(), (byte) 5, 10);

        var result = balancer.rebalanceTree();

        assertTrue(result.successful(), "Normal rebalanceTree must return success=true");
        var errorEvents = logAppender.list.stream()
                                          .filter(e -> e.getLevel() == Level.ERROR)
                                          .toList();
        assertTrue(errorEvents.isEmpty(), "No ERROR must be logged on a clean rebalance");
    }

    /**
     * Octree subclass that throws from getSpatialIndex() once the throw flag is armed,
     * simulating a runtime failure inside the rebalanceTree try block.
     */
    private static class ThrowingOctree extends Octree<LongEntityID, String> {
        private volatile boolean throwOnKeySet = false;

        ThrowingOctree(SequentialLongIDGenerator gen) {
            super(gen, 5, (byte) 5);
        }

        void setThrowOnKeySet(boolean v) {
            this.throwOnKeySet = v;
        }

        @Override
        protected Map<MortonKey, SpatialNodeImpl<LongEntityID>> getSpatialIndex() {
            if (throwOnKeySet) {
                throw new RuntimeException("Simulated failure inside rebalanceTree (Luciferase-7wzml.142)");
            }
            return super.getSpatialIndex();
        }
    }
}
