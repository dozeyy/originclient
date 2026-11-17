package com.vulkairis.client.mixin.iris;

import com.vulkairis.iris.StateUpdateNotifierManager;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.uniform.Uniform;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * Mixin into Iris's ProgramUniforms$Builder to guard against NullPointerExceptions
 * during shader pipeline creation under VulkanMod.
 */
@Mixin(value = ProgramUniforms.Builder.class, remap = false)
public class ProgramUniformsBuilderMixin {
    @Shadow @Final private Map<Integer, String> locations;
    @Shadow @Final private Map<String, Uniform> dynamic;
    @Shadow @Final private List<ValueUpdateNotifier> notifiersToReset;

    @Inject(method = "addDynamicUniform(Lnet/irisshaders/iris/gl/uniform/Uniform;Lnet/irisshaders/iris/gl/state/ValueUpdateNotifier;)Lnet/irisshaders/iris/gl/program/ProgramUniforms$Builder;",
            at = @At("HEAD"), cancellable = true)
    private void vulkairis$guardAddDynamicUniform(Uniform uniform, ValueUpdateNotifier notifier, CallbackInfoReturnable<ProgramUniforms.Builder> cir) {
        if (uniform == null) {
            cir.setReturnValue((ProgramUniforms.Builder) (Object) this);
            return;
        }

        // Ensure state notifiers are initialized
        StateUpdateNotifierManager.ensureInitialized();

        ValueUpdateNotifier safeNotifier = (notifier != null) ? notifier : (listener -> {});

        int loc = uniform.getLocation();
        String name = this.locations.get(loc);
        if (name == null) {
            name = "uniform_" + loc;
            this.locations.put(loc, name);
        }

        this.dynamic.put(name, uniform);
        this.notifiersToReset.add(safeNotifier);

        cir.setReturnValue((ProgramUniforms.Builder) (Object) this);
    }

    @Inject(method = "addUniform(Lnet/irisshaders/iris/gl/uniform/UniformUpdateFrequency;Lnet/irisshaders/iris/gl/uniform/Uniform;)Lnet/irisshaders/iris/gl/program/ProgramUniforms$Builder;",
            at = @At("HEAD"))
    private void vulkairis$guardAddUniform(UniformUpdateFrequency frequency, Uniform uniform, CallbackInfoReturnable<ProgramUniforms.Builder> cir) {
        if (uniform != null) {
            int loc = uniform.getLocation();
            if (!this.locations.containsKey(loc)) {
                this.locations.put(loc, "uniform_" + loc);
            }
        }
    }
}
