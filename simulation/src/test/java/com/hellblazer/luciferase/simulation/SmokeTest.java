package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.primeMover.controllers.SteppingController;
import org.junit.jupiter.api.Test;

/**
 * @author hal.hildebrand
 **/
public class SmokeTest {
    @Test
    public void smokin() {
        var controller = new SteppingController();
        var animator = new VolumeAnimator(controller, Constants.ROOT_SIMPLEX);
    
    }
}
