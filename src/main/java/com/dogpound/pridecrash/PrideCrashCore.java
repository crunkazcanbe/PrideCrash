package com.dogpound.pridecrash;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

/** Loads the one early patch: every crash report gets a "PrideCrash says" section. */
@IFMLLoadingPlugin.Name("PrideCrash")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1005)
public class PrideCrashCore implements IFMLLoadingPlugin, IEarlyMixinLoader {
    @Override public List<String> getMixinConfigs() { return Collections.singletonList("pridecrash.mixins.json"); }
    @Override public void injectData(Map<String, Object> data) {
        // the earliest moment we run: look at how the LAST run ended before anything here can crash
        try { Silent.early(Reports.gameDir(), Reports.crashDir(), Reports.ourDir()); } catch (Throwable ignored) {}
    }
    @Override public String[] getASMTransformerClass() { return new String[0]; }
    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public String getAccessTransformerClass() { return null; }
}
