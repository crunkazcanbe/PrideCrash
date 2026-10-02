package com.dogpound.pridecrash.client;

import com.dogpound.pridecrash.Analyzer;
import com.dogpound.pridecrash.Config;
import com.dogpound.pridecrash.PrideCrash;
import com.dogpound.pridecrash.Reports;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.crash.CrashReport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.util.*;

public class ClientProxy extends PrideCrash.Proxy {
    private static GuiReport openNext;
    private boolean checkedPending;
    private Analyzer.Result banner;                                  // "your game crashed last time" on the main menu
    private int[] bannerBox;

    @SubscribeEvent
    public void drawBanner(net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent.Post e) {
        if (banner == null || !isTitle(e.getGui())) { bannerBox = null; return; }
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.client.gui.FontRenderer fr = mc.fontRenderer;
        String who = banner.top() == null ? "" : "  §7Most likely: §f" + banner.top().name + " §7(" + banner.top().percent + "%)";
        String l1 = "§c⚠ §lYour game crashed last time", l2 = fr.trimStringToWidth("§f" + banner.title, 300), btn = "See why ▶";
        int w = Math.min(e.getGui().width - 10, Math.max(fr.getStringWidth(l1 + who), fr.getStringWidth(l2)) + fr.getStringWidth(btn) + 40);
        int x = (e.getGui().width - w) / 2, y = 4, h = 26;
        net.minecraft.client.gui.Gui.drawRect(x, y, x + w, y + h, 0xE0140E22);
        int seg = w / com.dogpound.pridecrash.PrideFrame.RAINBOW.length;
        for (int i = 0; i < com.dogpound.pridecrash.PrideFrame.RAINBOW.length; i++)
            net.minecraft.client.gui.Gui.drawRect(x + i * seg, y, i == com.dogpound.pridecrash.PrideFrame.RAINBOW.length - 1 ? x + w : x + (i + 1) * seg, y + 2, com.dogpound.pridecrash.PrideFrame.RAINBOW[i]);
        fr.drawStringWithShadow(fr.trimStringToWidth(l1 + who, w - fr.getStringWidth(btn) - 30), x + 6, y + 5, 0xFFFFFF);
        fr.drawStringWithShadow(fr.trimStringToWidth(l2, w - fr.getStringWidth(btn) - 30), x + 6, y + 15, 0xFFFFFF);
        int bw = fr.getStringWidth(btn) + 14, bx = x + w - bw - 5;
        com.dogpound.pridecrash.PrideFrame.button(bx, y + 5, bw, 16, btn, 0xFF6A3FA0, e.getMouseX(), e.getMouseY());
        String x_ = "✕";
        fr.drawStringWithShadow("§8" + x_, x + w - 2 - fr.getStringWidth(x_), y + h - 9, 0xFFFFFF);
        bannerBox = new int[]{x, y, w, h, bx, bw};
    }

    @SubscribeEvent
    public void clickBanner(net.minecraftforge.client.event.GuiScreenEvent.MouseInputEvent.Pre e) {
        if (bannerBox == null || banner == null || !org.lwjgl.input.Mouse.getEventButtonState() || org.lwjgl.input.Mouse.getEventButton() != 0) return;
        GuiScreen g = e.getGui();
        int mx = org.lwjgl.input.Mouse.getEventX() * g.width / Minecraft.getMinecraft().displayWidth;
        int my = g.height - org.lwjgl.input.Mouse.getEventY() * g.height / Minecraft.getMinecraft().displayHeight - 1;
        int[] b = bannerBox;
        if (mx < b[0] || my < b[1] || mx >= b[0] + b[2] || my >= b[1] + b[3]) return;
        e.setCanceled(true);
        if (mx >= b[0] + b[2] - 12 && my >= b[1] + b[3] - 10) { banner = null; return; }   // ✕ = dismiss
        Minecraft.getMinecraft().displayGuiScreen(new GuiReport(g, banner, true));
        banner = null;
    }

    @Override
    public void init() {
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new Cmd());
        // did the last run die without a crash report? (killed, or Java/driver crash → hs_err file)
        try {
            Analyzer.Result silent = com.dogpound.pridecrash.Silent.checkAndMark(Reports.gameDir(), Reports.crashDir(), Reports.ourDir());
            if (silent != null && !Reports.pending().isFile()) {
                java.nio.file.Files.write(Reports.pending().toPath(), silent.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                PrideCrash.LOG.info("the last run closed without a crash report: " + silent.title);
            }
        } catch (Throwable t) {
            PrideCrash.LOG.warn("silent-crash check failed: " + t);
        }
    }

    /** after a crash: the first main menu shows what happened (once) */
    /** Minecraft's title screen or one that replaces it (PrideCanvas's DPMainMenu, CustomMainMenu…) */
    static boolean isTitle(GuiScreen g) {
        if (g == null) return false;
        String n = g.getClass().getSimpleName();
        return g instanceof GuiMainMenu || n.endsWith("MainMenu") || n.contains("TitleScreen");
    }

    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.LOWEST)   // after menu mods swapped theirs in
    public void open(GuiOpenEvent e) {
        if (checkedPending || !isTitle(e.getGui())) return;
        checkedPending = true;
        File p = Reports.pending();
        if (!Config.popupAfterCrash || !p.isFile()) return;
        try {
            Analyzer.Result r = Reports.fromJson(p);
            File seen = new File(Reports.ourDir(), "last-shown.json");
            seen.delete();
            p.renameTo(seen);
            if (Config.fullScreenPopup) e.setGui(new GuiReport(e.getGui(), r, true));
            else banner = r;
            PrideCrash.LOG.info("last crash waiting on the main menu: " + r.title);
        } catch (Exception ex) {
            PrideCrash.LOG.warn("couldn't show the last crash: " + ex);
        }
    }

    /** chat commands can't open a screen directly (chat closes right after) — open it next tick */
    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || openNext == null) return;
        Minecraft.getMinecraft().displayGuiScreen(openNext);
        openNext = null;
    }

    static class Cmd extends CommandBase {
        @Override public String getName() { return "pridecrash"; }
        @Override public String getUsage(ICommandSender s) { return "/pridecrash [list|latest|test] [summary|suspects|stack|details|mixins|ai]"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean checkPermission(MinecraftServer server, ICommandSender sender) { return true; }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
            String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "test":
                    // a real crash through the normal path, so the whole thing (hook → file → next-start screen) is tested
                    Minecraft.getMinecraft().crashed(new CrashReport("PrideCrash test crash (/pridecrash test)",
                            new IllegalStateException("This is a pretend crash to test PrideCrash — nothing is broken.")));
                    return;
                case "latest": {
                    List<File> fs = Reports.crashFiles();
                    openNext = new GuiReport(null, fs.isEmpty() ? null : Reports.load(fs.get(0)), false, tabOf(args));
                    return;
                }
                default:
                    openNext = new GuiReport(null, null, false, tabOf(args));
            }
        }

        /** "/pridecrash latest stack" → the Stack tab */
        private static int tabOf(String[] args) {
            String t = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
            List<String> names = Arrays.asList("summary", "suspects", "stack", "details", "mixins", "ai");
            return Math.max(0, names.indexOf(t));
        }

        @Override
        public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos pos) {
            if (args.length == 1) return getListOfStringsMatchingLastWord(args, "list", "latest", "test");
            if (args.length == 2) return getListOfStringsMatchingLastWord(args, "summary", "suspects", "stack", "details", "mixins", "ai");
            return Collections.emptyList();
        }
    }
}
