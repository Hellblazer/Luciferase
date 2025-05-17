package com.hellblazer.luciferase.simulation.facets;

import javax.vecmath.Tuple3f;

public interface Moveable {
    void moveBy(Tuple3f delta);

    void moveTo(Tuple3f position);
}
