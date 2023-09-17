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

import static com.hellblazer.luciferase.geometry.Rotor3f.PrincipalAxis.X;
import static com.hellblazer.luciferase.geometry.Rotor3f.PrincipalAxis.Y;
import static com.hellblazer.luciferase.geometry.Rotor3f.PrincipalAxis.Z;

import java.util.function.Consumer;

import javax.vecmath.Tuple3f;

import com.hellblazer.luciferase.geometry.Rotor3f;
import com.hellblazer.luciferase.geometry.Rotor3f.RotationOrder;

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

    private OrientedTxfm    next;
    private final Rotor3f   orientation = new Rotor3f();
    private final Scale     s           = new Scale();
    private final Translate t           = new Translate();
    private final Affine    transform   = new Affine();

    @Override
    public void accept(Node node) {
        final var transforms = node.getTransforms();
        transforms.clear();
        var current = this;
        while (current != null) {
            transforms.addAll(current.t, current.transform, current.s);
            current = current.next;
        }
    }

    public OrientedTxfm next() {
        return next;
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

    public Rotor3f orientation() {
        return orientation;
    }

    public void pivot(Tuple3f pivot) {
        s.setPivotX(pivot.x);
        s.setPivotY(pivot.y);
        s.setPivotZ(pivot.z);
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
        s.setPivotX(0);
        s.setPivotY(0);
        s.setPivotZ(0);
    }

    public void rotate(RotationOrder order, float x, float y, float z) {
        switch (order) {
        case XYZ:
            orientation.set(X.angle(x).combine(Y.angle(y)).combine(Z.angle(z)));
            break;
        case XZY:
            orientation.set(X.angle(x).combine(Z.angle(z)).combine(Y.angle(y)));
            break;
        case YXZ:
            orientation.set(Y.angle(y).combine(X.angle(x).combine(Z.angle(z))));
            break;
        case YZX:
            orientation.set(Y.angle(y).combine(Z.angle(z)).combine(X.angle(x)));
            break;
        case ZXY:
            orientation.set(Z.angle(z).combine(X.angle(x)).combine(Y.angle(y)));
            break;
        case ZYX:
            orientation.set(Z.angle(z).combine(Y.angle(y)).combine(X.angle(x)));
            break;
        default:
            throw new IllegalArgumentException("Unknown rotation order: " + order);

        }
        transform();
    }

    public void rotate(RotationOrder order, Tuple3f angle) {
        rotate(order, angle.x, angle.y, angle.z);
    }

    public Scale scale() {
        return s;
    }

    public void scale(float x, float y, float z) {
        s.setX(x);
        s.setY(y);
        s.setZ(z);
    }

    public void scale(Tuple3f scale) {
        s.setX(scale.x);
        s.setY(scale.y);
        s.setZ(scale.z);
    }

    public void scale(Tuple3f scale, Tuple3f pivot) {
        s.setX(scale.x);
        s.setY(scale.y);
        s.setZ(scale.z);
        s.setPivotX(pivot.x);
        s.setPivotY(pivot.y);
        s.setPivotZ(pivot.z);
    }

    public void transform() {
        final var m = orientation.toMatrix();
        transform.setToTransform(m.getM00(), m.getM10(), m.getM20(), m.getM30(), m.getM01(), m.getM11(), m.getM21(),
                                 m.getM31(), m.getM02(), m.getM12(), m.getM22(), m.getM32());
    }

    public void translate(float x, float y, float z) {
        t.setX(x);
        t.setY(y);
        t.setZ(z);
    }

    public void translate(Tuple3f translation) {
        t.setX(translation.x);
        t.setY(translation.y);
        t.setZ(translation.z);
    }

    public Translate translation() {
        return t;
    }
}
