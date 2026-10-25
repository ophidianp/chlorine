# Chlorine

Chlorine is a **Fabric performance and power-management mod for Minecraft
26.3**. It targets CPU-side simulation work, bursty entity effects, and
laptop power usage while leaving chunk rendering and renderer internals to
specialized mods.

Current release: **1.0.7**

## What it does

### Adaptive client tuning

- **Adaptive simulation distance:** monitors a rolling FPS average and lowers
  simulation distance when performance falls below the configured threshold.
  It raises the value again after a longer recovery cooldown.
- **Entity-distance scaling:** can reduce the entity-distance multiplier after
  simulation distance reaches its configured floor.
- **Particle fallback:** can reduce the particle setting as a last resort.
- **Simulation-distance cap:** optionally prevents other tuning systems from
  raising simulation distance above a fixed ceiling.
- **Fast-travel chunk governor:** temporarily lowers simulation distance while
  moving quickly, such as with an Elytra or Riptide trident, then restores it
  after a cooldown.

Chlorine intentionally changes **simulation distance**, not render distance.
Render-distance changes can rebuild the entire chunk render graph and cause a
large hitch; simulation distance instead targets server ticking, entity
updates, random ticks, and block updates.

### Server and gameplay-side optimization

- **Distant classic-mob AI throttle:** staggers goal-selector AI for mobs such
  as zombies and skeletons when no player is nearby. Physics and nearby mobs
  are not throttled.
- **Distant Brain-mob throttle:** applies the same idea directly to
  `Brain.tick()` for villagers, piglins, hoglins, allays, frogs, and other
  Brain-driven mobs.
- **Item merging:** periodically combines compatible nearby item entities
  around online players.
- **XP orb merging:** combines nearby experience orbs. The implementation
  verifies the internal value update before discarding an orb and disables
  itself for the session if that operation cannot be verified.
- **Stationary collision cache:** reuses block collision-shape results for
  stationary entities and invalidates the cache when blocks change.
- **Fluid tick governor:** caps fluid callbacks per level tick and reschedules
  overflow one tick later to flatten pathological water/lava bursts. This is
  disabled automatically when its configuration toggle is off.
- **Server tick diagnostics:** optionally logs a warning when average server
  tick time exceeds the configured threshold. This is observability only; it
  does not alter server behavior.
### Client effects and power saving

- **Unfocused-window power saver:** caps FPS while the game window is
  unfocused or minimized and can hide clouds during that period.
- **Sound budget:** limits how many new sounds may start in one client tick,
  smoothing bursts from farms, combat, or other events.
- **Sound pre-cull:** drops very quiet distance-attenuated sounds before they
  reach the audio engine.
- **Particle budget:** limits particle creation per client tick to smooth
  fireworks, explosions, potion clouds, and similar bursts.
- **Beacon and portal throttles:** skips distant beacon beams and Nether portal
  ambient particles when enabled.
- **Low-end auto-tune:** once per launch, detects constrained memory or CPU
  availability and applies lighter vanilla visual defaults.

All features are independently configurable and can be disabled without
removing the mod.

## Requirements

