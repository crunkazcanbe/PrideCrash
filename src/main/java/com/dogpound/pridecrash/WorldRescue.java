package com.dogpound.pridecrash;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/**
 * "Force load" (requested feature): a mod that throws
 * while the world is starting no longer kills the world. Forge's LoadController.errorOccurred is skipped for the world
 * thread (MixinLoadControllerRescue), the problem is kept here and told to every player who joins.
 */
public final class WorldRescue {
    public static final List<String> PROBLEMS = new ArrayList<>();

    /** true = we handled it, the world keeps loading */
    public static boolean rescue(ModContainer mod, Throwable t) {
        if (!Config.forceWorldLoad || !"Server thread".equals(Thread.currentThread().getName())) return false;
        Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
        while (c.getCause() != null && c.getCause() != c && c.getMessage() != null && c.getMessage().startsWith("java.")) c = c.getCause();
        String why = c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
        String line = (mod == null ? "A mod" : mod.getName()) + " broke while the world was starting (" + why + ")";
        synchronized (PROBLEMS) { PROBLEMS.add(line); }
        PrideCrash.LOG.error("[PrideCrash] force-load: " + line + " — skipped, world keeps loading", t);
        return true;
    }

    @SubscribeEvent
    public void join(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.player instanceof EntityPlayerMP)) return;
        synchronized (PROBLEMS) {
            if (PROBLEMS.isEmpty()) return;
            e.player.sendMessage(new TextComponentString("§d§l✦ PrideCrash §r§7kept this world loading past " + PROBLEMS.size() + " broken mod" + (PROBLEMS.size() == 1 ? "" : "s") + ":"));
            for (String p : PROBLEMS) e.player.sendMessage(new TextComponentString("§c  ⚠ §f" + p));
            e.player.sendMessage(new TextComponentString("§7  Those mods may not work in this world until fixed. Full details: logs/latest.log"));
        }
    }
}
