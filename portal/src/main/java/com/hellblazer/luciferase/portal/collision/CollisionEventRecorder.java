/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.collision;

import com.hellblazer.luciferase.lucien.collision.CollisionShape;
import com.hellblazer.luciferase.lucien.collision.physics.RigidBody;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javax.vecmath.Point3f;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records and replays collision events for debugging and testing.
 * Captures complete state information for deterministic replay.
 *
 * @author hal.hildebrand
 */
public class CollisionEventRecorder {
    
    // Recording state
    private final BooleanProperty isRecording = new SimpleBooleanProperty(false);
    private final BooleanProperty isReplaying = new SimpleBooleanProperty(false);
    private final LongProperty currentFrame = new SimpleLongProperty(0);
    private final LongProperty totalFrames = new SimpleLongProperty(0);
    
    // Event storage
    private final ObservableList<CollisionEvent> recordedEvents = FXCollections.observableArrayList();
    private final ObservableList<FrameSnapshot> frameSnapshots = FXCollections.observableArrayList();
    
    // Recording session info
    private final StringProperty sessionName = new SimpleStringProperty("Untitled Session");
    private final ObjectProperty<LocalDateTime> recordingStartTime = new SimpleObjectProperty<>();
    private final LongProperty recordingDuration = new SimpleLongProperty(0);
    
    // Frame tracking
    private final AtomicLong frameCounter = new AtomicLong(0);
    private final Map<Object, EntityState> lastKnownStates = new HashMap<>();
    
    // Replay state
    private int replayFrameIndex = 0;
    private final List<ReplayListener> replayListeners = new ArrayList<>();
    
    public CollisionEventRecorder() {
        setupPropertyBindings();
    }
    
