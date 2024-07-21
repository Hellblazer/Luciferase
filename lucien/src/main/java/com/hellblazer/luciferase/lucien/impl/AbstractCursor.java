package com.hellblazer.luciferase.lucien.impl;

import com.hellblazer.luciferase.lucien.Cursor;
import com.hellblazer.luciferase.lucien.Perceiving;
import com.hellblazer.luciferase.lucien.grid.Vertex;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;

/**
 * @author hal.hildebrand
 **/
public class AbstractCursor<E extends Perceiving> implements Cursor, Cloneable {
    protected Vertex location;

    public AbstractCursor(Vertex location) {
        this.location = location;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AbstractCursor<E> clone() {
        try {
            return (AbstractCursor<E>) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("Unable to clone", e);
        }
    }

    @Override
    public Point3f getLocation() {
        return new Point3f(location);
    }

    @Override
    public void moveBy(Tuple3f velocity) {
        location.moveBy(velocity);
    }
}
