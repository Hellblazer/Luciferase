/**
 * Copyright (c) 2016 Chiral Behaviors, LLC, all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.hellblazer.luciferase.portal.mesh.explorer;

import javafx.scene.paint.PhongMaterial;

import static javafx.scene.paint.Color.*;

/**
 * @author halhildebrand
 */
public class Colors {
    public static final PhongMaterial   blackMaterial;
    public static final PhongMaterial[] blackMaterials;
    public static final PhongMaterial   blueMaterial;
    public static final PhongMaterial   cyanMaterial;
    public static final PhongMaterial[] eight4Materials;
    public static final PhongMaterial[] eightMaterials;
    public static final PhongMaterial   greenMaterial;
    public static final PhongMaterial   lavenderMaterial;
    public static final PhongMaterial   limeMaterial;
    public static final PhongMaterial   magentaMaterial;
    public static final PhongMaterial[] materials;
    public static final PhongMaterial   oliveMaterial;
    public static final PhongMaterial   orangeMaterial;
    public static final PhongMaterial   purpleMaterial;
    public static final PhongMaterial   redMaterial;
    public static final PhongMaterial   violetMaterial;
    public static final PhongMaterial   yellowMaterial;

    static {
        redMaterial = new PhongMaterial(RED);
        redMaterial.setSpecularColor(RED);
        redMaterial.setDiffuseColor(RED);

        blueMaterial = new PhongMaterial(BLUE);
        blueMaterial.setSpecularColor(BLUE);
        blueMaterial.setDiffuseColor(BLUE);

        greenMaterial = new PhongMaterial(GREEN);
        greenMaterial.setSpecularColor(GREEN);
        greenMaterial.setDiffuseColor(GREEN);

        yellowMaterial = new PhongMaterial(YELLOW);
        yellowMaterial.setSpecularColor(YELLOW);
        yellowMaterial.setDiffuseColor(YELLOW);

        violetMaterial = new PhongMaterial(VIOLET);
        violetMaterial.setSpecularColor(VIOLET);
        violetMaterial.setDiffuseColor(VIOLET);

        orangeMaterial = new PhongMaterial(ORANGE);
        orangeMaterial.setSpecularColor(ORANGE);
        orangeMaterial.setDiffuseColor(ORANGE);

        cyanMaterial = new PhongMaterial(CYAN);
        cyanMaterial.setSpecularColor(CYAN);
        cyanMaterial.setDiffuseColor(CYAN);

        purpleMaterial = new PhongMaterial(PURPLE);
        purpleMaterial.setSpecularColor(PURPLE);
        purpleMaterial.setDiffuseColor(PURPLE);

        magentaMaterial = new PhongMaterial(MAGENTA);
        magentaMaterial.setSpecularColor(MAGENTA);
        magentaMaterial.setDiffuseColor(MAGENTA);

        lavenderMaterial = new PhongMaterial(LAVENDER);
        lavenderMaterial.setSpecularColor(LAVENDER);
        lavenderMaterial.setDiffuseColor(LAVENDER);

        oliveMaterial = new PhongMaterial(OLIVE);
        oliveMaterial.setSpecularColor(OLIVE);
        oliveMaterial.setDiffuseColor(OLIVE);

        limeMaterial = new PhongMaterial(LIME);
        limeMaterial.setSpecularColor(LIME);
        limeMaterial.setDiffuseColor(LIME);

        blackMaterial = new PhongMaterial(BLACK);
        blackMaterial.setSpecularColor(BLACK);
        blackMaterial.setDiffuseColor(BLACK);

        materials = new PhongMaterial[] { redMaterial, blueMaterial, greenMaterial, yellowMaterial, cyanMaterial,
                                          purpleMaterial, orangeMaterial, violetMaterial, magentaMaterial,
                                          lavenderMaterial, oliveMaterial, limeMaterial };
        blackMaterials = new PhongMaterial[] { blackMaterial, blackMaterial };
        eightMaterials = new PhongMaterial[] { redMaterial, blueMaterial, greenMaterial, yellowMaterial, redMaterial,
                                               blueMaterial, greenMaterial, yellowMaterial };
        eight4Materials = new PhongMaterial[] { redMaterial, blueMaterial, greenMaterial, yellowMaterial, blackMaterial,
                                                blackMaterial, blackMaterial, blackMaterial };
    }

}
