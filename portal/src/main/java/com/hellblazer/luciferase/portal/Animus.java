/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal;

import javax.vecmath.Vector3f;

import com.hellblazer.luciferase.lucien.animus.Oriented;
import com.hellblazer.luciferase.lucien.animus.Rotor3f;

import javafx.beans.Observable;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Translate;

/**
 * Integrated control of position and orientation for a JavaFX node
 *
 * @author hal.hildebrand
 */
public class Animus<N extends Node> {

    private final N                        animated;
    private final ObjectProperty<Rotor3f>  orientation = new SimpleObjectProperty<>();
    private final ObjectProperty<Vector3f> position    = new SimpleObjectProperty<>();
    private final Oriented                 tracking;

    public Animus(N animated) {
        this(new Oriented(), animated);
    }

    public Animus(Oriented tracking, N animated) {
        this.animated = animated;
        this.tracking = tracking;
        updateTransforms();
        position.set(new Vector3f(tracking));
        orientation.set(new Rotor3f(tracking.orientation()));
        position.addListener(p -> updatePosition(p));
        orientation.addListener(r -> updateOrientation(r));
    }

    /**
     * @return the JavaFX Node animated by this Orientable
     */
    public N getAnimated() {
        return animated;
    }

    /**
     * @return the Property used to orient the animated Node
     */
    public ObjectProperty<Rotor3f> getOrientation() {
        return orientation;
    }

    /**
     * 
     * @return the Property used to position the animated Node
     */
    public ObjectProperty<Vector3f> getPosition() {
        return position;
    }

    /**
     * Used to update the properties on state change
     */
    protected void update() {
        tracking.orientation().set(new Rotor3f(tracking.orientation()));
        position.set(new Vector3f(tracking));
        updateTransforms();
    }

    protected void updateTransforms() {
        final var t = animated.getTransforms();
        t.clear();
        t.addAll(transform(), translate());
    }

    private Affine transform() {
        final var m = tracking.orientation().toMatrix();
        var t = new Affine();
        t.setToTransform(m.getM00(), m.getM10(), m.getM20(), m.getM30(), m.getM01(), m.getM11(), m.getM21(), m.getM31(),
                         m.getM02(), m.getM12(), m.getM22(), m.getM32());
        return t;
    }

    private Translate translate() {
        return new Translate(tracking.x, tracking.y, tracking.z);
    }

    private void updateOrientation(Observable r) {
        tracking.orientation().set(orientation.getValue());
        updateTransforms();
    }

    private void updatePosition(Observable p) {
        tracking.set(position.getValue());
        updateTransforms();
    }
}
