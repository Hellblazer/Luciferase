/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Central registry mapping {@link SpatialKeySerde#typeId()} discriminator
 * strings and {@link SpatialKey} implementation classes to their serdes
 * (Luciferase-546).
 * <p>
 * <b>Built-in registrations.</b> Serde providers are discovered via the
 * {@link java.util.ServiceLoader} SPI ({@code META-INF/services/} the
 * {@link SpatialKeySerde} service) from this class's static initialiser. The
 * built-in {@code MortonKeySerde} / {@code TetreeKeySerde} ship as providers in
 * the module that owns the gRPC/proto transport. When no providers are on the
 * classpath the registry is simply empty — the spatial index works standalone;
 * serdes are only needed for distributed ghost exchange.
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
        // Discover SpatialKeySerde providers via the ServiceLoader SPI. Built-ins ship as
        // providers in the module owning the gRPC/proto transport (lucien-distributed after
        // RDR-007 P1). Zero providers is a valid state — the registry stays empty and the
        // spatial index works standalone (no ExceptionInInitializerError on absence).
        ServiceLoader.load(SpatialKeySerde.class).forEach(SpatialKeySerdeRegistry::register);
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