    /**
     * Setup property change listeners.
     */
    private void setupPropertyBindings() {
        isRecording.addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                startRecording();
            } else {
                stopRecording();
            }
        });
        
        isReplaying.addListener((obs, oldVal, newVal) -> {
            if (!newVal) {
                stopReplay();
            }
        });
    }
    
    /**
     * Start recording collision events.
     */
    public void startRecording() {
        if (isReplaying.get()) {
            throw new IllegalStateException("Cannot record while replaying");
        }
        
        recordedEvents.clear();
        frameSnapshots.clear();
        lastKnownStates.clear();
        frameCounter.set(0);
        
        recordingStartTime.set(LocalDateTime.now());
        sessionName.set("Session_" + recordingStartTime.get().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        
        System.out.println("Started recording collision events: " + sessionName.get());
    }
    
    /**
     * Stop recording collision events.
     */
    public void stopRecording() {
        recordingDuration.set(frameCounter.get());
        totalFrames.set(frameCounter.get());
        
        System.out.println("Stopped recording. Captured " + recordedEvents.size() + 
                         " events over " + frameCounter.get() + " frames");
    }
    
    /**
     * Record a collision event.
     */
    public void recordCollision(CollisionShape shapeA, CollisionShape shapeB, 
                               Point3f contactPoint, Vector3f contactNormal, float penetrationDepth) {
        if (!isRecording.get()) return;
        
        var event = new CollisionEvent(
            frameCounter.get(),
            System.nanoTime(),
            createShapeSnapshot(shapeA),
            createShapeSnapshot(shapeB),
            new Point3f(contactPoint),
            new Vector3f(contactNormal),
            penetrationDepth
        );
        
        recordedEvents.add(event);
    }
    
    /**
     * Record rigid body state.
     */
    public void recordRigidBodyState(Object bodyId, RigidBody body) {
        if (!isRecording.get()) return;
        
        var state = new EntityState(
            bodyId,
            frameCounter.get(),
            new Point3f(body.getPosition()),
            new Quat4f(body.getOrientation()),
            new Vector3f(body.getLinearVelocity()),
            new Vector3f(body.getAngularVelocity()),
            body.getMass(),
            body.isKinematic()
        );
        
        lastKnownStates.put(bodyId, state);
    }
    
    /**
     * Advance to the next frame.
     */
    public void nextFrame() {
        if (!isRecording.get()) return;
        
        var frame = frameCounter.incrementAndGet();
        currentFrame.set(frame);
        
        // Take a snapshot of current state
        var snapshot = new FrameSnapshot(frame, System.nanoTime(), new HashMap<>(lastKnownStates));
        frameSnapshots.add(snapshot);
    }
    
    /**
     * Start replaying recorded events.
     */
    public void startReplay() {
        if (isRecording.get()) {
            stopRecording();
        }
        
        if (recordedEvents.isEmpty()) {
            throw new IllegalStateException("No recorded events to replay");
        }
        
        replayFrameIndex = 0;
        currentFrame.set(0);
        isReplaying.set(true);
        
        System.out.println("Started replay of " + recordedEvents.size() + " events");
        notifyReplayListeners(ReplayEvent.REPLAY_STARTED, null);
    }
    
    /**
     * Stop replaying events.
     */
    public void stopReplay() {
        replayFrameIndex = 0;
        currentFrame.set(0);
        isReplaying.set(false);
        
        System.out.println("Stopped replay");
        notifyReplayListeners(ReplayEvent.REPLAY_STOPPED, null);
    }
    
    /**
     * Step to the next frame in replay.
     */
    public void stepReplay() {
        if (!isReplaying.get()) return;
        
        var currentFrameNum = currentFrame.get();
        
        // Find events for current frame
        var frameEvents = recordedEvents.stream()
            .filter(event -> event.frameNumber == currentFrameNum)
            .toList();
        
        // Notify listeners of frame events
        for (var event : frameEvents) {
            notifyReplayListeners(ReplayEvent.COLLISION_EVENT, event);
        }
        
        // Find frame snapshot
        frameSnapshots.stream()
            .filter(snapshot -> snapshot.frameNumber == currentFrameNum)
            .findFirst()
            .ifPresent(snapshot -> notifyReplayListeners(ReplayEvent.FRAME_SNAPSHOT, snapshot));
        
        currentFrame.set(currentFrameNum + 1);
        
        // Check if replay is complete
        if (currentFrameNum >= totalFrames.get()) {
            stopReplay();
        }
    }
    
    /**
     * Seek to a specific frame in replay.
     */
    public void seekToFrame(long frameNumber) {
        if (!isReplaying.get()) return;
        
        var targetFrame = Math.max(0, Math.min(frameNumber, totalFrames.get()));
        currentFrame.set(targetFrame);
        
        // Replay all events up to this frame
        var eventsUpToFrame = recordedEvents.stream()
            .filter(event -> event.frameNumber <= targetFrame)
            .toList();
        
        notifyReplayListeners(ReplayEvent.SEEK_TO_FRAME, targetFrame);
        
        for (var event : eventsUpToFrame) {
            notifyReplayListeners(ReplayEvent.COLLISION_EVENT, event);
        }
    }
    
    /**
     * Save recorded session to file.
     */
    public void saveSession(File file) throws IOException {
        try (var out = new ObjectOutputStream(new FileOutputStream(file))) {
            var session = new RecordingSession(
                sessionName.get(),
                recordingStartTime.get(),
                recordingDuration.get(),
                new ArrayList<>(recordedEvents),
                new ArrayList<>(frameSnapshots)
            );
            out.writeObject(session);
        }
        
        System.out.println("Saved recording session to: " + file.getAbsolutePath());
    }
    
    /**
     * Load recorded session from file.
     */
    public void loadSession(File file) throws IOException, ClassNotFoundException {
        try (var in = new ObjectInputStream(new FileInputStream(file))) {
            var session = (RecordingSession) in.readObject();
            
            sessionName.set(session.name);
            recordingStartTime.set(session.startTime);
            recordingDuration.set(session.duration);
            totalFrames.set(session.duration);
            
            recordedEvents.clear();
            recordedEvents.addAll(session.events);
            
            frameSnapshots.clear();
            frameSnapshots.addAll(session.snapshots);
        }
        
        System.out.println("Loaded recording session from: " + file.getAbsolutePath());
    }
    
    /**
     * Export recording session as text.
     */
    public String exportAsText() {
        var report = new StringBuilder();
        
        report.append("=== Collision Recording Session ===\n");
        report.append("Session: ").append(sessionName.get()).append("\n");
        report.append("Start Time: ").append(recordingStartTime.get()).append("\n");
        report.append("Duration: ").append(recordingDuration.get()).append(" frames\n");
        report.append("Total Events: ").append(recordedEvents.size()).append("\n");
        report.append("Total Snapshots: ").append(frameSnapshots.size()).append("\n");
        report.append("\n");
        
        // Event summary
        report.append("=== Event Timeline ===\n");
        for (var event : recordedEvents) {
            report.append(String.format("Frame %d: Collision at (%.2f, %.2f, %.2f) depth=%.3f\n",
                event.frameNumber,
                event.contactPoint.x, event.contactPoint.y, event.contactPoint.z,
                event.penetrationDepth));
        }
        
        return report.toString();
    }
    
    /**
     * Create a snapshot of a collision shape.
     */
    private ShapeSnapshot createShapeSnapshot(CollisionShape shape) {
        return new ShapeSnapshot(
            shape.getClass().getSimpleName(),
            new Point3f(shape.getPosition()),
            shape.getAABB()
        );
    }
    
    /**
     * Add a replay listener.
     */
    public void addReplayListener(ReplayListener listener) {
        replayListeners.add(listener);
    }
    
    /**
     * Remove a replay listener.
     */
    public void removeReplayListener(ReplayListener listener) {
        replayListeners.remove(listener);
    }
    
    /**
     * Notify all replay listeners.
     */
    private void notifyReplayListeners(ReplayEvent eventType, Object data) {
        for (var listener : replayListeners) {
            listener.onReplayEvent(eventType, data);
        }
    }
    
    // Property getters
    public BooleanProperty isRecordingProperty() { return isRecording; }
    public BooleanProperty isReplayingProperty() { return isReplaying; }
    public LongProperty currentFrameProperty() { return currentFrame; }
    public LongProperty totalFramesProperty() { return totalFrames; }
    public StringProperty sessionNameProperty() { return sessionName; }
    public ObjectProperty<LocalDateTime> recordingStartTimeProperty() { return recordingStartTime; }
    public LongProperty recordingDurationProperty() { return recordingDuration; }
    
    public ObservableList<CollisionEvent> getRecordedEvents() { return recordedEvents; }
    public ObservableList<FrameSnapshot> getFrameSnapshots() { return frameSnapshots; }
    
    // Data classes
    public static class CollisionEvent implements Serializable {
        public final long frameNumber;
        public final long timestamp;
        public final ShapeSnapshot shapeA;
        public final ShapeSnapshot shapeB;
        public final Point3f contactPoint;
        public final Vector3f contactNormal;
        public final float penetrationDepth;
        
        public CollisionEvent(long frameNumber, long timestamp, ShapeSnapshot shapeA, ShapeSnapshot shapeB,
                             Point3f contactPoint, Vector3f contactNormal, float penetrationDepth) {
            this.frameNumber = frameNumber;
            this.timestamp = timestamp;
            this.shapeA = shapeA;
            this.shapeB = shapeB;
            this.contactPoint = contactPoint;
            this.contactNormal = contactNormal;
            this.penetrationDepth = penetrationDepth;
        }
    }
    
    public static class ShapeSnapshot implements Serializable {
        public final String shapeType;
        public final Point3f position;
        public final Object bounds; // EntityBounds - would need to be serializable
        
        public ShapeSnapshot(String shapeType, Point3f position, Object bounds) {
            this.shapeType = shapeType;
            this.position = position;
            this.bounds = bounds;
        }
    }
    
    public static class EntityState implements Serializable {
        public final Object entityId;
        public final long frameNumber;
        public final Point3f position;
        public final Quat4f orientation;
        public final Vector3f linearVelocity;
        public final Vector3f angularVelocity;
        public final float mass;
        public final boolean kinematic;
        
        public EntityState(Object entityId, long frameNumber, Point3f position, Quat4f orientation,
                          Vector3f linearVelocity, Vector3f angularVelocity, float mass, boolean kinematic) {
            this.entityId = entityId;
            this.frameNumber = frameNumber;
            this.position = position;
            this.orientation = orientation;
            this.linearVelocity = linearVelocity;
            this.angularVelocity = angularVelocity;
            this.mass = mass;
            this.kinematic = kinematic;
        }
    }
    
    public static class FrameSnapshot implements Serializable {
        public final long frameNumber;
        public final long timestamp;
        public final Map<Object, EntityState> entityStates;
        
        public FrameSnapshot(long frameNumber, long timestamp, Map<Object, EntityState> entityStates) {
            this.frameNumber = frameNumber;
            this.timestamp = timestamp;
            this.entityStates = new HashMap<>(entityStates);
        }
    }
    
    public static class RecordingSession implements Serializable {
        public final String name;
        public final LocalDateTime startTime;
        public final long duration;
        public final List<CollisionEvent> events;
        public final List<FrameSnapshot> snapshots;
        
        public RecordingSession(String name, LocalDateTime startTime, long duration,
                               List<CollisionEvent> events, List<FrameSnapshot> snapshots) {
            this.name = name;
            this.startTime = startTime;
            this.duration = duration;
            this.events = new ArrayList<>(events);
            this.snapshots = new ArrayList<>(snapshots);
        }
    }
    
    // Enums and interfaces
    public enum ReplayEvent {
        REPLAY_STARTED,
        REPLAY_STOPPED,
        COLLISION_EVENT,
        FRAME_SNAPSHOT,
        SEEK_TO_FRAME
    }
    
    public interface ReplayListener {
        void onReplayEvent(ReplayEvent eventType, Object data);
    }
}