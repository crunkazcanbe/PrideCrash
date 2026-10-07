package com.dogpound.pridecrash.client;

import com.dogpound.pridecrash.Analyzer;
import com.dogpound.pridecrash.Config;
import com.dogpound.pridecrash.PrideCrash;
import com.dogpound.pridecrash.Reports;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Registered from PrideCrash's constructor (as early as a mod can) so even crashes while booting get our screen. */
public class CrashScreenSwap {
    /**
     * LoliASM (inside chibi) keeps the game alive after a crash and shows its own screen. Requested: show
     * PrideCrash instead - which mod, plain English, what to try - with "Main Menu" (in-game crash, LoliASM already
     * recovered) or "Quit Game" (crash while booting, the game can't continue).
     */
    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.HIGHEST)
    public void replaceLoliCrashScreen(net.minecraftforge.client.event.GuiOpenEvent e) {
        GuiScreen g = e.getGui(), ours = swap(g);
        if (ours != g) e.setGui(ours);
    }

    /**
     * LoliASM's crash screen -> ours. Called from the event above AND from MixinMinecraftCrashScreen (Minecraft's own
     * displayGuiScreen), because a crash while mods are still being built happens before this mod exists to hear events
     * (2026-10-04 02:23: Ksyxis boot crash showed LoliASM's screen).
     */
    public static GuiScreen swap(GuiScreen g) {
        if (g == null) return g;
        String n = g.getClass().getName();
        boolean crash = n.equals("zone.rong.loliasm.common.crashes.GuiCrashScreen");
        boolean init = n.equals("zone.rong.loliasm.common.crashes.GuiInitErrorScreen");
        if (!crash && !init) return g;
        try {
            java.lang.reflect.Field rf = g.getClass().getSuperclass().getDeclaredField("report");
            rf.setAccessible(true);
            net.minecraft.crash.CrashReport report = (net.minecraft.crash.CrashReport) rf.get(g);
            boolean index = false;
            try { index = Config.indexJars; } catch (Throwable ignored) { }
            Analyzer.Result r = Analyzer.analyze(report.getCompleteReport(), index ? Reports.index() : null);
            if (report.getFile() != null) r.file = report.getFile().getName().replaceAll("-(client|server)\\.txt$", "").replaceAll("\\.txt$", "");
            GuiReport ours = new GuiReport(crash ? new GuiMainMenu() : null, r, true);
            if (crash) ours.withClose("\u2302 Main Menu", () -> Minecraft.getMinecraft().displayGuiScreen(new GuiMainMenu()));
            else ours.withClose("\u2716 Quit Game", () -> Minecraft.getMinecraft().shutdown());
            return ours;
        } catch (Throwable t) {
            PrideCrash.LOG.warn("could not replace LoliASM's crash screen, keeping theirs: " + t);
            return g;
        }
    }

}
