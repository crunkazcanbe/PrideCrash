package com.dogpound.pridecrash;

import net.minecraftforge.common.config.Config.Comment;
import net.minecraftforge.common.config.Config.Name;

@net.minecraftforge.common.config.Config(modid = PrideCrash.MODID, name = "pridecrash")
public class Config {
    @Name("enabled") @Comment("Add a \"PrideCrash says\" section to every crash report.")
    public static boolean enabled = true;
    @Name("indexJars") @Comment("Read every mod jar's file list to name mods exactly (takes ~2 s, only when a crash happens).")
    public static boolean indexJars = true;
    @Name("popupAfterCrash") @Comment("After a crash, tell you on the next main menu (a banner you can click).")
    public static boolean popupAfterCrash = true;
    @Name("fullScreenPopup") @Comment("Open the whole report screen instead of the banner (blocks the main menu until you close it — auto-join mods wait).")
    public static boolean fullScreenPopup = false;
    @Name("aiUrl") @Comment("OpenAI-style chat URL for \"Ask AI\" (llama-swap / LiteLLM / Ollama). Empty = button off.")
    public static String aiUrl = "http://127.0.0.1:8080/v1/chat/completions";
    @Name("aiModel") @Comment("Model name sent to the AI.")
    public static String aiModel = "qwen27";
    @Name("aiKey") @Comment("API key if your AI needs one (blank for local).")
    public static String aiKey = "";
}
