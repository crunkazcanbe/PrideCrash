# PrideCrash

A crash analyzer for Minecraft 1.12.2 that names the mod most likely to blame, explains the crash in plain English and shows a report the next time you start the game.

## About

In a large modpack a crash report is often hundreds of lines of stack trace with no clear culprit. PrideCrash adds a short "PrideCrash says" section to every crash report: what happened, a ranked list of suspect mods with a percentage for each, the reasons behind each score, and what to try next.

It also notices when the game died without writing a crash report at all, for example when Java itself crashed inside the graphics driver or when the operating system killed the game for using too much memory.

PrideCrash was built for the Pride modpack, a 1.12.2 pack of about 750 mods, but it does not depend on anything in that pack and works in other packs. All analysis happens on your computer. The optional "Ask AI" button only talks to an address you configure, and it is meant for a local model.

## Features

### Crash report section
- A mixin on `CrashReport.getCompleteReport()` appends a `-- PrideCrash says --` section to every crash report, both the file in `crash-reports/` and the log. It runs at priority 2000, so MixinBooter's "Mixins in Stacktrace" list is already in the text it reads.
- The analysis runs once per report. Any error inside PrideCrash is caught so it cannot make a crash worse.
- The section contains: what happened, a plain-words explanation, up to 5 suspects with percentage, jar file and up to 3 reasons each, and a "What to try" list.
- The analysis is saved as JSON in `crash-reports/pridecrash/` so the report screen opens instantly. Crash files from before PrideCrash was installed are analysed when you open them.

### How suspects are scored
PrideCrash reads the text of the crash report, so it works on a crash happening right now and on any old crash file.

