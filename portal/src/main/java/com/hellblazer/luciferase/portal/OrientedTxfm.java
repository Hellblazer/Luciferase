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

import static com.hellblazer.luciferase.lucien.animus.Rotor3f.PrincipalAxis.X;
import static com.hellblazer.luciferase.lucien.animus.Rotor3f.PrincipalAxis.Y;
import static com.hellblazer.luciferase.lucien.animus.Rotor3f.PrincipalAxis.Z;

import java.util.function.Consumer;

import javax.vecmath.Tuple3f;

import com.hellblazer.luciferase.lucien.animus.Rotor3f;

import javafx.scene.Node;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

/**
 * Composable transform. This is a Translate, followed by the transform to the
 * orientation and then finally the scaling. This class is designed to be
 * chained together to perform complex transformations and then to apply these
 * transformations to any given Node.
 *
 * @author hal.hildebrand
 */
public class OrientedTxfm implements Consumer<Node> {
    OrientedTxfm    next;
    final Rotor3f   orientation = new Rotor3f();
    final Scale     s           = new Scale();
    final Translate t           = new Translate();

    @Override
    public void accept(Node node) {
        final var transforms = node.getTransforms();
        transforms.clear();
        var current = this;
        while (current != null) {
            transforms.addAll(current.t, current.transform(), current.s);
            current = current.next;
        }
    }

    /**
     * Set the next txfm of the receiver, return the txfm
     *
     * @param txfm
     * @return the passed txfm
     */
    public OrientedTxfm next(OrientedTxfm txfm) {
        next = txfm;
        return txfm;
    }

    /**
     * Reset to zero'd state
     */
    public void reset() {
        t.setX(0.0);
        t.setY(0.0);
        t.setZ(0.0);
        orientation.set(new Rotor3f());
        s.setX(1.0);
        s.setY(1.0);
        s.setZ(1.0);
    }

    /**
     * Reset translate and scale
     */
    public void resetTS() {
        t.setX(0.0);
        t.setY(0.0);
        t.setZ(0.0);
        s.setX(1.0);
        s.setY(1.0);
        s.setZ(1.0);
    }

    public void setOrientation(Rotor3f orientation) {
        this.orientation.set(orientation);
    }

    /**
     * set the orientation to the supplied angles rotation around the primary axis
     *
     * @param x
     * @param y
     * @param z
     */
    public void setRotate(float x, float y, float z) {
        orientation.set(X.angle(-x).combine(Y.angle(-y)).combine(Z.angle(z)));
    }

    /**
     * scale everything
     *
     * @param scaleFactor
     */
    public void setScale(double scaleFactor) {
        s.setX(scaleFactor);
        s.setY(scaleFactor);
        s.setZ(scaleFactor);
    }

    /**
     * Scale by component
     */
    public void setScale(double x, double y, double z) {
        s.setX(x);
        s.setY(y);
        s.setZ(z);
    }

    public void setScaleX(double x) {
        s.setX(x);
    }

    public void setScaleY(double y) {
        s.setY(y);
    }

    public void setScaleZ(double z) {
        s.setZ(z);
    }

    public void setTranslate(double x, double y) {
        t.setX(x);
        t.setY(y);
    }

    public void setTranslate(double x, double y, double z) {
        t.setX(x);
        t.setY(y);
        t.setZ(z);
    }

    public void setTranslate(Tuple3f p) {
        t.setX(p.x);
        t.setY(p.y);
        t.setZ(p.z);
    }

    public void setTranslateX(double x) {
        t.setX(x);
    }

    public void setTranslateY(double y) {
        t.setY(y);
    }

    public void setTranslateZ(double z) {
        t.setZ(z);
    }

    @Override
    public String toString() {
        return "OrientedTxfm[t = (" + t.getX() + ", " + t.getY() + ", " + t.getZ() + ")  " + "r = (" + orientation
        + ") " + "s = (" + s.getX() + ", " + s.getY() + ", " + s.getZ() + ")]";
    }

    private Affine transform() {
        final var m = orientation.toMatrix();
        var t = new Affine();
        t.setToTransform(m.getM00(), m.getM10(), m.getM20(), m.getM30(), m.getM01(), m.getM11(), m.getM21(), m.getM31(),
                         m.getM02(), m.getM12(), m.getM22(), m.getM32());
        return t;
    }
}
