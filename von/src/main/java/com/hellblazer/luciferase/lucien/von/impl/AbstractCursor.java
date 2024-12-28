package com.hellblazer.luciferase.lucien.von.impl;

import com.hellblazer.luciferase.lucien.von.Cursor;
import com.hellblazer.luciferase.lucien.von.Perceiving;
import com.hellblazer.luciferase.lucien.grid.Vertex;

import javax.vecmath.Point3d;
import javax.vecmath.Tuple3d;

/**
 * @author hal.hildebrand
 **/
public class AbstractCursor<E extends Perceiving> implements Cursor, Cloneable {
    protected Vertex location;

    public AbstractCursor(Vertex location) {
        this.location = location;
    }

    @Override
    public Point3d getLocation() {
        return new Point3d(location);
    }

    @Override
    public void moveBy(Tuple3d velocity) {
        location.moveBy(velocity);
    }
}
