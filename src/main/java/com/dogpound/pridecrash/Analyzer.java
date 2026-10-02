package com.dogpound.pridecrash;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The brain: reads a crash report's TEXT (so it works on a crash happening right now and on any old crash file)
 * and works out which mod is to blame, how sure it is, what went wrong in plain English and what to try.
 *
 * Blame comes from the stack: frames from a mod's own code count most, the ones nearest the throw count more,
 * and the deepest "Caused by" (the real cause) counts more than the wrapper. Minecraft/Forge/Java frames are
 * skipped, but a Minecraft frame whose source file is a mixin ("MixinWorld.java") means some mod patched that
 * code — a small hint toward that mod. "Entity Type: modid:thing" style details in the report add hints too.
 */
public final class Analyzer {

    public static final class Suspect {
        public String name, file, modid;
        public int percent;
        public double score;
        public final List<String> why = new ArrayList<>();
        transient final Set<String> whySeen = new HashSet<>();
    }

    public static final class Frame {
        public String cls, method, file, mod;                 // mod = who owns this line ("" = Minecraft/Forge/Java)
        public int line;
        public boolean mixin;                                 // Minecraft code patched by a mixin
        public int block;                                     // 0 = top exception, 1.. = Caused by
    }

    public static final class Result {
        public String time = "", description = "", exception = "", message = "", rootException = "", rootMessage = "";
        public String title = "", plain = "", side = "";
        public final List<String> tips = new ArrayList<>();
        public final List<Suspect> suspects = new ArrayList<>();
        public final List<Frame> frames = new ArrayList<>();
        public final List<String> details = new ArrayList<>();          // "Entity Type: …" lines worth showing
        public String file = "";
        public final Map<String, List<String>> mixinsOn = new LinkedHashMap<>();   // target class → mixin classes (MixinBooter's list)

        public Suspect top() { return suspects.isEmpty() ? null : suspects.get(0); }

        /** the section appended to the crash report itself */
        public String toText() {
            StringBuilder b = new StringBuilder();
            b.append("\n-- PrideCrash says --\n");
            b.append("What happened: ").append(title).append('\n');
            b.append("In plain words: ").append(plain).append('\n');
            if (suspects.isEmpty()) b.append("Guilty mod: couldn't tell — only Minecraft/Forge code was on the stack.\n");
            else {
                b.append("Most likely guilty mod:\n");
                for (int i = 0; i < Math.min(5, suspects.size()); i++) {
                    Suspect s = suspects.get(i);
                    b.append(String.format("  %d. %s  %d%%%s%n", i + 1, s.name, s.percent, s.file == null ? "" : "  [" + s.file + "]"));
                    for (String w : s.why) b.append("       - ").append(w).append('\n');
                }
            }
            if (!tips.isEmpty()) {
                b.append("What to try:\n");
                for (String t : tips) b.append("  * ").append(t).append('\n');
            }
            return b.toString();
        }

        public String toJson() { return GSON.toJson(this); }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ------------------------------------------------------------------ parsing
    private static final Pattern FRAME = Pattern.compile("^\\s*at (?:[^\\s/()]*/)*([\\w$.]+)\\.([^(\\s/]+)\\(([^)]*)\\)");
    private static final Pattern EXC = Pattern.compile("^((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable|Failure|Crash\\w*)?)(?::\\s?(.*))?$");
    private static final Pattern CAUSED = Pattern.compile("^\\s*Caused by: ([\\w$.]+)(?::\\s?(.*))?$");
    private static final Pattern REG_ID = Pattern.compile("^\\s*(Entity Type|Entity's Type|Block type|Block|Tile entity type|Actual block type|Name|Item Type|Registry Name|Tile entity class):\\s*(.*)$");
    private static final Pattern MODID_IN = Pattern.compile("\\b([a-z][a-z0-9_]{1,63}):[a-z0-9_/.]+");
    private static final Pattern MIXIN_CFG = Pattern.compile("(?:mixins\\.([a-z0-9_]+)(?:\\.[a-z0-9_]+)*\\.json|([a-z0-9_]+)\\.mixins?(?:\\.[a-z0-9_]+)*\\.json)");
    private static final Pattern MIXIN_HANDLER = Pattern.compile("^(?:handler|redirect|modify|localvar|constant|wrapOperation|wrapWithCondition|mixinextras\\$\\w+)\\$[a-z0-9]+\\$([^$]+)\\$");

