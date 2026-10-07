package com.dogpound.pridecrash;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deaths that leave no crash report: Java itself crashing (an hs_err_pid*.log — usually the graphics driver) or
 * the game being killed from outside (Linux's out-of-memory killer, a force-close). A "running" flag is written at
 * start and removed by a shutdown hook on any normal exit; if the flag is still there next time and no crash
 * report is newer than it, the game died silently — and this works out how.
 */
public final class Silent {
    private Silent() {}

    static File flag(File ourDir) { return new File(ourDir, "running.flag"); }

    public static volatile boolean checked;

    /**
     * Earliest possible check, from the coremod (2026-10-03: a launch that crashed during mod construction never reached
     * the old check in init, and its crash report then overwrote the waiting one). Every silent death is saved to the
     * history as its own file; it only becomes "pending" (shown on the menu) if nothing newer is waiting.
     */
    public static void early(File gameDir, File crashDir, File ourDir) {
        if (checked) return;
        checked = true;
        try {
            File flag = flag(ourDir);
            long since = flag.isFile() ? flag.lastModified() : 0;
            Analyzer.Result r = checkAndMark(gameDir, crashDir, ourDir);
            if (r == null) return;
            byte[] json = r.toJson().getBytes(StandardCharsets.UTF_8);
            String name = "crash-" + new java.text.SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new java.util.Date(since)) + "-silent";
            Files.write(new File(ourDir, name + ".json").toPath(), json);
            File pending = new File(ourDir, "pending.json");
            if (!pending.isFile() || pending.lastModified() < since) Files.write(pending.toPath(), json);
            System.out.println("[PrideCrash] the last run closed without a crash report: " + r.title);
        } catch (Throwable t) {
            System.out.println("[PrideCrash] silent-crash check failed: " + t);
        }
    }

    /** at game start: look at the last run, then mark this one as running. Returns a result if the last run died silently. */
    public static Analyzer.Result checkAndMark(File gameDir, File crashDir, File ourDir) {
        Analyzer.Result r = null;
        File flag = flag(ourDir);
        if (flag.isFile()) r = lastRun(gameDir, crashDir, flag.lastModified(), pidIn(flag));
        try {
            Files.write(flag.toPath(), ("started " + new java.util.Date() + " pid=" + myPid()).getBytes(StandardCharsets.UTF_8));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> flag.delete(), "PrideCrash clean exit"));
        } catch (Exception ignored) {}
        return r;
    }

    static String myPid() {
        try { return java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]; } catch (Throwable t) { return ""; }
    }

    static String pidIn(File flag) {
        try {
            Matcher m = Pattern.compile("pid=(\\d+)").matcher(new String(Files.readAllBytes(flag.toPath()), StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : "";
        } catch (Exception e) { return ""; }
    }

    /** Linux only: the kernel log line if the out-of-memory killer took that pid (journalctl, readable by the user) */
    static String oomLine(long since, String pid) {
        if (pid.isEmpty() || !new File("/proc").isDirectory()) return null;
        try {
            Process p = new ProcessBuilder("journalctl", "-k", "-q", "--no-pager", "--since", "@" + (since / 1000)).redirectErrorStream(true).start();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String l, hit = null;
            while ((l = br.readLine()) != null) if (l.contains("Killed process " + pid + " ")) hit = l;
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return hit;
        } catch (Exception e) { return null; }
    }

    /** her launcher wrapper records every exit (mc-gamewrap → ~/.cache/mc-exit.log): "exit=137 signal KILL" */
    static String exitSignal(long since) {
        try {
            File f = new File(System.getProperty("user.home"), ".cache/mc-exit.log");
            if (!f.isFile() || f.lastModified() < since) return null;
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            String last = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
            Matcher m = Pattern.compile("signal (\\w+)").matcher(last);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) { return null; }
    }

    static Analyzer.Result lastRun(File gameDir, File crashDir, long since) { return lastRun(gameDir, crashDir, since, ""); }

    static Analyzer.Result lastRun(File gameDir, File crashDir, long since, String pid) {
        File[] crashes = crashDir.listFiles((d, n) -> n.endsWith(".txt"));
        if (crashes != null) for (File c : crashes) if (c.lastModified() >= since) return null;   // a normal crash — already handled
        File hs = null;
        for (File dir : new File[]{gameDir, gameDir.getParentFile()}) {
            File[] fs = dir == null ? null : dir.listFiles((d, n) -> n.startsWith("hs_err_pid") && n.endsWith(".log"));
            if (fs != null) for (File f : fs) if (f.lastModified() >= since && (hs == null || f.lastModified() > hs.lastModified())) hs = f;
        }
        Analyzer.Result r = new Analyzer.Result();
        r.description = "The game closed without a crash report";
        r.time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(since)) + " (the run that started then)";
        r.file = hs == null ? "" : hs.getAbsolutePath();
        String oom = hs == null ? oomLine(since, pid) : null;
        String sig = hs == null ? exitSignal(since) : null;
        if (hs != null) fromHsErr(r, hs);
        else if (oom != null) {
            r.exception = "out of memory (killed)";
            r.message = oom.replaceAll("^.*?Out of memory: ", "");
            r.title = "The computer ran out of RAM, so Linux closed Minecraft";
            r.plain = "Linux's out-of-memory killer stopped Minecraft because the whole computer was out of memory. "
                    + "Minecraft uses more than its heap (graphics, Distant Horizons, native code), and other programs (AI models, browsers, Docker) share the same RAM.";
            r.tips.add("Lower Minecraft's max memory a little, or close big programs (AI models, browsers) while playing.");
            r.tips.add("Kernel log: " + r.message);
        } else {
            r.exception = sig == null ? "killed" : "killed (SIG" + sig + ")";
            r.title = "The game was closed from outside — no crash, no error" + (sig == null ? "" : " (signal " + sig + ")");
            r.plain = "Minecraft didn't crash by itself: it was stopped by something outside it, and Java had no chance to write anything. "
                    + "On Linux the usual reason is the out-of-memory killer — the whole computer ran out of RAM, so the biggest program (Minecraft) got killed. "
                    + "Force-closing it, or the computer going to sleep/losing power, looks the same.";
            r.tips.add("Check if the computer was low on memory: run  journalctl -k | grep -i \"killed process\"  and look for java.");
            r.tips.add("If it was memory: lower Minecraft's max memory a little (it needs room outside the heap too), or close other big programs (AI models, browsers).");
            r.tips.add("If you closed it yourself or the computer slept, you can ignore this.");
        }
        return r;
    }

    private static final Pattern SIGNAL = Pattern.compile("#\\s+(SIG\\w+|EXCEPTION_\\w+)");
    private static final Pattern PROBLEM = Pattern.compile("#\\s+([CjJVv])\\s+(?:\\[([^\\]+]+)[^\\]]*\\]\\s*(.*)|\\d+\\s+\\S+\\s+(\\S+))");

    static void fromHsErr(Analyzer.Result r, File hs) {
        List<String> lines;
        try { lines = Files.readAllLines(hs.toPath(), StandardCharsets.ISO_8859_1); } catch (Exception e) { lines = java.util.Collections.emptyList(); }
        String signal = "", lib = "", javaFrame = "";
        boolean oom = false;
        for (int i = 0; i < Math.min(lines.size(), 60); i++) {
            String l = lines.get(i);
            if (l.contains("insufficient memory for the Java Runtime") || l.contains("Out of Memory Error")) oom = true;
            Matcher s = SIGNAL.matcher(l);
            if (signal.isEmpty() && s.find()) signal = s.group(1);
            if (l.startsWith("# Problematic frame:") && i + 1 < lines.size()) {
                Matcher p = PROBLEM.matcher(lines.get(i + 1));
                if (p.find()) {
                    if (p.group(2) != null) lib = p.group(2).trim();
                    else if (p.group(4) != null) javaFrame = p.group(4);
                }
            }
        }
        r.exception = signal.isEmpty() ? (oom ? "native out of memory" : "JVM crash") : signal;
        r.message = lib.isEmpty() ? javaFrame : lib;
        String l = lib.toLowerCase(Locale.ROOT);
        if (oom) {
            r.title = "Java ran out of memory outside the game's heap";
            r.plain = "Java needed more memory from the computer (for textures, native code, threads) and the computer had none left, so Java stopped.";
            r.tips.add("Close other big programs, or lower Minecraft's max memory a bit — the heap setting isn't all the RAM Minecraft uses.");
        } else if (l.matches(".*(nvidia|libgl|glx|mesa|radeon|amdgpu|iris|i965|zink|vulkan|libdrm|dri|swrast|llvmpipe|libegl).*")) {
            r.title = "The graphics driver crashed (" + lib + ")";
            r.plain = "The crash happened inside your graphics driver, not in a mod's Java code. It's usually a shader/rendering mod asking the driver for something it can't do, "
                    + "or a driver bug. Mods that change rendering (shaders, Celeritas/Nothirium, frame generation, Distant Horizons) are the usual trigger.";
            r.tips.add("If frame generation / Vulkan layers were on, try a launch with them off.");
            r.tips.add("Update the graphics driver (Mesa / NVIDIA).");
            r.tips.add("If it happens in one place, turn off shaders or the rendering mod that draws that thing.");
        } else if (l.contains("lwjgl") || l.contains("openal") || l.contains("glfw")) {
            r.title = "LWJGL (Minecraft's window/sound/input library) crashed (" + lib + ")";
            r.plain = "The low-level library that runs the window, sound or controller input crashed. Often a sound device or controller being plugged/unplugged, or the window system.";
            r.tips.add("If you changed audio devices or unplugged a controller right before, that's the cause; otherwise update the launcher's LWJGL.");
        } else if (l.contains("libjvm") || l.contains("jvm.dll") || !javaFrame.isEmpty()) {
            String mod = javaFrame.isEmpty() ? "" : Analyzer.framework(javaFrame) ? "" : Analyzer.modOf(javaFrame.replaceAll("\\(.*", "").replaceAll("\\.[^.]+$", ""), null);
            r.title = "Java itself crashed" + (mod.isEmpty() ? "" : " while running " + mod);
            r.plain = "The Java runtime (the engine Minecraft runs on) hit an internal error" + (javaFrame.isEmpty() ? "" : " while running " + javaFrame) + ". "
                    + "This is usually a Java bug made worse by aggressive JVM flags or class-changing coremods.";
            r.tips.add("Try the other Java (GraalVM ↔ OpenJ9) or remove unusual -XX flags.");
            if (!mod.isEmpty()) r.tips.add("It was running " + mod + " — if it keeps happening, try without it.");
        } else {
            r.title = "Java crashed in native code" + (lib.isEmpty() ? "" : " (" + lib + ")");
            r.plain = "The crash happened outside Java code, in a system or native library, so no mod's Java code is on the stack.";
            r.tips.add("Open the hs_err file (Open crash file) — the \"Problematic frame\" line names the library.");
        }
        r.tips.add("Full details: " + hs.getName());
    }

    /** self-check: java -cp … com.dogpound.pridecrash.Silent */
    public static void main(String[] a) throws Exception {
        File d = Files.createTempDirectory("pcsilent").toFile(), crash = new File(d, "crash-reports"), our = new File(crash, "pridecrash");
        our.mkdirs();
        check(checkAndMark(d, crash, our) == null, "first start: nothing");
        Analyzer.Result r = checkAndMark(d, crash, our);                   // flag left behind (no shutdown yet) = killed
        check(r != null && r.exception.equals("killed"), "killed from outside");
        Files.write(new File(d, "hs_err_pid42.log").toPath(), ("#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n#  SIGSEGV (0xb) at pc=0x1, pid=42, tid=43\n#\n"
                + "# Problematic frame:\n# C  [libnvidia-glcore.so.550.54+0x9a1b2c]\n").getBytes(StandardCharsets.UTF_8));
        r = checkAndMark(d, crash, our);
        check(r != null && r.title.startsWith("The graphics driver crashed (libnvidia-glcore.so.550.54"), "driver: " + (r == null ? null : r.title));
        check(r.exception.equals("SIGSEGV"), "signal");
        new File(d, "hs_err_pid42.log").delete();
        Files.write(new File(crash, "crash-x.txt").toPath(), new byte[0]);
        check(checkAndMark(d, crash, our) == null, "normal crash report = not silent");
        System.out.println("Silent self-check OK");
    }

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
