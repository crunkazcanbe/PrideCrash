package com.dogpound.pridecrash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which jar (and which mod) a class comes from — by package, read straight from every jar's list of files, so it
 * works at crash time without loading anything. Also remembers mixin classes by simple name, because LoliASM
 * writes the mixin's source file into stack lines of patched Minecraft code (e.g. "MixinWorld.java:2115").
 */
public final class ModIndex {
    public static final class Jar {
        public final String file, name, id;
        /** ships an access transformer (META-INF/*_at.cfg) that its manifest never loads (no FMLAT line) — Tombstone 4.8.0 */
        public boolean atNotLoaded;
        public String atFile;
        Jar(String file, String name, String id) { this.file = file; this.name = name; this.id = id; }
        @Override public String toString() { return name + " (" + file + ")"; }
    }

    private final Map<String, Jar> byPackage = new HashMap<>();
    private final Map<String, List<Jar>> mixinBySimpleName = new HashMap<>();
    private final Map<String, Jar> byModId = new HashMap<>();
    private final Map<String, Jar> byFile = new HashMap<>();
    public int jars;

    /** index these jars (mods folder, the active mod group…) */
    public static ModIndex build(Collection<File> files) {
        ModIndex idx = new ModIndex();
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".jar")) continue;
            try (ZipFile z = new ZipFile(f)) {
                Jar jar = describe(z, f.getName());
                idx.jars++;
                idx.byFile.put(jar.file, jar);
                jar.atFile = atFile(z);
                jar.atNotLoaded = jar.atFile != null && !hasFmlAt(z);
                if (jar.id != null) idx.byModId.putIfAbsent(jar.id.toLowerCase(Locale.ROOT), jar);
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                    int slash = n.lastIndexOf('/');
                    String pkg = slash < 0 ? "" : n.substring(0, slash).replace('/', '.');
                    idx.byPackage.putIfAbsent(pkg, jar);
                    String simple = n.substring(slash + 1, n.length() - 6);
                    if (simple.contains("Mixin") || n.contains("/mixin/") || n.contains("/mixins/"))
                        idx.mixinBySimpleName.computeIfAbsent(simple, k -> new ArrayList<>()).add(jar);
                }
            } catch (Exception ignored) {}
        }
        return idx;
    }

    private static String atFile(ZipFile z) {
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.startsWith("META-INF/") && n.endsWith("_at.cfg")) return n.substring(9);
        }
        return null;
    }

    private static boolean hasFmlAt(ZipFile z) {
        try {
            java.util.jar.Manifest m = z instanceof java.util.jar.JarFile ? ((java.util.jar.JarFile) z).getManifest() : null;
            if (m == null) {
                ZipEntry me = z.getEntry("META-INF/MANIFEST.MF");
                if (me == null) return false;
                m = new java.util.jar.Manifest(z.getInputStream(me));
            }
            String at = m.getMainAttributes().getValue("FMLAT");
            return at != null && !at.trim().isEmpty();
        } catch (Exception e) { return true; }
    }

    /** the mod's name and id from mcmod.info, else a tidy file name */
    private static Jar describe(ZipFile z, String file) {
        String name = null, id = null;
        ZipEntry info = z.getEntry("mcmod.info");
        if (info != null) {
            try (InputStreamReader r = new InputStreamReader(z.getInputStream(info), StandardCharsets.UTF_8)) {
                JsonElement root = new JsonParser().parse(r);
                JsonArray list = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().has("modList") ? root.getAsJsonObject().getAsJsonArray("modList") : null;
                if (list != null && list.size() > 0) {
                    JsonObject m = list.get(0).getAsJsonObject();
                    if (m.has("name")) name = m.get("name").getAsString();
                    if (m.has("modid")) id = m.get("modid").getAsString();
                }
            } catch (Exception ignored) {}
        }
        if (name == null || name.trim().isEmpty()) name = tidy(file);
        return new Jar(file, name.trim(), id);
    }

    /** "tombstone-1.12.2-4.8.0.jar" → "tombstone" */
    static String tidy(String file) {
        String n = file.replaceAll("\\.jar$", "").replaceAll("^\\[[^]]*]\\s*", "");
        String[] parts = n.split("[-_ ]");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.matches("(?i)(mc)?1\\.\\d+(\\.\\d+)?|\\d+(\\.\\d+)*[a-z]?|v\\d.*|forge|universal|release|beta|alpha|mc")) break;
            b.append(b.length() == 0 ? "" : " ").append(p);
        }
        return b.length() == 0 ? n : b.toString();
    }

    /** the jar a class comes from (longest package match), or null */
    public Jar ofClass(String cls) {
        int dot = cls.lastIndexOf('.');
        String pkg = dot < 0 ? "" : cls.substring(0, dot);
        while (true) {
            Jar j = byPackage.get(pkg);
            if (j != null) return j;
            int d = pkg.lastIndexOf('.');
            if (d < 0) return null;
            pkg = pkg.substring(0, d);
        }
    }

    /** jars that contain a mixin class with this simple name ("MixinWorld") */
    public List<Jar> mixinsNamed(String simple) { return mixinBySimpleName.getOrDefault(simple, Collections.emptyList()); }

    public Jar ofFile(String file) { return file == null ? null : byFile.get(file); }

    public Jar ofModId(String id) { return id == null ? null : byModId.get(id.toLowerCase(Locale.ROOT)); }
}