    private static final String[] FRAMEWORK = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "net.minecraft.", "net.minecraftforge.", "org.spongepowered.",
            "com.google.", "org.apache.", "io.netty.", "com.mojang.", "org.lwjgl.", "paulscode.", "scala.", "kotlin.",
            "com.cleanroommc.", "top.outlands.", "zone.rong.mixinbooter.", "org.objectweb.", "it.unimi.", "gnu.trove.",
            "LZMA.", "org.prismlauncher.", "org.multimc.", "cpw.mods.", "com.llamalad7.mixinextras.", "optifine.", "net.optifine.", "com.dogpound.pridecrash."
    };
    private static final Set<String> TLD = new HashSet<>(Arrays.asList(
            "com", "net", "org", "io", "me", "ovh", "team", "dev", "top", "mod", "mods", "zone", "info", "xyz", "gg",
            "cc", "co", "tk", "de", "fr", "ru", "cn", "uk", "nl", "pl", "se", "jp", "au", "ca", "us", "eu", "tv", "moe", "site"));
    private static final Set<String> WORDS = new HashSet<>(Arrays.asList(
            "mixin", "mixins", "core", "common", "api", "client", "server", "asm", "util", "utils", "impl", "internal", "lib", "forge", "mod", "coremod"));
    private static final Set<String> AUTHORS = new HashSet<>(Arrays.asList(
            "vazkii", "mcjty", "blusunrize", "cofh", "codechicken", "mezz", "jeresources", "slimeknights", "teamroots", "lumien", "shadows"));
    private static final Set<String> NOT_MODS = new HashSet<>(Arrays.asList("minecraft", "forge", "fml", "mcp", "http", "https", "file", "jar", "java"));

