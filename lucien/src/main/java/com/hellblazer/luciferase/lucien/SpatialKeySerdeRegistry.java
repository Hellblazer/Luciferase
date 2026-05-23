/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Central registry mapping {@link SpatialKeySerde#typeId()} discriminator
 * strings and {@link SpatialKey} implementation classes to their serdes
 * (Luciferase-546).
 * <p>
 * <b>Built-in registrations.</b> The two ship-with-lucien serde classes
 * ({@code MortonKeySerde}, {@code TetreeKeySerde} in
 * {@code com.hellblazer.luciferase.lucien.forest.ghost.grpc}) register
 * themselves via class-initialisers on this registry. They are eagerly
 * triggered from this class's static initialiser so that any consumer that
 * touches the registry sees the built-ins without having to load each serde
 * class by name first.
 * <p>
 * <b>Extension registrations.</b> Other modules and tests can register
 * additional serdes by calling {@link #register(SpatialKeySerde)} once at
 * class-init or test-setup time. Registrations are idempotent for the
 * <i>same</i> serde instance (re-registering the same singleton is a no-op);
 * registering a <i>different</i> serde under an already-claimed type-id or
 * key-class throws {@link IllegalStateException} to surface the conflict
 * loudly.
 * <p>
 * <b>Class-hierarchy lookup.</b> {@link #forKey(SpatialKey)} walks the
 * superclass / interface chain of the key's runtime class so a serde
 * registered against an abstract base (e.g. {@code TetreeKey}) handles all
 * concrete subclasses ({@code CompactTetreeKey}, {@code ExtendedTetreeKey}).
 * Lookups are O(class-hierarchy-depth) and the result is cached on hit.
 *
 * @author hal.hildebrand
 */
public final class SpatialKeySerdeRegistry {

    private static final ConcurrentMap<String, SpatialKeySerde<?>>     BY_TYPE_ID  = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, SpatialKeySerde<?>>   BY_KEY_CLASS = new ConcurrentHashMap<>();

    static {
        // Trigger built-in serde class-init so their self-registration runs
        // before any consumer queries the registry.
        try {
            Class.forName(
                "com.hellblazer.luciferase.lucien.forest.ghost.grpc.MortonKeySerde");
            Class.forName(
                "com.hellblazer.luciferase.lucien.forest.ghost.grpc.TetreeKeySerde");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError("Built-in SpatialKeySerde class not on classpath: " + e.getMessage());
        }
    }

    private SpatialKeySerdeRegistry() {
        // Static-only.
    }

    /**
     * Register a serde under its {@link SpatialKeySerde#typeId()} and
     * {@link SpatialKeySerde#keyClass()}. Idempotent for the same instance;
     * conflicting registrations throw.
     */
    public static void register(SpatialKeySerde<?> serde) {
        Objects.requireNonNull(serde, "serde");
        Objects.requireNonNull(serde.typeId(), "serde.typeId");
        Objects.requireNonNull(serde.keyClass(), "serde.keyClass");

        var existingByType = BY_TYPE_ID.putIfAbsent(serde.typeId(), serde);
        if (existingByType != null && existingByType != serde) {
            throw new IllegalStateException(
                "SpatialKeySerde type-id '" + serde.typeId() + "' already registered to "
                + existingByType.getClass().getName() + "; cannot re-register "
                + serde.getClass().getName());
        }

        var existingByClass = BY_KEY_CLASS.putIfAbsent(serde.keyClass(), serde);
        if (existingByClass != null && existingByClass != serde) {
            // Roll back the type-id registration to keep the maps consistent.
            BY_TYPE_ID.remove(serde.typeId(), serde);
            throw new IllegalStateException(
                "SpatialKeySerde key-class " + serde.keyClass().getName() + " already registered to "
                + existingByClass.getClass().getName() + "; cannot re-register "
                + serde.getClass().getName());
        }
    }

    /**
     * Look up the serde registered for a given type-id. Returns {@code null}
     * if no serde is registered.
     */
    public static SpatialKeySerde<?> forTypeId(String typeId) {
        return BY_TYPE_ID.get(typeId);
    }

    /**
     * Look up the serde that handles a given key instance. Searches the
     * runtime class's hierarchy (superclasses + implemented interfaces) so
     * subclasses of a registered base class are routed to the base's serde.
     * On hit, caches the resolution on the concrete class for O(1) subsequent
     * lookups. Returns {@code null} if no compatible serde is registered.
     */
    @SuppressWarnings("unchecked")
    public static <K extends SpatialKey<K>> SpatialKeySerde<K> forKey(K key) {
        Objects.requireNonNull(key, "key");
        var runtimeClass = key.getClass();
        var cached = BY_KEY_CLASS.get(runtimeClass);
        if (cached != null) {
            return (SpatialKeySerde<K>) cached;
        }
        // Walk the class hierarchy looking for a registered base.
        for (Class<?> c = runtimeClass.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            var serde = BY_KEY_CLASS.get(c);
            if (serde != null) {
                BY_KEY_CLASS.putIfAbsent(runtimeClass, serde);  // Cache for next time
                return (SpatialKeySerde<K>) serde;
            }
        }
        return null;
    }

    /**
     * For test isolation only: clear all registrations. Production code MUST
     * NOT call this — built-in serdes are re-registered the next time the
     * registry's static initialiser is touched, but extension serdes are not.
     */
    static void clearForTesting() {
        BY_TYPE_ID.clear();
        BY_KEY_CLASS.clear();
    }
}
