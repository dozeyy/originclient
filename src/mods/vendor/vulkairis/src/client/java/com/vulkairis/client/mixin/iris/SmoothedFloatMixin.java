package com.vulkairis.client.mixin.iris;

import net.irisshaders.iris.gl.uniform.FloatSupplier;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.transforms.SmoothedFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = SmoothedFloat.class, remap = false)
public class SmoothedFloatMixin {
    @Shadow
    private FloatSupplier unsmoothed;
    @Shadow
    private float decayConstantUp;
    @Shadow
    private float decayConstantDown;
    @Shadow
    private float accumulator;
    @Shadow
    private boolean hasInitialValue;

    /**
     * @author Vulkairis
     * @reason Ensure GUI elements and transitions are never stuck with 0.0 alpha when target is positive.
     */
    @Overwrite
    public float getAsFloat() {
        if (!this.hasInitialValue) {
            return this.unsmoothed.getAsFloat();
        }
        float target = this.unsmoothed.getAsFloat();
        // If target is requesting full visibility but accumulator is stuck at zero, return target immediately
        if (target > 0.01f && this.accumulator < 0.01f) {
            return target;
        }
        return this.accumulator;
    }

    /**
     * @author Vulkairis
     * @reason Provide a realistic frameTime fallback (~60fps) when world pipeline is paused or destroyed
     *         so GUI elements transition smoothly under VulkanMod instead of freezing at factor 0.
     */
    @Overwrite
    private void update() {
        if (!this.hasInitialValue) {
            this.accumulator = this.unsmoothed.getAsFloat();
            this.hasInitialValue = true;
            return;
        }

        float target = this.unsmoothed.getAsFloat();
        float frameTime = SystemTimeUniforms.TIMER.getLastFrameTime();
        if (frameTime <= 0.0001f) {
            frameTime = 0.016667f; // Fallback ~60 FPS
        }

        float decay = (target > this.accumulator) ? this.decayConstantUp : this.decayConstantDown;
        float factor = 1.0f - (float) Math.exp(-decay * frameTime);
        this.accumulator = this.accumulator + (target - this.accumulator) * factor;
    }
}