    public static Result analyze(String report, ModIndex idx) {
        Result r = new Result();
        String[] lines = report.split("\\r?\\n");
        int block = -1;
        boolean inStack = false;
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.startsWith("Time: ")) r.time = ln.substring(6).trim();
            else if (ln.startsWith("Description: ") && r.description.isEmpty()) {
                r.description = ln.substring(13).trim();
                // the exception is the first non-empty line after the description
                for (int j = i + 1; j < lines.length; j++) {
                    if (lines[j].trim().isEmpty()) continue;
                    Matcher m = EXC.matcher(lines[j].trim());
                    if (m.matches()) {
                        r.exception = m.group(1);
                        r.message = joinMessage(lines, j, m.group(2));
                        block = 0; inStack = true; i = j;
                    }
                    break;
                }
                continue;
            }
            if (ln.startsWith("A detailed walkthrough") || ln.startsWith("-- ")) inStack = false;
            Matcher c = CAUSED.matcher(ln);
            if (c.find() && block >= 0 && (inStack || ln.startsWith("Caused by"))) {
                block++; inStack = true;
                r.rootException = c.group(1);
                r.rootMessage = c.group(2) == null ? "" : c.group(2).trim();
                continue;
            }
            if (inStack) {
                Matcher f = FRAME.matcher(ln);
                if (f.find()) r.frames.add(frame(f, block, idx));
            }
            if (ln.contains("Mixins in Stacktrace:")) {
                String target = null;
                for (i = i + 1; i < lines.length; i++) {
                    String t = lines[i];
                    if (t.trim().isEmpty()) break;
                    if (t.trim().endsWith(":") && !t.startsWith("        ")) { target = t.trim().replace(":", ""); continue; }
                    if (target != null) r.mixinsOn.computeIfAbsent(target, k -> new ArrayList<>()).add(t.trim().split("\\s+")[0]);
                }
                continue;
            }
            Matcher d = REG_ID.matcher(ln);
            if (d.find() && r.details.size() < 12) r.details.add(ln.trim());
        }
        if (r.exception.isEmpty()) {                            // not a Minecraft crash report — maybe a bare trace
            for (int j = 0; j < lines.length; j++) {
                Matcher m = EXC.matcher(lines[j].trim());
                if (m.matches() && j + 1 < lines.length && FRAME.matcher(lines[j + 1]).find()) {
                    r.exception = m.group(1); r.message = m.group(2) == null ? "" : m.group(2).trim();
                    for (int k = j + 1; k < lines.length; k++) {
                        Matcher f = FRAME.matcher(lines[k]);
                        if (f.find()) r.frames.add(frame(f, 0, idx));
                    }
                    break;
                }
            }
        }
        if (r.rootException.isEmpty()) { r.rootException = r.exception; r.rootMessage = r.message; }
        r.message = tidyMsg(r.message); r.rootMessage = tidyMsg(r.rootMessage);
        r.side = report.contains("-server.txt") || r.frames.stream().anyMatch(f -> f.cls.contains("MinecraftServer")) ? "server" : "client";
        blame(r, idx, block < 0 ? 0 : block);
        explain(r, idx);
        return r;
    }

    /** drop Java's "(… are in unnamed module of loader …)" noise */
    static String tidyMsg(String m) { return m.replaceAll("\\s*\\([^()]*(?:unnamed module|module \\S+ of loader)[^()]*\\)", "").replaceAll("\\bclass (\\S)", "$1").trim(); }

    /** messages can run onto following lines (before the first "at") */
    private static String joinMessage(String[] lines, int j, String first) {
        StringBuilder b = new StringBuilder(first == null ? "" : first.trim());
        for (int k = j + 1; k < lines.length && k < j + 6; k++) {
            String t = lines[k].trim();
            if (t.isEmpty() || t.startsWith("at ") || t.startsWith("Caused by")) break;
            b.append(' ').append(t);
        }
        return b.toString();
    }

    private static Frame frame(Matcher f, int block, ModIndex idx) {
        Frame fr = new Frame();
        fr.cls = f.group(1); fr.method = f.group(2); fr.block = block;
        String src = f.group(3);
        int colon = src.lastIndexOf(':');
        fr.file = colon < 0 ? src : src.substring(0, colon);
        try { fr.line = colon < 0 ? -1 : Integer.parseInt(src.substring(colon + 1)); } catch (NumberFormatException e) { fr.line = -1; }
        if (!framework(fr.cls)) fr.mod = modOf(fr.cls, idx);
        else {
            fr.mod = "";
            String simple = fr.cls.substring(fr.cls.lastIndexOf('.') + 1);
            int dollar = simple.indexOf('$');
            if (dollar > 0) simple = simple.substring(0, dollar);
            fr.mixin = fr.file.endsWith(".java") && !fr.file.equals(simple + ".java") && !fr.file.equals("SourceFile")
                    || MIXIN_HANDLER.matcher(fr.method).find();
        }
        return fr;
    }

    static boolean framework(String cls) {
        for (String p : FRAMEWORK) if (cls.startsWith(p)) return true;
        return !cls.contains(".");
    }

    /** mod name for a class: the jar that holds it, else a guess from its package */
    static String modOf(String cls, ModIndex idx) {
        if (idx != null) {
            ModIndex.Jar j = idx.ofClass(cls);
            if (j != null) return j.name;
        }
        return guessFromPackage(cls);
    }

    /** "ovh.corail.tombstone.x.Y" → "tombstone", "team.cqr.cqrepoured.a.B" → "cqrepoured", "vazkii.botania.x.Y" → "botania" */
    static String guessFromPackage(String cls) {
        String[] p = cls.split("\\.");
        int n = p.length - 1, i = 0;                               // p[n] is the class itself
        if (n <= 1) return n == 1 ? p[0] : cls;
        if (TLD.contains(p[0].toLowerCase(Locale.ROOT)) || p[0].length() <= 3) {
            i = 1;
            if (p[i].equalsIgnoreCase("github") && n - i >= 3) i++;
            if (n - i >= 2) i++;                                      // tld.author.mod…
        } else if (AUTHORS.contains(p[0].toLowerCase(Locale.ROOT)) && n >= 2) i = 1;
        i = Math.min(i, n - 1);
        while (i > 0 && WORDS.contains(p[i].toLowerCase(Locale.ROOT))) i--;   // "org.valkyrienskies.mixin" → valkyrienskies
        return p[i];
    }

    // ------------------------------------------------------------------ blame
    private static void blame(Result r, ModIndex idx, int lastBlock) {
        Map<String, Suspect> by = new LinkedHashMap<>();
        int[] seenInBlock = new int[lastBlock + 2];
        boolean first = true;
        for (Frame f : r.frames) {
            int b = Math.min(f.block, seenInBlock.length - 1);
            int depth = seenInBlock[b]++;
            double near = 1.0 / (1.0 + depth * 0.25);              // nearer the throw = more weight
            double blockW = f.block == lastBlock ? 1.5 : 1.0;      // the real cause
            if (!f.mod.isEmpty()) {
                double w = near * blockW * (first ? 4.0 : 1.0);
                String why = first ? "its code is where the error was thrown (" + shortName(f) + ")" : "its code was running when it broke (" + shortName(f) + ")";
                add(by, f.mod, fileOf(f.cls, idx), w, why);
                first = false;
            } else if (f.mixin) {
                // a handler$/redirect$ method IS mod code merged into Minecraft; a mixin source file alone only means "patched"
                boolean handler = MIXIN_HANDLER.matcher(f.method).find();
                List<String[]> owners = mixinOwners(r, f, idx);             // {name, file}
                if (owners.isEmpty()) continue;
                double w = (handler ? near * blockW * (first ? 4.0 : 1.0) : 0.25 * near * blockW) / owners.size();
                String what = f.cls.substring(f.cls.lastIndexOf('.') + 1) + "." + f.method.replaceAll("^\\w+\\$[a-z0-9]+\\$[^$]+\\$", "");
                for (String[] o : owners)
                    add(by, o[0], o[1], w, handler ? (first ? "its patch inside Minecraft is where the error was thrown (" : "its patch inside Minecraft was running (") + what + ", " + f.file + ")"
                            : "it patches " + what + " (" + f.file + ")" + (owners.size() > 1 ? " — shared with " + (owners.size() - 1) + " other mod(s)" : ""));
                if (handler) first = false;
            }
        }
        // mod classes named in the message ("cannot be cast to club.sk1er.patcher.ducks.VisGraphExt")
        Matcher cm = Pattern.compile("\\b((?:[a-z][\\w]*\\.){2,}[A-Z][\\w$]*)").matcher(r.message + " " + r.rootMessage);
        Set<String> named = new HashSet<>();
        while (cm.find()) {
            String c = cm.group(1);
            if (framework(c) || !named.add(c)) continue;
            add(by, modOf(c, idx), fileOf(c, idx), 3.0, "the error message names its class (" + c.substring(c.lastIndexOf('.') + 1) + ")");
        }
        // mixin config names in the message ("mixins.cqrepoured.json")
        Matcher m = MIXIN_CFG.matcher(r.message + " " + r.rootMessage);
        while (m.find()) {
            String id = m.group(1) != null ? m.group(1) : m.group(2);
            ModIndex.Jar j = idx == null ? null : idx.ofModId(id);
            add(by, j == null ? id : j.name, j == null ? null : j.file, 3.0, "the error names its mixin file (" + m.group() + ")");
        }
        // registry names in the report details ("Entity Type: cqrepoured:boss")
        for (String d : r.details) {
            Matcher x = MODID_IN.matcher(d);
            while (x.find()) {
                String id = x.group(1);
                if (NOT_MODS.contains(id)) continue;
                ModIndex.Jar j = idx == null ? null : idx.ofModId(id);
                add(by, j == null ? id : j.name, j == null ? null : j.file, 0.5, "the thing being updated belongs to it (" + d + ")");
            }
        }
        // merge guesses that are the same mod under two names ("cqrepoured" and "Chocolate Quest Repoured")
        double total = 0;
        for (Suspect s : by.values()) total += s.score;
        List<Suspect> list = new ArrayList<>(by.values());
        list.sort((a, b) -> Double.compare(b.score, a.score));
        for (Suspect s : list) s.percent = total <= 0 ? 0 : (int) Math.round(100 * s.score / total);
        list.removeIf(s -> s.percent < 3 && list.indexOf(s) > 0);
        r.suspects.addAll(list);
    }

    /** who owns the mixin behind a patched Minecraft frame: the modid in the handler name, else MixinBooter's
     *  "Mixins in Stacktrace" list (mixins on that class whose name matches the source file), else a unique jar match */
    private static List<String[]> mixinOwners(Result r, Frame f, ModIndex idx) {
        List<String[]> out = new ArrayList<>();
        Matcher h = MIXIN_HANDLER.matcher(f.method);
        if (h.find() && !h.group(1).equals("unknown_owner")) {
            ModIndex.Jar j = idx == null ? null : idx.ofModId(h.group(1));
            out.add(new String[]{j == null ? h.group(1) : j.name, j == null ? null : j.file});
            return out;
        }
        // "handler$abc$unknown_owner$patcher$setLimitScan" / "patcher$setLimitScan": mods prefix merged methods with their id
        String rest = f.method.replaceAll("^\\w+\\$[a-z0-9]+\\$[^$]+\\$", "");
        Matcher pre = Pattern.compile("^([a-z][a-z0-9_]+)\\$").matcher(rest);
        if (idx != null && pre.find() && idx.ofModId(pre.group(1)) != null) {
            ModIndex.Jar j = idx.ofModId(pre.group(1));
            out.add(new String[]{j.name, j.file});
            return out;
        }
        String simple = f.file.replace(".java", "");
        Set<String> seen = new HashSet<>();
        for (String mx : r.mixinsOn.getOrDefault(f.cls, Collections.emptyList()))
            if (mx.substring(mx.lastIndexOf('.') + 1).equals(simple)) {
                String name = modOf(mx, idx);
                if (seen.add(name.toLowerCase(Locale.ROOT))) out.add(new String[]{name, fileOf(mx, idx)});
            }
        if (out.isEmpty() && idx != null) {
            List<ModIndex.Jar> jars = idx.mixinsNamed(simple);
            if (jars.size() == 1) out.add(new String[]{jars.get(0).name, jars.get(0).file});
        }
        return out;
    }

    private static String fileOf(String cls, ModIndex idx) {
        if (idx == null) return null;
        ModIndex.Jar j = idx.ofClass(cls);
        return j == null ? null : j.file;
    }

    private static String shortName(Frame f) {
        String c = f.cls.substring(f.cls.lastIndexOf('.') + 1);
        return c + "." + f.method + (f.line > 0 ? " line " + f.line : "");
    }

    private static void add(Map<String, Suspect> by, String name, String file, double w, String why) {
        String key = name.toLowerCase(Locale.ROOT);
        Suspect s = by.computeIfAbsent(key, k -> { Suspect n = new Suspect(); n.name = name; return n; });
        if (s.file == null) s.file = file;
        s.score += w;
        if (s.why.size() < 3 && s.whySeen.add(why)) s.why.add(why);
    }

    // ------------------------------------------------------------------ plain English
    private static void explain(Result r, ModIndex idx) {
        String ex = r.rootException.isEmpty() ? r.exception : r.rootException;
        String msg = (r.rootMessage.isEmpty() ? r.message : r.rootMessage);
        String simple = ex.substring(ex.lastIndexOf('.') + 1);
        Suspect top = r.top();
        String who = top == null ? "a mod" : top.name;
        String what = r.description.isEmpty() ? "" : " while " + describe(r.description);

        switch (simple) {
            case "IllegalAccessError":
                r.title = who + " tried to use a locked part of Minecraft" + what;
                r.plain = who + " reached into a private part of Minecraft (" + member(msg) + "). Mods normally get that door opened by an "
                        + "\"access transformer\" when the game starts; this time it stayed locked, so Java stopped the game.";
                ModIndex.Jar tj = idx == null || top == null ? null : idx.ofFile(top.file);
                if (tj != null && tj.atNotLoaded)
                    r.tips.add("FOUND IT: " + tj.file + " has an access transformer file but its manifest has no \"FMLAT:\" line, so it never loads. "
                            + "Add \"FMLAT: " + tj.atFile + "\" to its META-INF/MANIFEST.MF (or use the previous version of " + who + ").");
                r.tips.add("Update " + who + ", or try the version made for your loader (Cleanroom/MixinBooter setups sometimes skip old access transformers).");
                r.tips.add("If it only happens with one other mod on the stack, the two are fighting — check for an update or a patch mod.");
                break;
            case "NullPointerException":
                r.title = who + " used something that wasn't there" + what;
                r.plain = who + " expected a thing (an item, block, entity or setting) to exist and it was empty (null)."
                        + (msg.isEmpty() ? "" : " Java says: " + msg);
                r.tips.add("Update " + who + ". If it's in a world, note what you were doing — this is usually one specific block, mob or item.");
                break;
            case "ClassCastException":
                r.title = who + " mixed up two kinds of things" + what;
                r.plain = who + " treated one thing as a different kind of thing (" + msg + "). Often two mods change the same object in different ways.";
                r.tips.add("Look for another mod that replaces the same thing (entity, tile, GUI) — the two probably clash.");
                break;
            case "NoClassDefFoundError":
            case "ClassNotFoundException":
                r.title = who + " needs something that isn't installed" + what;
                r.plain = who + " asked for " + msg.replace('/', '.') + ", which isn't in the game. A library/dependency mod is missing, or it's the wrong version.";
                r.tips.add("Check " + who + "'s download page for required mods and install the right versions.");
                r.tips.add("If the missing piece is from a client-only mod, " + who + " may have run on the server by mistake.");
                break;
            case "NoSuchMethodError":
            case "NoSuchFieldError":
            case "AbstractMethodError":
            case "IncompatibleClassChangeError":
                r.title = who + " was built for a different version of something" + what;
                r.plain = who + " looked for " + member(msg) + " and it isn't there any more. One of its dependencies is newer or older than it expects.";
                r.tips.add("Update " + who + " and the library it talks to so their versions match.");
                break;
            case "OutOfMemoryError":
                r.title = "The game ran out of memory" + what;
                r.plain = "Java used up all the memory it was given. " + (top == null ? "" : who + " was running at the time, but it may not be the one using it all.");
                r.tips.add("Give Minecraft more memory (Prism: Edit instance → Settings → Java → Maximum memory).");
                r.tips.add("If it keeps happening at the same spot, a mod is leaking memory — try with fewer mods.");
                break;
            case "StackOverflowError":
                r.title = who + " got stuck in a loop" + what;
                r.plain = who + " kept calling itself over and over until Java ran out of room. Often two mods keep triggering each other.";
                r.tips.add("Look at the other mods on the stack — two mods passing the same event back and forth is common.");
                break;
            case "ConcurrentModificationException":
                r.title = who + " changed a list while it was being read" + what;
                r.plain = who + " added or removed something while the game was still going through that list — usually a timing (threading) bug.";
                r.tips.add("Update " + who + "; if it's rare, it may not happen again.");
                break;
            case "ArrayIndexOutOfBoundsException":
            case "IndexOutOfBoundsException":
                r.title = who + " went past the end of a list" + what;
                r.plain = who + " asked for item number " + msg.replaceAll("\\D+", " ").trim() + " in a list that isn't that long. Often a changed config or ID mismatch.";
                r.tips.add("If you changed IDs or configs for " + who + " recently, change them back.");
                break;
            case "MixinApplyError":
            case "InvalidInjectionException":
            case "InvalidMixinException":
            case "MixinTransformerError":
                r.title = who + "'s patch into Minecraft didn't fit" + what;
                r.plain = who + " tried to patch Minecraft code that another mod already changed (or that is a different version). The patch failed while loading.";
                r.tips.add("Remove or update " + who + ", or the mod that patches the same class (see \"Mixins in Stacktrace\" above).");
                break;
            case "ReportedException":
                r.title = who + " broke" + what;
                r.plain = "Minecraft caught an error from " + who + " and stopped: " + (msg.isEmpty() ? r.description : msg);
                r.tips.add("Update " + who + ".");
                break;
            default:
                r.title = (top == null ? "Minecraft" : who) + " crashed" + what;
                r.plain = simple + (msg.isEmpty() ? "" : ": " + msg);
                r.tips.add("Update " + who + ", then try again.");
        }
        if (r.description.startsWith("Ticking entity") || r.description.startsWith("Ticking block entity"))
            r.tips.add("If the world crashes every time you load it, the broken " + (r.description.contains("block") ? "block" : "mob")
                    + " can be removed (NBTExplorer, or a /kill of that mob type) — its position is in the details above.");
        if (top != null && top.percent < 40 && r.suspects.size() > 1)
            r.tips.add("Blame is split between " + r.suspects.get(0).name + " and " + r.suspects.get(1).name + " — they may be clashing.");
    }

    /** "Ticking entity" → "updating a mob" */
    private static String describe(String d) {
        String s = d.toLowerCase(Locale.ROOT);
        if (s.startsWith("ticking entity")) return "updating a mob/entity";
        if (s.startsWith("ticking block entity")) return "updating a machine/block entity";
        if (s.startsWith("exception in server tick") || s.startsWith("exception ticking world")) return "running the world";
        if (s.startsWith("unexpected error") || s.startsWith("error executing task")) return "playing";
        if (s.contains("rendering")) return "drawing " + s.replace("rendering", "").trim();
        if (s.contains("initializing game") || s.contains("loading")) return "starting up";
        return s;
    }

    /** "…tried to access private field net.minecraft.entity.EntityLivingBase.field_70755_b (…)" → "EntityLivingBase.field_70755_b" */
    private static String member(String msg) {
        Matcher m = Pattern.compile("([\\w$]+\\.[\\w$]+(?:\\([^)]*\\)[\\w;/$\\[]*)?)(?:\\s|$)").matcher(msg.replaceAll("\\(.*? loader .*?\\)", ""));
        String best = null;
        while (m.find()) if (m.group(1).contains("field_") || m.group(1).contains("func_") || best == null) best = m.group(1);
        if (best == null) return "a part of Minecraft";
        String[] p = best.split("\\.");
        return p.length >= 2 ? p[p.length - 2] + "." + p[p.length - 1] : best;
    }

    // ------------------------------------------------------------------ self-check: java … Analyzer <crash files>
    private static ModIndex MAIN_IDX;
    public static void main(String[] args) throws Exception {
        String dir = System.getenv("PRIDECRASH_MODS");
        if (dir != null) { File[] fs = new File(dir).listFiles(); MAIN_IDX = ModIndex.build(fs == null ? Collections.emptyList() : Arrays.asList(fs)); System.out.println("indexed " + MAIN_IDX.jars + " jars"); }
        for (String a : args) {
            Result r = analyze(new String(Files.readAllBytes(new File(a).toPath()), StandardCharsets.UTF_8), MAIN_IDX);
            System.out.println("=== " + a + r.toText());
        }
        String tomb = "Description: Ticking entity\n\njava.lang.IllegalAccessError: class ovh.corail.tombstone.event.EventHandler tried to access private field net.minecraft.entity.EntityLivingBase.field_70755_b (x)\n"
                + "    at ovh.corail.tombstone.event.EventHandler.onCheckAttackTarget(EventHandler.java:447)\n"
                + "    at net.minecraftforge.fml.common.eventhandler.EventBus.post(EventBus.java:204)\n"
                + "    at team.cqr.cqrepoured.entity.bases.AbstractEntityCQR.setAttackTarget(AbstractEntityCQR.java:1620)\n"
                + "    at net.minecraft.world.World.updateEntity(MixinWorld.java:2077)\n";
        Result t = analyze(tomb, null);
        check("tombstone".equals(t.top().name), "top suspect tombstone, got " + t.top().name);
        check(t.suspects.get(1).name.equals("cqrepoured"), "second cqrepoured");
        check(t.top().percent > 60, "tombstone confident, got " + t.top().percent);
        check(t.plain.contains("EntityLivingBase.field_70755_b"), "names the locked field: " + t.plain);
        check(t.frames.get(3).mixin, "MixinWorld.java frame is a mixin");
        check(guessFromPackage("mod.acgaming.universaltweaks.X").equals("universaltweaks"), "UT package");
        check(guessFromPackage("com.dogpound.zoomies.mixin.M").equals("zoomies"), "zoomies package");
        Result j = analyze("Description: x\n\njava.lang.RuntimeException: y\n\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)\n\tat app//ovh.corail.tombstone.A.b(A.java:1)\n", null);
        check(j.frames.get(0).cls.equals("jdk.internal.reflect.DirectMethodHandleAccessor") && !j.frames.get(0).mixin, "module prefix: " + j.frames.get(0).cls);
        check("tombstone".equals(j.top().name), "app// prefix");
        check(guessFromPackage("appeng.core.Api").equals("appeng"), "appeng package");
        check(guessFromPackage("org.valkyrienskies.mixin.world.gen.MixinChunkProviderServer").equals("valkyrienskies"), "VS package");
        check(guessFromPackage("ovh.corail.tombstone.event.EventHandler").equals("tombstone"), "tombstone package");
        System.out.println("Analyzer self-check OK");
    }

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
