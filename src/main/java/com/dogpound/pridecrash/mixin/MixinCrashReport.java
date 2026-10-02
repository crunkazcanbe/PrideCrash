package com.dogpound.pridecrash.mixin;

import com.dogpound.pridecrash.Reports;
import net.minecraft.crash.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CrashReport.getCompleteReport() builds the text that goes into crash-reports/*.txt and the log. We add our
 * section at the end (priority 2000 so MixinBooter's "Mixins in Stacktrace" is already in the text we read).
 * The analysis is done once per report; a failure here must never make the crash worse, so everything is caught.
 */
@Mixin(value = CrashReport.class, priority = 2000)
public abstract class MixinCrashReport {
    @Unique private String pridecrash$section;
    @Unique private boolean pridecrash$busy;

    @Inject(method = "func_71502_e", at = @At("RETURN"), cancellable = true, remap = false)
    private void pridecrash$append(CallbackInfoReturnable<String> cir) {
        if (pridecrash$busy) return;
        try {
            pridecrash$busy = true;
            if (pridecrash$section == null) pridecrash$section = Reports.onCrash(cir.getReturnValue());
            if (!pridecrash$section.isEmpty()) cir.setReturnValue(cir.getReturnValue() + pridecrash$section);
        } catch (Throwable t) {
            pridecrash$section = "";
        } finally {
            pridecrash$busy = false;
        }
    }
}
