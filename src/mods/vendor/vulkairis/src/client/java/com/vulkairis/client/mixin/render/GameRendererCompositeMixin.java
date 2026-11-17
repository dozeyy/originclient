package com.vulkairis.client.mixin.render;

import com.vulkairis.origin.composite.CompositeRunner;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Origin (step 2). Target: {@code GameRenderer.render(DeltaTracker, boolean)}, right after its call to
 * {@code renderLevel(DeltaTracker)} (before the screenshot hook and the GUI).
 *
 * <p>Why: the pack's deferred/composite/final chain must run exactly once per frame, after the whole
 * world is drawn and before the GUI. Upstream ran it from {@code Renderer.endRenderPass}, which fires
 * on every render-pass end (re-entrant, several times a frame, and also on menus).</p>
 *
 * <p>Without it: no post chain runs, so a shader pack changes nothing on screen (the vanilla world
 * shows through, which is also the fail-soft state).</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCompositeMixin {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER))
    private void vulkairis$runPostChain(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        CompositeRunner.afterWorld();
    }
}
