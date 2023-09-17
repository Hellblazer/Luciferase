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

import javafx.scene.Group;

/**
 * A group that encapsulates a chain of oriented transforms
 *
 * @author hal.hildebrand
 */
public class OrientedGroup extends Group {
    private final OrientedTxfm txfm;

    public OrientedGroup(OrientedTxfm txfm) {
        this.txfm = txfm;
        apply();
    }

    public void apply() {
        txfm.accept(this);
    }

    public OrientedTxfm getTransform() {
        return txfm;
    }
}