- Minecraft **26.3**
- Fabric Loader **0.19.5 or newer**
- Fabric API
- Java **25 or newer**
- [Cloth Config API](https://modrinth.com/mod/cloth-config) (required)

[Sodium 0.9.2 or newer](https://modrinth.com/mod/sodium) (required)

[Mod Menu](https://modrinth.com/mod/modmenu) is optional. When installed, it
adds Chlorine's **Config** button to the mod list.

Chlorine 1.0.3 and newer require Sodium because its client workload governors integrate
directly with Sodium's renderer. Lithium, FerriteCore, EntityCulling,
ImmediatelyFast, and Iris remain optional.

## Installation

1. Install Fabric Loader for Minecraft 26.3.
2. Install Fabric API and Cloth Config API.
3. Place `chlorine-1.0.7.jar` in the instance's `mods` directory.
4. Optionally install Mod Menu for the in-game configuration screen.

The configuration file is created at:

```text
config/chlorine.json
```

Changes made through the Cloth Config screen are written to the same file.

## Configuration overview

The in-game screen groups settings into:

- Simulation Distance Cap
- Adaptive Scaler
- Power Saver
- Mob AI Throttle
- Item Merging
- XP Orb Merging
- Sound Budget
- Particle Budget
- Low-End Auto-Tune
- Beacon & Portal Throttle
- Chunk Gen Governor
- Sound Pre-Cull
- Sodium Workload Governors (including the 1.0.7 upload budget, nearby
  rebuild scaling and async culling options)
- Sodium GPU Profile
- Raw Performance Guards
- Time-Budgeted Merge Work
- Adaptive Effect Budgets
- Tick Diagnostics

Important interactions:

- The adaptive scaler, chunk governor, and simulation-distance cap all operate
  on the client simulation-distance option.
- Chlorine lowers the client simulation-distance minimum to 1 chunk, including
  in Sodium's settings screen when Sodium is installed.
- Chlorine's Sodium workload governor samples Sodium's public builder counters at
  `RenderSectionManager.finalizeRenderLists(...)`. It defers the immediate
  update request only after a sustained low-FPS window, a real queued-job
  backlog, and a stable camera all agree that synchronous work is contributing
  to pressure. Hysteresis prevents rapid toggling, and moving cameras retain
  Sodium's normal path. Chlorine does not replace Sodium's culler, builder, or
  worker pool.
- The Sodium GPU profile enables Sodium's exposed fog-occlusion, block-face
  culling, and visible-texture animation settings. It does not replace
  Sodium's OpenGL renderer or attempt the GPU entity-transform work tracked
  upstream as future research.
- Item and XP-orb merge passes use small per-tick wall-clock budgets and
  resume where they stopped. Dense farms therefore cannot turn one merge pass
  into a large server-tick or frame-time spike.
- Sound and particle burst budgets automatically tighten while FPS is low and
  return to their configured limits when performance recovers. Effects are
  dropped rather than queued, so the feature cannot create delayed burst work.
- Lowering AI throttle intervals improves responsiveness but reduces the
  possible CPU savings. Start with the defaults and adjust gradually.

## Building from source

This repository includes the Gradle wrapper. Use:

```bash
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

The build requires Java 25 and internet access for Minecraft, Fabric, and
library dependencies. Output jars are written to:

```text
build/libs/
```

The GitHub Actions workflow in `.github/workflows/build.yml` builds with
Temurin Java 25 and Gradle 9.5.1, then uploads the jars as workflow
artifacts.

## Project structure

```text
src/main/java/com/chlorine/
  Chlorine.java                 Common Fabric entrypoint
  ChlorineConfig.java           JSON configuration and defaults
  ItemMerger.java               Nearby item-entity merging
  XpOrbMerger.java              Defensive XP-orb merging
  ServerTickDiagnostics.java    Server tick-health logging
  mixin/                        Common AI mixins

src/client/java/com/chlorine/client/
  ChlorineClient.java            Client entrypoint and tick orchestration
  PerformanceScaler.java         FPS-driven simulation tuning
  PowerSaver.java                Unfocused-window power saving
  ChunkGenGovernor.java          Fast-travel tuning
  SimDistanceCap.java            Simulation-distance ceiling
  LowEndAutoTune.java            One-time low-end defaults
  ChlorineConfigScreenBuilder.java
                                  Cloth Config screen

src/client/java/com/chlorine/mixin/client/
                                  Client sound, particle, beacon, and portal hooks
```

The mod uses Fabric lifecycle events for periodic work and narrowly scoped
Mixin injections for Minecraft internals. It does not replace the renderer,
rewrite chunk storage, or add blocks, items, entities, or world-generation
content.

## Known limitations

- Performance results depend on the world, entity count, hardware, and other
  installed mods. The defaults are conservative but are not universal
  benchmarks.
- Throttling distant AI intentionally trades some background simulation
  frequency for lower CPU usage.
- `LightmapThrottleMixin` is parked in `disabled-mixins/` and is not part of
  the build because the targeted lighting class changed in the 26.x rewrite.
- XP orb merging fails safe: if the orb value field cannot be read or written,
  the feature disables itself for that session instead of risking XP loss.

## Note
The github auto code type shows that its 55% html, and shows the primary language AS html. This is
due to the Gradle and Building logs generated by Gradle. I dont plan to delete these logs as they
show authenticity and show some originality that I built this (using AI ig but I built it).

## License

All Rights Reserved. See [LICENSE](LICENSE).
