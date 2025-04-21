package com.hellblazer.sentry;

/**
 * @author hal.hildebrand
 **/
public record Morton(long index, Vertex v) implements Comparable<Morton> {
    @Override
    public int compareTo(Morton o) {
        return Long.compare(index, o.index);
    }
}
