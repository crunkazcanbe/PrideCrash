package com.dogpound.pridecrash;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * PrideCrash (her TODO: "crash analyzer — the exact guilty mod, in plain English"). Every crash report gets a
 * "PrideCrash says" section naming the mod to blame with a confidence, what went wrong and what to try; the next
 * time the game starts, a report screen shows it. /pridecrash opens every crash report you ever had.
 */
@Mod(modid = PrideCrash.MODID, name = "PrideCrash", version = "0.1.0", acceptableRemoteVersions = "*")
public class PrideCrash {
    public static final String MODID = "pridecrash";
    public static final Logger LOG = LogManager.getLogger("PrideCrash");

    @SidedProxy(clientSide = "com.dogpound.pridecrash.client.ClientProxy", serverSide = "com.dogpound.pridecrash.PrideCrash$Proxy")
    public static Proxy proxy;

    public static class Proxy { public void init() {} }

    public PrideCrash() {
        // client: stand in for LoliASM's crash screens from the very start, so boot crashes show PrideCrash too
        if (net.minecraftforge.fml.relauncher.FMLLaunchHandler.side().isClient()) {
            try { MinecraftForge.EVENT_BUS.register(Class.forName("com.dogpound.pridecrash.client.CrashScreenSwap").newInstance()); }
            catch (Throwable t) { LOG.warn("crash screen swap not registered: " + t); }
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new WorldRescue());
        proxy.init();
        LOG.info("[PrideCrash] loaded OK");
    }

    @SubscribeEvent
    public void config(ConfigChangedEvent.OnConfigChangedEvent e) {
        if (MODID.equals(e.getModID())) ConfigManager.sync(MODID, Config.Type.INSTANCE);
    }
}
