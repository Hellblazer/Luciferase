package com.hellblazer.luciferase.lucien;

import javax.vecmath.Tuple3i;
import java.util.NavigableMap;
import java.util.stream.Stream;

/**
 * @author hal.hildebrand
 **/
public class MappedTetree<Content> implements Tetree<Content> {

    private final NavigableMap<Long, Content> chunks;

    public MappedTetree(NavigableMap<Long, Content> chunks) {
        this.chunks = chunks;
    }

    @Override
    public Simplex<Content> intersecting(Spatial volume) {
        return null;
    }

    @Override
    public Simplex<Content> enclosing(Spatial volume) {
        return null;
    }

    @Override
    public Stream<Simplex<Content>> bounding(Spatial volume) {
        return Stream.empty();
    }

    @Override
    public Stream<Simplex<Content>> boundedBy(Spatial volume) {
        return Stream.empty();
    }

    @Override
    public Simplex<Content> enclosing(Tuple3i point, byte level) {
        return null;
    }

    @Override
    public Simplex<Content> get(int linearIndex) {
        return null;
    }

    private Tet locate(Tuple3i point, byte level) {
        return null;
    }
}
