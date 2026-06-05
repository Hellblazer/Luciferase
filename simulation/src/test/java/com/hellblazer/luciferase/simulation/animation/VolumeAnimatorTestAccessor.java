package com.hellblazer.luciferase.simulation.animation;

import com.hellblazer.primeMover.controllers.RealTimeController;

import java.lang.reflect.Field;

/**
 * Test-only white-box accessor for {@link VolumeAnimator} private fields.
 */
class VolumeAnimatorTestAccessor {

    static RealTimeController getController(VolumeAnimator animator) throws Exception {
        Field f = VolumeAnimator.class.getDeclaredField("controller");
        f.setAccessible(true);
        return (RealTimeController) f.get(animator);
    }
}
