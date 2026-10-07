# Changelog

## 0.2.0 - 2026-10-07

### Added
- **Replaces LoliASM's crash screens**: when LoliASM keeps the game alive after a crash, the PrideCrash report (guilty mod, plain-English explanation, what to try) is shown instead, with a **Main Menu** button for in-game crashes or **Quit Game** for crashes during startup. This also covers crashes that happen while mods are still loading, before PrideCrash itself has finished loading.
- **Force-load worlds** (`forceWorldLoad`, on by default): when a mod throws an error while a world is starting, its broken step is skipped and the world keeps loading. Every player who joins is told in chat which mods failed; full details go to `logs/latest.log`.
- When a world stops or fails to open and drops you on the title screen, the banner now appears immediately ("Your world stopped") instead of only after a restart.
- Silent deaths on Linux are now identified as out-of-memory kills when the kernel log shows the game's process was killed, with a clear explanation and tips. The kill signal is shown when it is known.

### Changed
- The silent-death check now runs at the earliest possible moment (from the coremod), so a launch that crashes during mod construction no longer hides the previous run's result.
- Every silent death is kept in the crash history as its own entry; it only replaces the pending main-menu banner if nothing newer is waiting.
- Report screens follow the pack-wide menu theme chosen in PrideCanvas (Themes) when it is installed.

