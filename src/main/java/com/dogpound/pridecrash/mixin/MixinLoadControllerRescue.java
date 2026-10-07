package com.dogpound.pridecrash.mixin;

import com.dogpound.pridecrash.WorldRescue;
import net.minecraftforge.fml.common.LoadController;
import net.minecraftforge.fml.common.ModContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** world-start errors (server thread) are recorded and skipped instead of crashing the world; see WorldRescue */
@Mixin(value = LoadController.class, remap = false)
public abstract class MixinLoadControllerRescue {
    @Inject(method = "errorOccurred", at = @At("HEAD"), cancellable = true)
    private void pridecrash$rescue(ModContainer mod, Throwable t, CallbackInfo ci) {
        if (WorldRescue.rescue(mod, t)) ci.cancel();
    }
}
