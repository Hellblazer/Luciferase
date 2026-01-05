package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.simulation.facets.Locatable;
import com.hellblazer.luciferase.simulation.facets.Moveable;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.luciferase.simulation.spatial.Cursor;

import javax.vecmath.Tuple3f;

/**
 * @author hal.hildebrand
 **/
@Entity({ Locatable.class, Moveable.class })
public class MovableActor implements Locatable, Moveable {
    private final Cursor cursor;

    public MovableActor(Cursor cursor) {
        this.cursor = cursor;
    }

    @Override
    public void moveBy(Tuple3f delta) {
        cursor.moveBy(delta);
    }

    @Override
    public void moveTo(Tuple3f position) {
        cursor.moveTo(position);
    }

    @Override
    public Tuple3f position() {
        return cursor.getLocation();
    }
}
