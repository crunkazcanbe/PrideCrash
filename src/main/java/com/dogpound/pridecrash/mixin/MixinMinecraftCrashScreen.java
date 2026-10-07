package com.dogpound.pridecrash.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Every screen Minecraft is told to show passes here first: LoliASM's crash screens become PrideCrash, even at boot. */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MixinMinecraftCrashScreen {
    @ModifyVariable(method = "func_147108_a", at = @At("HEAD"), argsOnly = true, remap = false, require = 0)
    private GuiScreen pridecrash$ourScreen(GuiScreen screen) {
        return com.dogpound.pridecrash.client.CrashScreenSwap.swap(screen);
    }
}