1. **Stack frames are parsed.** Each `at ...` line is split into class, method, file and line. Frames are grouped into blocks: block 0 is the top exception, and each `Caused by:` starts a new block. Module prefixes such as `java.base/` and `app//` are handled.
2. **Frames are assigned to mods.** Frames from Java, Minecraft, Forge, Mixin, MixinExtras, Cleanroom, MixinBooter, LWJGL, Netty, Guava, Apache, Scala, Kotlin, OptiFine, the launcher and PrideCrash itself count as framework code. Every other frame is mapped to a mod:
   - by the jar that contains its package (PrideCrash indexes the class list of every jar in `mods/`, `mods/1.12.2/`, `mods/1.12` and every jar Forge reports as loaded, and reads each jar's `mcmod.info` for the mod name and id), or
   - by guessing from the package name when no jar matches (it skips top-level domains, author names and words such as `mixin`, `core`, `api`, `common`; for example `ovh.corail.tombstone.X` becomes `tombstone`).
3. **Weights.** For each frame that belongs to a mod:
   - nearness: `1 / (1 + 0.25 × depth)`, where depth is the frame's position within its block, so frames nearer the throw count more;
   - real cause: ×1.5 if the frame is in the last `Caused by` block;
   - thrower: ×4 for the first mod frame overall (the mod whose code threw the error).
4. **Mixin-patched Minecraft frames.** A framework frame whose source file name does not match its class (for example `World.updateEntity(MixinWorld.java:2077)`) or whose method is a mixin handler (`handler$`, `redirect$`, `modify$`, `localvar$`, `constant$`, `wrapOperation$`, `wrapWithCondition$`, MixinExtras) is traced to the mod that owns the mixin:
   - from the mod id embedded in the handler name, or a `modid$` prefix on the merged method name;
   - else from MixinBooter's "Mixins in Stacktrace" list (mixins on that class whose simple name matches the file);
   - else from a unique jar containing a mixin class with that simple name.
   A handler method is mod code merged into Minecraft and is weighted like a normal mod frame. A mixin source file alone only means "this mod patched it" and gets 0.25 of the weight. Weight is split evenly if several mods share the patch.
5. **Hints from the message and details.**
   - Mod class names mentioned in the exception message (for example in a `ClassCastException`): +3.0.
   - Mixin config files named in the message (`mixins.<id>.json`, `<id>.mixins.json`): +3.0.
   - Registry ids in report details such as `Entity Type: modid:thing`, `Block type`, `Tile entity type`: +0.5.
6. **Percentages.** Each suspect's score is divided by the sum of all scores. Suspects under 3% are dropped (the top one is always kept). If the top suspect is under 40% and there is a second one, a tip says the two may be clashing.

### Plain-English explanations
The root exception (the deepest `Caused by`) picks the explanation and tips. Recognised types:

| Exception | Explanation |
|-----------|-------------|
| `IllegalAccessError` | the mod used a locked part of Minecraft; names the field or method. If the blamed jar ships an access transformer (`META-INF/*_at.cfg`) but its manifest has no `FMLAT:` line, it says so and gives the exact line to add. |
| `NullPointerException` | the mod used something that wasn't there |
| `ClassCastException` | the mod mixed up two kinds of things, often two mods changing the same object |
| `NoClassDefFoundError`, `ClassNotFoundException` | a dependency is missing or the wrong version |
| `NoSuchMethodError`, `NoSuchFieldError`, `AbstractMethodError`, `IncompatibleClassChangeError` | built for a different version of something |
| `OutOfMemoryError` | the game ran out of memory |
| `StackOverflowError` | stuck in a loop, often two mods triggering each other |
| `ConcurrentModificationException` | a list changed while being read (threading bug) |
| `ArrayIndexOutOfBoundsException`, `IndexOutOfBoundsException` | went past the end of a list, often a changed config or id |
| `MixinApplyError`, `InvalidInjectionException`, `InvalidMixinException`, `MixinTransformerError` | a mod's patch into Minecraft didn't fit |
| `ReportedException` | Minecraft caught an error from the mod |
| anything else | generic "crashed" summary with the exception and message |

The crash description is turned into words too ("Ticking entity" becomes "updating a mob/entity", and so on). For ticking entity and ticking block entity crashes there is an extra tip about removing the broken mob or block.

### Silent deaths (no crash report)
- At start-up a `running.flag` file is written and a shutdown hook deletes it on any normal exit.
- If the flag is still there on the next start and no crash report is newer than it, the last run died silently. PrideCrash then looks for an `hs_err_pid*.log` from that run in the game folder and its parent:
  - **No hs_err file**: the game was killed from outside (on Linux usually the out-of-memory killer, or a force-close or power loss). Tips include checking `journalctl -k` for killed processes.
  - **hs_err file**: reads the signal (`SIGSEGV`, `EXCEPTION_*`) and the "Problematic frame" line, then classifies it as native out-of-memory, a graphics driver crash (NVIDIA, Mesa, AMD, Intel, Vulkan, EGL and similar libraries), an LWJGL/OpenAL/GLFW crash, a JVM-internal crash (naming the mod if a Java frame is shown), or another native library crash.
- The result is shown on the next main menu like a normal crash.

### Main-menu banner
- After a crash, the first title screen shows a banner: "Your game crashed last time", the title, and the top suspect with its percentage. Click "See why" to open the report, or the ✕ to dismiss it.
- Works with replacement main menus (any screen whose class name ends in `MainMenu` or contains `TitleScreen`).
- Optionally the full report screen can open instead of the banner (`fullScreenPopup`).

### Report screen
- Left: every crash report in `crash-reports/`, newest first, with date, a "game" or "world" (client or server) badge, and the blamed mod once analysed.
- Right, six tabs:
  - **Summary**: title, explanation, the most likely guilty mod with a confidence bar and jar file, other involved mods, tips, time, side and exception.
  - **Suspects**: every suspect with percentage, jar (or "guessed from its package name"), reasons, and buttons to copy the name, search for updates on CurseForge, or search GitHub issues for the mod and exception.
  - **Stack**: the stack with each line coloured and tagged by mod (mod code, Minecraft code a mod patched, or framework). Option to show only mod lines. Copy-stack button.
  - **Details**: time, side, description, exception, message, real cause, stack line counts, what was being updated, file name.
  - **Mixins**: the "Mixins in Stacktrace" list grouped by target class and mod.
  - **Ask AI**: see below.
- Bottom bar: Copy report, Open crash file, Open folder, Re-check (re-analyse), Ask AI, and Close / Back / Continue.
- Keys: ↑/↓ select a crash, Tab cycles tabs, 1–6 jump to a tab, Ctrl+C copies the report, Esc closes.

### Ask AI (optional)
- Sends PrideCrash's analysis plus the first 80 lines of the crash report to an OpenAI-compatible chat completions endpoint (llama-swap, LiteLLM, Ollama and similar). The prompt asks for what broke, which mod is likely to blame and why, and up to four things to try.
- Request: `temperature` 0.3, `max_tokens` 700, 5 s connect timeout, 180 s read timeout. `<think>` blocks are removed from the answer.
- Off when `aiUrl` is empty. Nothing is sent unless you press the button.

## Commands

Client-side command, available to everyone.

| Command | Effect |
|---------|--------|
| `/pridecrash` or `/pridecrash list [tab]` | Open the report screen with the newest crash selected. |
| `/pridecrash latest [tab]` | Open the newest crash report. |
| `/pridecrash test` | Trigger a real test crash through the normal path, to check the whole chain (crash hook, file, next-start banner). |

`[tab]` is one of `summary`, `suspects`, `stack`, `details`, `mixins`, `ai`. Tab completion is supported.

## Config

File: `config/pridecrash.cfg`

| Key | Default | Effect |
|-----|---------|--------|
| `enabled` | `true` | Add a "PrideCrash says" section to every crash report. |
| `indexJars` | `true` | Read every mod jar's file list to name mods exactly (about 2 seconds, only when a crash happens or a report is opened). Off = guess from package names. |
| `popupAfterCrash` | `true` | After a crash, show a clickable banner on the next main menu. |
| `fullScreenPopup` | `false` | Open the whole report screen instead of the banner. This blocks the main menu until closed, so auto-join mods wait. |
| `aiUrl` | `http://127.0.0.1:8080/v1/chat/completions` | OpenAI-style chat URL for "Ask AI". Empty = button off. |
| `aiModel` | `qwen27` | Model name sent to the AI. |
| `aiKey` | *(empty)* | API key, sent as `Authorization: Bearer`, if your endpoint needs one. |

## Files

| Path | Contents |
|------|----------|
| `crash-reports/pridecrash/<crash name>.json` | saved analysis for each crash |
| `crash-reports/pridecrash/pending.json` | the newest crash not yet shown |
| `crash-reports/pridecrash/last-shown.json` | the last crash shown on the main menu |
| `crash-reports/pridecrash/running.flag` | present while the game runs; used for silent-death detection |

## Requirements

- Minecraft 1.12.2
- Forge 14.23.5.2860 or newer, or Cleanroom
- [MixinBooter](https://github.com/CleanroomMC/MixinBooter) (bundled with Cleanroom)
- Install on the client. It also loads on a server and adds the crash report section there; the banner, screen and command are client-only.

## Install

1. Download `PrideCrash-1.12.2-<version>.jar`.
2. Put it in your `mods` folder next to MixinBooter (not needed on Cleanroom).
3. Start the game. Run `/pridecrash test` once to see the whole flow.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. The project targets Java 8 (ForgeGradle 3, MCP snapshot `20171003-1.12`). It compiles against `libs/mixinbooter-api.jar` and `libs/sponge-mixin.jar`, which you need to place in `libs/` before building.

Both `Analyzer` and `Silent` have a `main` method with built-in self-checks that can be run on the compiled classes.

## License

MIT License. © 2026 crunkazcanbe.

## Credits

Made by crunkazcanbe, with Claude.


## Compile-only jars

The build compiles against these jars in `libs/` (other authors' mods / APIs). They are not included in this repo — get them from their official pages and drop them in `libs/` before building:

- `mixinbooter-api.jar`
- `sponge-mixin.jar`
