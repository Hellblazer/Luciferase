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

import javafx.scene.Camera;
import javafx.scene.Node;
import javafx.scene.Scene;

/**
 * @author hal.hildebrand
 */
public class Portal {
    private final Animus<Node>   avatar;
    private final Animus<Camera> camera;

    public Portal(Node avatar, Camera camera) {
        this.avatar = new Animus<>(avatar);
        this.camera = new Animus<>(camera);
    }

    public Animus<Node> getAvatar() {
        return avatar;
    }

    public Animus<Camera> getCamera() {
        return camera;
    }

    public void setCamera(Scene scene) {
        scene.setCamera(camera.getAnimated());
    }
}
