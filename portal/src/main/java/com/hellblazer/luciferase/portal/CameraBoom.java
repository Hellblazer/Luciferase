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

import static com.hellblazer.luciferase.geometry.Rotor3f.RotationOrder.ZYX;

import com.hellblazer.luciferase.geometry.Rotor3f.RotationOrder;

import javafx.scene.Camera;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

/**
 * @author hal.hildebrand
 */
public class CameraBoom {
    protected final OrientedTxfm control = new OrientedTxfm();
    protected final Rotate       inverse = new Rotate();
    protected final OrientedTxfm root    = new OrientedTxfm();
    private final Camera         camera;

    public CameraBoom(Camera camera) {
        root.next(control);
        inverse.setAxis(Rotate.Z_AXIS);
        inverse.setAngle(180);
        this.camera = camera;
    }

    public Camera getCamera() {
        return camera;
    }

    public OrientedTxfm getControl() {
        return control;
    }

    public Rotate getInverse() {
        return inverse;
    }

    public OrientedTxfm getRoot() {
        return root;
    }

    public Translate getTranslation() {
        return control.translation();
    }

    public void reset() {
        // TODO Auto-generated method stub

    }

    public void rotate(float x, float y, float z) {
        root.rotate(ZYX, x, y, z);
    }

    public void rotate(RotationOrder order, float x, float y, float z) {
        root.rotate(order, x, y, z);
    }

    public void setXY(float x, float y) {
        control.translate(x, y, 0);
    }

    public void translate(float i, float j, float k) {
        control.translate(i, j, k);
    }
}
