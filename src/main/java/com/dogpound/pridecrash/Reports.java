package com.dogpound.pridecrash;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Files on disk: crash-reports/*.txt (Minecraft's), and ours in crash-reports/pridecrash/:
 *   <report name>.json   — the analysis, so the report screen opens instantly
 *   pending.json         — the newest crash nobody has looked at yet (shown on the next main menu)
 */
public final class Reports {
    private Reports() {}

    public static File gameDir() { return new File(".").getAbsoluteFile(); }
    public static File crashDir() { return new File(gameDir(), "crash-reports"); }
    public static File ourDir() { File d = new File(crashDir(), "pridecrash"); d.mkdirs(); return d; }
    public static File pending() { return new File(ourDir(), "pending.json"); }

    private static ModIndex index;

    /** every jar the game could have loaded: mods/, mods/1.12.2/, and anything Forge says it loaded */
    public static synchronized ModIndex index() {
        if (index != null) return index;
        List<File> files = new ArrayList<>();
        for (String d : new String[]{"mods", "mods/1.12.2", "mods/1.12"}) {
            File[] fs = new File(gameDir(), d).listFiles();
            if (fs != null) files.addAll(Arrays.asList(fs));
        }
        try {
            for (net.minecraftforge.fml.common.ModContainer m : net.minecraftforge.fml.common.Loader.instance().getActiveModList())
                if (m.getSource() != null && m.getSource().isFile() && !files.contains(m.getSource())) files.add(m.getSource());
        } catch (Throwable ignored) {}                              // crashing before Forge is ready
        index = ModIndex.build(files);
        return index;
    }

    /** called from the crash hook: analyse, remember for the next start, return the text to append */
    public static String onCrash(String report) {
        if (!Config.enabled) return "";
        Analyzer.Result r = Analyzer.analyze(report, Config.indexJars ? index() : null);
        String name = "crash-" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
        r.file = name;
        try {
            byte[] json = r.toJson().getBytes(StandardCharsets.UTF_8);
            Files.write(new File(ourDir(), name + ".json").toPath(), json);
            Files.write(pending().toPath(), json);
        } catch (Exception ignored) {}
        return r.toText();
    }

    /** the analysis for a crash file: ours if saved, else worked out now (old crashes from before PrideCrash) */
    public static Analyzer.Result load(File txt) {
        String base = txt.getName().replaceAll("-(client|server)\\.txt$", "").replaceAll("\\.txt$", "");
        File json = new File(ourDir(), base + ".json");
        try {
            String text = new String(Files.readAllBytes(txt.toPath()), StandardCharsets.UTF_8);
            Analyzer.Result r;
            if (text.contains("-- PrideCrash says --") && json.isFile())
                r = fromJson(json);
            else {
                r = Analyzer.analyze(text, Config.indexJars ? index() : null);
                Files.write(json.toPath(), r.toJson().getBytes(StandardCharsets.UTF_8));
            }
            r.file = txt.getName();
            return r;
        } catch (Exception e) {
            Analyzer.Result r = new Analyzer.Result();
            r.title = "Couldn't read " + txt.getName();
            r.plain = e.toString();
            r.file = txt.getName();
            return r;
        }
    }

    public static Analyzer.Result fromJson(File f) throws Exception {
        return new com.google.gson.Gson().fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), Analyzer.Result.class);
    }

    /** crash files, newest first */
    public static List<File> crashFiles() {
        File[] fs = crashDir().listFiles((d, n) -> n.startsWith("crash-") && n.endsWith(".txt"));
        List<File> out = fs == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(fs));
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /** the Minecraft crash file that goes with a saved analysis */
    public static File txtFor(Analyzer.Result r) {
        if (r.file == null || r.file.isEmpty()) return null;
        if (new File(r.file).isAbsolute()) return new File(r.file);                // hs_err file of a silent death
        if (r.file.endsWith(".txt")) return new File(crashDir(), r.file);
        for (String side : new String[]{"-client.txt", "-server.txt"}) {
            File f = new File(crashDir(), r.file + side);
            if (f.isFile()) return f;
        }
        // written a second or two apart from ours: take the closest crash file
        File best = null;
        for (File f : crashFiles()) if (f.getName().compareTo(r.file) <= 0 || best == null) { best = f; if (f.getName().compareTo(r.file) <= 0) break; }
        return best;
    }
}
