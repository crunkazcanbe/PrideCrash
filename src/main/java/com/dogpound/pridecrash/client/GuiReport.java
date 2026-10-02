package com.dogpound.pridecrash.client;

import com.dogpound.pridecrash.Analyzer;
import com.dogpound.pridecrash.Config;
import com.dogpound.pridecrash.PrideFrame;
import com.dogpound.pridecrash.Reports;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * The crash report screen. Left: every crash the game ever had (newest first, with the blamed mod once known).
 * Right: tabs — Summary (what happened, who to blame, what to try), Suspects (every mod with its score and why),
 * Stack (the stack with each line tagged by mod), Details (what was being updated, the raw exception) and Mixins
 * (which mods patch the Minecraft code that was running). Bottom: copy, open the file/folder, ask the AI.
 */
public class GuiReport extends GuiScreen {
    private static final String[] TABS = {"✦ Summary", "⚠ Suspects", "☰ Stack", "◉ Details", "⚙ Mixins", "✪ Ask AI"};
    private static final int RED = 0xFFE05A6A, GOLD = 0xFFFFC857, GREEN = 0xFF6EE7A0;

    private final GuiScreen back;
    private final boolean afterCrash;
    private final List<File> files = Reports.crashFiles();
    private final Map<String, String> sideLabel = new HashMap<>();          // file → blamed mod, from saved analyses
    private volatile Analyzer.Result result;
    private volatile boolean loading;
    private int selected = -1, tab, listScroll, scroll, contentH, hoverFile = -1;
    private boolean onlyMods = true;
    private volatile String aiText = "", aiState = "";
    private String toast = "";
    private long toastUntil;
    private PrideFrame f;
    private final List<Runnable> clicks = new ArrayList<>();                // hit-boxes rebuilt every frame
    private final List<int[]> boxes = new ArrayList<>();

    public GuiReport(GuiScreen back, Analyzer.Result r, boolean afterCrash, int tab) {
        this(back, r, afterCrash);
        this.tab = Math.max(0, Math.min(TABS.length - 1, tab));
    }

    public GuiReport(GuiScreen back, Analyzer.Result r, boolean afterCrash) {
        this.back = back;
        this.afterCrash = afterCrash;
        this.result = r;
        for (File fl : files) {
            File json = new File(Reports.ourDir(), fl.getName().replaceAll("-(client|server)\\.txt$", "").replaceAll("\\.txt$", "") + ".json");
            if (json.isFile()) try { Analyzer.Result a = Reports.fromJson(json); if (a.top() != null) sideLabel.put(fl.getName(), a.top().name); } catch (Exception ignored) {}
        }
        if (r != null) {
            File txt = Reports.txtFor(r);
            if (txt != null) selected = files.indexOf(txt);
        } else if (!files.isEmpty()) select(0);
    }

    private void select(int i) {
        if (i < 0 || i >= files.size() || loading) return;
        selected = i; scroll = 0; aiText = ""; aiState = "";
        loading = true;
        File fl = files.get(i);
        Thread t = new Thread(() -> {
            Analyzer.Result r = Reports.load(fl);
            result = r;
            if (r.top() != null) sideLabel.put(fl.getName(), r.top().name);
            loading = false;
        }, "PrideCrash read");
        t.setDaemon(true);
        t.start();
    }

    @Override public boolean doesGuiPauseGame() { return true; }

    // ------------------------------------------------------------------ drawing
    @Override
    public void drawScreen(int mx, int my, float pt) {
        f = PrideFrame.fit(width, height);
        clicks.clear(); boxes.clear(); drawHover = null;
        f.draw(this, "PrideCrash", files.size() + " crash report" + (files.size() == 1 ? "" : "s"));
        FontRenderer fr = fontRenderer;

        int listW = Math.min(170, f.cw / 3), top = f.cy, bottom = f.y + f.h - 30;
        if (afterCrash) {
            Gui.drawRect(f.cx, top, f.cx + f.cw, top + 14, 0x60E05A6A);
            fr.drawStringWithShadow("§lYour game crashed last time — here's what PrideCrash found.", f.cx + 5, top + 3, 0xFFFFFF);
            top += 18;
        }
        drawList(mx, my, f.cx, top, listW, bottom - top);
        int px = f.cx + listW + 8, pw = f.cx + f.cw - px;

        // tabs
        int tx = px, full = 0;
        for (String t : TABS) full += fr.getStringWidth(t) + 15;
        boolean compact = full > pw;                                           // small window: only the open tab gets its name
        for (int i = 0; i < TABS.length; i++) {
            String label = compact && tab != i ? TABS[i].substring(0, TABS[i].indexOf(' ')) : TABS[i];
            int tw = fr.getStringWidth(label) + 12;
            if (tx + tw > px + pw) break;
            final int k = i;
            boolean over = in(mx, my, tx, top, tw, 16);
            PrideFrame.tile(tx, top, tw, 16, PrideFrame.RAINBOW[i % PrideFrame.RAINBOW.length], over, tab == i);
            fr.drawStringWithShadow(label, tx + 6, top + 5, tab == i ? 0xFFFFFF : 0xCFC6DD);
            if (over && compact && tab != i) drawHover = TABS[i].substring(TABS[i].indexOf(' ') + 1);
            hit(tx, top, tw, 16, () -> { tab = k; scroll = 0; });
            tx += tw + 3;
        }
        int cy = top + 20, ch = bottom - cy - 4;
        PrideFrame.card(px, cy, pw, ch, PrideFrame.PINK);

        Analyzer.Result r = result;
        PrideFrame.clip(px + 1, cy + 2, pw - 8, ch - 4);
        clipTop = cy + 2; clipBottom = cy + ch - 2;
        int y = cy + 6 - scroll, x = px + 8, w = pw - 20;
        if (loading) fr.drawStringWithShadow("Reading the crash and every mod jar… (a couple of seconds the first time)", x, y, PrideFrame.DIM);
        else if (r == null) fr.drawStringWithShadow(files.isEmpty() ? "No crash reports — nothing has crashed. ❤" : "Pick a crash on the left.", x, y, PrideFrame.DIM);
        else {
            int start = y;
            switch (tab) {
                case 0: y = summary(r, x, y, w, mx, my); break;
                case 1: y = suspects(r, x, y, w, mx, my); break;
                case 2: y = stack(r, x, y, w, mx, my); break;
                case 3: y = details(r, x, y, w); break;
                case 4: y = mixins(r, x, y, w); break;
                default: y = ai(r, x, y, w, mx, my);
            }
            contentH = y - start + 8;
        }
        PrideFrame.unclip();
        clipTop = Integer.MIN_VALUE; clipBottom = Integer.MAX_VALUE;
        PrideFrame.scrollbar(px + pw - 5, cy + 2, ch - 4, scroll, ch - 4, contentH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH - ch + 4)));

        // bottom bar
        int by = f.y + f.h - 24, bx = f.cx;
        String close = back == null ? "Close" : afterCrash ? "Continue ▶" : "Back";
        int cw = fr.getStringWidth(close) + 20;
        short_ = fr.getStringWidth("⎘ Copy report☰ Open crash file▣ Open folder⟲ Re-check✪ Ask AI") + 5 * 18 > f.cw - cw - 8;
        bx = bar(bx, by, "⎘ Copy report", 0xFF6A3FA0, mx, my, r == null ? null : () -> { setClipboardString(r.toText()); toast("Copied — paste it anywhere (Discord, a bug report…)"); });
        bx = bar(bx, by, "☰ Open crash file", PrideFrame.BUTTON, mx, my, r == null ? null : () -> { File t = Reports.txtFor(r); if (t != null) open(t); });
        bx = bar(bx, by, "▣ Open folder", PrideFrame.BUTTON, mx, my, () -> open(Reports.crashDir()));
        bx = bar(bx, by, "⟲ Re-check", PrideFrame.BUTTON, mx, my, selected < 0 ? null : () -> {
            File j = new File(Reports.ourDir(), files.get(selected).getName().replaceAll("-(client|server)\\.txt$", "") + ".json");
            j.delete(); select(selected); toast("Checked again");
        });
        bar(bx, by, "✪ Ask AI", 0xFF24408E, mx, my, r == null ? null : () -> { tab = 5; askAi(r); });
        PrideFrame.button(f.cx + f.cw - cw, by, cw, 18, close, 0xFF008026, mx, my);
        hit(f.cx + f.cw - cw, by, cw, 18, () -> mc.displayGuiScreen(back));

        if (!toast.isEmpty() && System.currentTimeMillis() < toastUntil) {
            int tw = fr.getStringWidth(toast) + 16, tx2 = f.x + (f.w - tw) / 2;
            Gui.drawRect(tx2, f.y + f.h - 46, tx2 + tw, f.y + f.h - 30, 0xE0101018);
            Gui.drawRect(tx2, f.y + f.h - 46, tx2 + tw, f.y + f.h - 45, PrideFrame.PINK);
            fr.drawStringWithShadow(toast, tx2 + 8, f.y + f.h - 41, 0xFFFFFF);
        }
        super.drawScreen(mx, my, pt);
        if (drawHover != null) drawHoveringText(Collections.singletonList(drawHover), mx, my);
    }

    private boolean short_;

    private int bar(int x, int y, String label, int color, int mx, int my, Runnable action) {
        if (short_) label = label.replace("Copy report", "Copy").replace("Open crash file", "File").replace("Open folder", "Folder").replace("Re-check", "Redo").replace("Ask AI", "AI");
        int w = fontRenderer.getStringWidth(label) + 14;
        PrideFrame.button(x, y, w, 18, label, action == null ? 0xFF201A2A : color, mx, my);
        if (action != null) hit(x, y, w, 18, action);
        return x + w + 4;
    }

    private void drawList(int mx, int my, int x, int y, int w, int h) {
        FontRenderer fr = fontRenderer;
        Gui.drawRect(x, y, x + w, y + h, 0x50000000);
        int rowH = 26, visible = h / rowH;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, files.size() - visible)));
        hoverFile = -1;
        for (int i = listScroll; i < files.size() && i < listScroll + visible; i++) {
            File fl = files.get(i);
            int ry = y + (i - listScroll) * rowH;
            boolean over = in(mx, my, x, ry, w - 4, rowH - 2);
            if (over) hoverFile = i;
            boolean server = fl.getName().contains("-server");
            PrideFrame.tile(x + 1, ry + 1, w - 6, rowH - 2, server ? PrideFrame.BLUE : PrideFrame.PINK, over, i == selected);
            fr.drawStringWithShadow(niceDate(fl.getName()), x + 5, ry + 5, 0xFFFFFF);
            String badge = server ? "§bworld" : "§dgame";
            fr.drawStringWithShadow(badge, x + w - 8 - fr.getStringWidth(badge), ry + 5, 0xFFFFFF);
            String who = sideLabel.get(fl.getName());
            fr.drawStringWithShadow(fr.trimStringToWidth(who == null ? "§8not checked yet" : "§c⚠ §f" + who, w - 12), x + 5, ry + 15, 0xFFFFFF);
            final int k = i;
            hit(x, ry, w - 4, rowH - 2, () -> select(k));
        }
        PrideFrame.scrollbar(x + w - 4, y, h, listScroll, visible, files.size());
    }

    /** "crash-2026-09-28_23.06.46-server.txt" → "Sep 28  23:06" */
    private static String niceDate(String n) {
        try {
            String d = n.substring(6, 16), t = n.substring(17, 22).replace('.', ':');
            String[] mon = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
            return mon[Integer.parseInt(d.substring(5, 7)) - 1] + " " + Integer.parseInt(d.substring(8, 10)) + "  " + t;
        } catch (Exception e) { return n; }
    }

    // ------------------------------------------------------------------ tabs
    private int summary(Analyzer.Result r, int x, int y, int w, int mx, int my) {
        FontRenderer fr = fontRenderer;
        y = para("§l" + r.title, x, y, w, 0xFFFFFF) + 4;
        y = para(r.plain, x, y, w, 0xDDD6EA) + 8;
        Analyzer.Suspect s = r.top();
        PrideFrame.card(x - 2, y, w + 4, s == null ? 22 : 52, s == null ? PrideFrame.DIM : RED);
        if (s == null) {
            fr.drawStringWithShadow("Guilty mod: couldn't tell — only Minecraft/Forge/Java code was on the stack.", x + 4, y + 7, PrideFrame.DIM);
            y += 28;
        } else {
            fr.drawStringWithShadow("§7MOST LIKELY GUILTY", x + 4, y + 5, 0xFFFFFF);
            fr.drawStringWithShadow("§l" + s.name, x + 4, y + 17, 0xFFFFFF);
            String pct = s.percent + "% sure";
            fr.drawStringWithShadow(pct, x + w - fr.getStringWidth(pct), y + 17, confColor(s.percent));
            percentBar(x + 4, y + 30, w - 8, s.percent);
            if (s.file != null) fr.drawStringWithShadow("§8" + fr.trimStringToWidth(s.file, w - 10), x + 4, y + 39, 0xFFFFFF);
            y += 58;
            if (r.suspects.size() > 1) {
                StringBuilder also = new StringBuilder("§7Also involved: ");
                for (int i = 1; i < Math.min(4, r.suspects.size()); i++) also.append(i > 1 ? ", " : "").append("§f").append(r.suspects.get(i).name).append(" §8").append(r.suspects.get(i).percent).append("%§7");
                y = para(also.toString(), x, y, w, 0xFFFFFF) + 6;
            }
        }
        fr.drawStringWithShadow("§e★ What to try", x, y, 0xFFFFFF);
        y += 12;
        for (String t : r.tips) y = para("§d•§f " + t, x + 4, y, w - 4, 0xFFFFFF) + 3;
        y += 6;
        Gui.drawRect(x, y, x + w, y + 1, 0x30FFFFFF);
        y += 5;
        y = para("§8" + (r.time.isEmpty() ? "" : r.time + "  ·  ") + (r.side.equals("server") ? "the world (server side)" : "the game (client side)") + "  ·  " + r.description, x, y, w, 0xFFFFFF);
        y = para("§8" + r.exception.substring(r.exception.lastIndexOf('.') + 1) + (r.message.isEmpty() ? "" : ": " + r.message), x, y + 2, w, 0xFFFFFF);
        return y;
    }

    private int suspects(Analyzer.Result r, int x, int y, int w, int mx, int my) {
        FontRenderer fr = fontRenderer;
        if (r.suspects.isEmpty()) return para("No mod code was on the stack, so there's no one to blame — this one is Minecraft, Forge or Java itself (or memory).", x, y, w, PrideFrame.DIM);
        y = para("§7How the blame is worked out: the mod whose code threw the error counts most, lines nearer the error count more, the real cause (last \"Caused by\") counts extra, and mods that only patched the Minecraft code on the way count a little.", x, y, w, 0xFFFFFF) + 6;
        int rank = 1;
        for (Analyzer.Suspect s : r.suspects) {
            List<String> why = new ArrayList<>();
            for (String line : s.why) why.addAll(fr.listFormattedStringToWidth("- " + line, w - 16));
            int h = 34 + why.size() * 10 + 18;
            PrideFrame.card(x - 2, y, w + 4, h, rank == 1 ? RED : PrideFrame.RAINBOW[rank % PrideFrame.RAINBOW.length]);
            fr.drawStringWithShadow("§l#" + rank + "  " + s.name, x + 4, y + 5, 0xFFFFFF);
            String pct = s.percent + "%";
            fr.drawStringWithShadow(pct, x + w - fr.getStringWidth(pct), y + 5, confColor(s.percent));
            percentBar(x + 4, y + 16, w - 8, s.percent);
            fr.drawStringWithShadow("§8" + (s.file == null ? "jar not found — guessed from its package name" : s.file), x + 4, y + 22, 0xFFFFFF);
            int ly = y + 34;
            for (String l : why) { fr.drawStringWithShadow(l, x + 8, ly, 0xC8C0D8); ly += 10; }
            final String name = s.name;
            int bx = x + 4;
            bx = mini(bx, ly + 2, "⎘ Copy name", mx, my, () -> { setClipboardString(name); toast("Copied " + name); });
            bx = mini(bx, ly + 2, "Find updates ↗", mx, my, () -> web("https://www.curseforge.com/minecraft/search?page=1&pageSize=20&sortBy=relevancy&class=mc-mods&search=" + enc(name)));
            mini(bx, ly + 2, "Known issues ↗", mx, my, () -> web("https://github.com/search?type=issues&q=" + enc(name + " " + r.exception.substring(r.exception.lastIndexOf('.') + 1))));
            y += h + 5;
            rank++;
        }
        return y;
    }

    private int stack(Analyzer.Result r, int x, int y, int w, int mx, int my) {
        FontRenderer fr = fontRenderer;
        int bx = mini(x, y, onlyMods ? "☑ Only lines from mods" : "☐ Only lines from mods", mx, my, () -> { onlyMods = !onlyMods; scroll = 0; });
        mini(bx, y, "⎘ Copy stack", mx, my, () -> {
            StringBuilder b = new StringBuilder(r.exception + ": " + r.message + "\n");
            for (Analyzer.Frame fl : r.frames) b.append("  at ").append(fl.cls).append('.').append(fl.method).append('(').append(fl.file).append(':').append(fl.line).append(")\n");
            setClipboardString(b.toString()); toast("Stack copied");
        });
        y += 18;
        fr.drawStringWithShadow("§d■ §7mod code   §b■ §7Minecraft code a mod patched   §8■ Minecraft / Forge / Java", x, y, 0xFFFFFF);
        y += 14;
        int block = -1, hidden = 0;
        for (Analyzer.Frame fl : r.frames) {
            if (fl.block != block) {
                if (hidden > 0) { fr.drawStringWithShadow("§8   … " + hidden + " Minecraft/Java lines hidden", x, y, 0xFFFFFF); y += 10; hidden = 0; }
                block = fl.block;
                fr.drawStringWithShadow(block == 0 ? "§c§l" + r.exception : "§6§lCaused by " + r.rootException, x, y + 2, 0xFFFFFF);
                y += 14;
            }
            boolean mod = !fl.mod.isEmpty();
            if (onlyMods && !mod && !fl.mixin) { hidden++; continue; }
            if (hidden > 0) { fr.drawStringWithShadow("§8   … " + hidden + " Minecraft/Java lines hidden", x, y, 0xFFFFFF); y += 10; hidden = 0; }
            String cls = fl.cls.substring(fl.cls.lastIndexOf('.') + 1);
            String tag = mod ? fl.mod : fl.mixin ? "patched: " + fl.file.replace(".java", "") : "";
            int tagW = tag.isEmpty() ? 0 : fr.getStringWidth(tag) + 8;
            if (mod) Gui.drawRect(x - 2, y - 1, x + w, y + 9, 0x25F5A9B8);
            String line = (mod ? "§d" : fl.mixin ? "§b" : "§8") + cls + "." + fl.method + (fl.line > 0 ? "§7:" + fl.line : "");
            fr.drawStringWithShadow(fr.trimStringToWidth(line, w - tagW - 4), x + 4, y, 0xFFFFFF);
            if (!tag.isEmpty()) {
                int tx = x + w - tagW + 4;
                Gui.drawRect(tx - 3, y - 1, x + w, y + 9, mod ? 0x60732982 : 0x6024408E);
                fr.drawStringWithShadow(tag, tx, y, 0xFFFFFF);
            }
            if (in(mx, my, x, y - 1, w, 10)) drawHover = fl.cls + "." + fl.method + "(" + fl.file + ":" + fl.line + ")";
            y += 11;
        }
        if (hidden > 0) { fr.drawStringWithShadow("§8   … " + hidden + " Minecraft/Java lines hidden", x, y, 0xFFFFFF); y += 10; }
        if (r.frames.isEmpty()) y = para("§8No stack lines in this report.", x, y, w, 0xFFFFFF);
        return y;
    }
    private String drawHover;

    private int details(Analyzer.Result r, int x, int y, int w) {
        y = kv("When", r.time, x, y, w);
        y = kv("Side", r.side.equals("server") ? "server — the world/logic" : "client — the game window/drawing", x, y, w);
        y = kv("Minecraft said", r.description, x, y, w);
        y = kv("Error", r.exception, x, y, w);
        if (!r.message.isEmpty()) y = kv("Message", r.message, x, y, w);
        if (!r.rootException.equals(r.exception)) {
            y = kv("Real cause", r.rootException, x, y, w);
            if (!r.rootMessage.isEmpty()) y = kv("Its message", r.rootMessage, x, y, w);
        }
        y = kv("Stack lines", r.frames.size() + " (" + r.frames.stream().filter(fl -> !fl.mod.isEmpty()).count() + " from mods, "
                + r.frames.stream().filter(fl -> fl.mixin).count() + " patched by mixins)", x, y, w);
        y += 6;
        if (!r.details.isEmpty()) {
            fontRenderer.drawStringWithShadow("§e★ What was being updated", x, y, 0xFFFFFF);
            y += 12;
            for (String d : r.details) y = para("§7" + d, x + 4, y, w - 4, 0xFFFFFF) + 2;
        }
        y += 6;
        y = para("§8File: " + r.file, x, y, w, 0xFFFFFF);
        return y;
    }

    private int mixins(Analyzer.Result r, int x, int y, int w) {
        FontRenderer fr = fontRenderer;
        if (r.mixinsOn.isEmpty()) return para("§8This report has no \"Mixins in Stacktrace\" list (MixinBooter adds it when mods patch the code that crashed).", x, y, w, 0xFFFFFF);
        y = para("§7Mods that patch the Minecraft code that was running when it crashed. Two mods patching the same thing is a common cause of crashes.", x, y, w, 0xFFFFFF) + 6;
        for (Map.Entry<String, List<String>> e : r.mixinsOn.entrySet()) {
            String target = e.getKey().substring(e.getKey().lastIndexOf('.') + 1);
            fr.drawStringWithShadow("§b§l" + target + " §8(" + e.getValue().size() + ")", x, y, 0xFFFFFF);
            y += 11;
            Map<String, Integer> byMod = new LinkedHashMap<>();
            for (String mx : e.getValue()) byMod.merge(modName(mx), 1, Integer::sum);
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Integer> m : byMod.entrySet()) b.append(b.length() == 0 ? "" : "§8, ").append("§f").append(m.getKey()).append(m.getValue() > 1 ? " §8×" + m.getValue() : "");
            y = para(b.toString(), x + 6, y, w - 6, 0xFFFFFF) + 5;
        }
        return y;
    }

    private static String modName(String cls) {
        try {
            com.dogpound.pridecrash.ModIndex.Jar j = Config.indexJars ? Reports.index().ofClass(cls) : null;
            if (j != null) return j.name;
        } catch (Throwable ignored) {}
        String[] p = cls.split("\\.");
        return p.length > 2 ? p[p.length > 3 && p[0].length() <= 4 ? 2 : 1] : cls;
    }

    private int ai(Analyzer.Result r, int x, int y, int w, int mx, int my) {
        y = para("§7Sends this crash to your own AI (" + (Config.aiUrl.isEmpty() ? "not set — see config/pridecrash.cfg" : Config.aiModel + " at " + Config.aiUrl) + ") and asks it to explain in plain words. Nothing leaves your computer unless that address does.", x, y, w, 0xFFFFFF) + 6;
        if (aiText.isEmpty() && !aiState.startsWith("…")) {
            mini(x, y, "✪ Ask now", mx, my, () -> askAi(r));
            y += 18;
        }
        if (!aiState.isEmpty()) y = para(aiState.startsWith("…") ? "§e" + aiState : "§c" + aiState, x, y, w, 0xFFFFFF) + 4;
        if (!aiText.isEmpty()) {
            for (String para : aiText.split("\n")) y = para(para.replace("**", "§l").replace("`", "§b"), x, y, w, 0xE8E0F0) + 1;
            y += 6;
            mini(x, y, "⎘ Copy answer", mx, my, () -> { setClipboardString(aiText); toast("Copied"); });
            y += 18;
        }
        return y;
    }

    private void askAi(Analyzer.Result r) {
        if (Config.aiUrl.isEmpty()) { aiState = "No AI address set (config/pridecrash.cfg → aiUrl)."; return; }
        if (aiState.startsWith("…")) return;
        aiState = "… asking " + Config.aiModel + " (the first answer can take a minute while the model loads)";
        aiText = "";
        Thread t = new Thread(() -> {
            try {
                String raw = "";
                File txt = Reports.txtFor(r);
                if (txt != null && txt.isFile()) {
                    List<String> ls = Files.readAllLines(txt.toPath(), StandardCharsets.UTF_8);
                    raw = String.join("\n", ls.subList(0, Math.min(80, ls.size())));
                }
                JsonObject body = new JsonObject();
                body.addProperty("model", Config.aiModel);
                body.addProperty("temperature", 0.3);
                body.addProperty("max_tokens", 700);
                JsonArray msgs = new JsonArray();
                msgs.add(msg("system", "You explain Minecraft 1.12.2 modpack crashes (Forge/Cleanroom, hundreds of mods) to a player who is not a programmer. "
                        + "Be short and friendly. Say: 1) what broke in one sentence, 2) which mod is most likely to blame and why, 3) up to 4 concrete things to try. No code."));
                msgs.add(msg("user", "PrideCrash's own analysis:\n" + r.toText() + "\n\nStart of the crash report:\n" + raw));
                body.add("messages", msgs);
                HttpURLConnection c = (HttpURLConnection) new URL(Config.aiUrl).openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(5000);
                c.setReadTimeout(180000);
                c.setRequestProperty("Content-Type", "application/json");
                if (!Config.aiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + Config.aiKey);
                try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                if (c.getResponseCode() / 100 != 2) { aiState = "The AI said HTTP " + c.getResponseCode() + " — check the address/model in config/pridecrash.cfg."; return; }
                try (InputStreamReader in = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
                    JsonObject res = new JsonParser().parse(in).getAsJsonObject();
                    String text = res.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
                    aiText = text.replaceAll("(?s)<think>.*?</think>", "").trim();
                    aiState = "";
                }
            } catch (Exception e) {
                aiState = "Couldn't reach the AI: " + e.getMessage();
            }
        }, "PrideCrash AI");
        t.setDaemon(true);
        t.start();
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    // ------------------------------------------------------------------ little helpers
    private int para(String s, int x, int y, int w, int color) {
        for (String l : fontRenderer.listFormattedStringToWidth(s, w)) { fontRenderer.drawStringWithShadow(l, x, y, color); y += 10; }
        return y;
    }

    private int kv(String k, String v, int x, int y, int w) {
        fontRenderer.drawStringWithShadow("§7" + k, x, y, 0xFFFFFF);
        return Math.max(y + 11, para(v == null || v.isEmpty() ? "§8—" : v, x + 90, y, w - 90, 0xFFFFFF) + 1);
    }

    private int mini(int x, int y, String label, int mx, int my, Runnable r) {
        int w = fontRenderer.getStringWidth(label) + 10;
        PrideFrame.button(x, y, w, 14, label, PrideFrame.BUTTON, mx, my);
        hit(x, y, w, 14, r);
        return x + w + 3;
    }

    private void percentBar(int x, int y, int w, int pct) {
        Gui.drawRect(x, y, x + w, y + 4, 0x40FFFFFF);
        int fill = w * Math.max(0, Math.min(100, pct)) / 100;
        int seg = Math.max(1, w / PrideFrame.RAINBOW.length);
        for (int i = 0; i * seg < fill; i++) Gui.drawRect(x + i * seg, y, x + Math.min(fill, (i + 1) * seg), y + 4, PrideFrame.RAINBOW[i % PrideFrame.RAINBOW.length]);
    }

    private static int confColor(int pct) { return pct >= 60 ? RED : pct >= 30 ? GOLD : GREEN; }

    private int clipTop = Integer.MIN_VALUE, clipBottom = Integer.MAX_VALUE;

    /** a clickable area (scrolled-away parts of the content can't be clicked) */
    private void hit(int x, int y, int w, int h, Runnable r) {
        int top = Math.max(y, clipTop), bottom = Math.min(y + h, clipBottom);
        if (bottom <= top) return;
        boxes.add(new int[]{x, top, w, bottom - top}); clicks.add(r);
    }

    private static boolean in(int mx, int my, int x, int y, int w, int h) { return mx >= x && my >= y && mx < x + w && my < y + h; }

    private void toast(String s) { toast = s; toastUntil = System.currentTimeMillis() + 2500; }

    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    private void web(String url) {
        try { java.awt.Desktop.getDesktop().browse(new java.net.URI(url)); toast("Opened in your browser"); }
        catch (Throwable t) { xdg(url); }
    }

    private void open(File fl) { xdg(fl.getAbsolutePath()); }

    private void xdg(String what) {
        try {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            new ProcessBuilder(os.contains("win") ? new String[]{"explorer", what} : os.contains("mac") ? new String[]{"open", what} : new String[]{"xdg-open", what}).start();
            toast("Opening…");
        } catch (Exception e) {
            setClipboardString(what);
            toast("Couldn't open it — copied the path instead");
        }
    }

    // ------------------------------------------------------------------ input
    @Override
    protected void mouseClicked(int mx, int my, int button) {
        if (button != 0) return;
        for (int i = boxes.size() - 1; i >= 0; i--) {
            int[] b = boxes.get(i);
            if (in(mx, my, b[0], b[1], b[2], b[3])) { clicks.get(i).run(); return; }
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0 || f == null) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        if (mx < f.cx + Math.min(170, f.cw / 3)) listScroll += d > 0 ? -1 : 1;
        else scroll += d > 0 ? -30 : 30;
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE) { mc.displayGuiScreen(back); return; }
        if (key == Keyboard.KEY_DOWN) select(Math.min(files.size() - 1, selected + 1));
        else if (key == Keyboard.KEY_UP) select(Math.max(0, selected - 1));
        else if (key == Keyboard.KEY_TAB) { tab = (tab + 1) % TABS.length; scroll = 0; }
        else if (key >= Keyboard.KEY_1 && key < Keyboard.KEY_1 + TABS.length) { tab = key - Keyboard.KEY_1; scroll = 0; }
        else if (GuiScreen.isKeyComboCtrlC(key) && result != null) { setClipboardString(result.toText()); toast("Copied"); }
    }
}
