package com.chlorine.client;

import com.chlorine.Chlorine;
import com.chlorine.ChlorineConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Builds Chlorine's in-game settings screen — reachable via Mod Menu's
 * "Config" button, the same way Sodium/Sodium Extra/Iris surface theirs.
 *
 * This uses Cloth Config's fluent builder API rather than hand-rolling
 * vanilla widgets directly. Cloth Config's own API (ConfigBuilder,
 * startBooleanToggle, startIntSlider, etc.) has stayed close to identical
 * across years of Minecraft versions — that's the whole point of the
 * library — so it's a much smaller compile-risk surface than building
 * against vanilla's Screen/widget classes directly, especially given
 * 26.1/26.2 reorganized a large chunk of the Gui/Hud/Screen code.
 *
 * === RISK NOTE ===
 * The two imports most likely to need adjusting if this doesn't compile:
 * `net.minecraft.client.gui.screens.Screen` (Mod Menu's ConfigScreenFactory
 * is generic over whatever Screen class it was built against, so this
 * must match) and `net.minecraft.network.chat.Component` (for the
 * `.literal(String)` text factory). Both have been stable Mojmap
 * locations for a long time, but 26.1/26.2 specifically reorganized GUI
 * code, so double-check against your IDE if either fails.
 */
public final class ChlorineConfigScreenBuilder {
    private ChlorineConfigScreenBuilder() {
    }

    public static Screen build(Screen parent) {
        ChlorineConfig cfg = Chlorine.CONFIG;

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Chlorine"))
                .setSavingRunnable(cfg::save);

        ConfigEntryBuilder eb = builder.entryBuilder();

        ConfigCategory simCap = builder.getOrCreateCategory(Component.literal("Simulation Distance Cap"));
        simCap.addEntry(eb.startBooleanToggle(Component.literal("Enable hard cap"), cfg.enableSimDistanceCap)
                .setTooltip(Component.literal("A hard ceiling simulation distance can never exceed, regardless of what the adaptive scaler or chunk-gen governor try to raise it to. Off by default."))
                .setSaveConsumer(v -> cfg.enableSimDistanceCap = v)
                .build());
        simCap.addEntry(eb.startIntSlider(Component.literal("Max simulation distance"), cfg.maxSimulationDistance, 1, 32)
                .setTooltip(Component.literal("Simulation distance will never be allowed above this, even while raising back toward your original setting."))
                .setSaveConsumer(v -> cfg.maxSimulationDistance = v)
                .build());

        ConfigCategory scaler = builder.getOrCreateCategory(Component.literal("Adaptive Scaler"));
        scaler.addEntry(eb.startBooleanToggle(Component.literal("Enable adaptive simulation distance"), cfg.enableAdaptiveSimulationDistance)
                .setTooltip(Component.literal("Lowers/raises simulation distance based on FPS. No render-graph rebuild, unlike render distance."))
                .setSaveConsumer(v -> cfg.enableAdaptiveSimulationDistance = v)
                .build());
        scaler.addEntry(eb.startIntSlider(Component.literal("Min simulation distance"), cfg.minSimulationDistance, 1, 32)
                .setSaveConsumer(v -> cfg.minSimulationDistance = v)
                .build());
        scaler.addEntry(eb.startIntSlider(Component.literal("Low FPS threshold"), cfg.lowFpsThreshold, 5, 200)
                .setSaveConsumer(v -> cfg.lowFpsThreshold = v)
                .build());
        scaler.addEntry(eb.startIntSlider(Component.literal("Target FPS"), cfg.targetFps, 5, 500)
                .setSaveConsumer(v -> cfg.targetFps = v)
                .build());
        scaler.addEntry(eb.startIntField(Component.literal("FPS averaging window (ticks)"), cfg.fpsCheckWindowTicks)
                .setMin(20)
                .setSaveConsumer(v -> cfg.fpsCheckWindowTicks = v)
                .build());
        scaler.addEntry(eb.startIntSlider(Component.literal("Simulation distance step"), cfg.simulationDistanceStep, 1, 8)
                .setSaveConsumer(v -> cfg.simulationDistanceStep = v)
                .build());
        scaler.addEntry(eb.startIntField(Component.literal("Lower cooldown (ticks)"), cfg.lowerCooldownTicks)
                .setMin(1)
                .setSaveConsumer(v -> cfg.lowerCooldownTicks = v)
                .build());
        scaler.addEntry(eb.startIntField(Component.literal("Raise cooldown (ticks)"), cfg.raiseCooldownTicks)
                .setMin(1)
                .setSaveConsumer(v -> cfg.raiseCooldownTicks = v)
                .build());
        scaler.addEntry(eb.startBooleanToggle(Component.literal("Also scale entity distance"), cfg.enableEntityDistanceScaling)
                .setSaveConsumer(v -> cfg.enableEntityDistanceScaling = v)
                .build());
        scaler.addEntry(eb.startDoubleField(Component.literal("Min entity distance scaling"), cfg.minEntityDistanceScaling)
                .setMin(0.1)
                .setMax(5.0)
                .setSaveConsumer(v -> cfg.minEntityDistanceScaling = v)
                .build());
        scaler.addEntry(eb.startDoubleField(Component.literal("Entity distance scaling step"), cfg.entityDistanceScalingStep)
                .setMin(0.05)
                .setMax(1.0)
                .setSaveConsumer(v -> cfg.entityDistanceScalingStep = v)
                .build());
        scaler.addEntry(eb.startBooleanToggle(Component.literal("Drop particles as last resort"), cfg.enableParticleScaling)
                .setSaveConsumer(v -> cfg.enableParticleScaling = v)
                .build());

        ConfigCategory power = builder.getOrCreateCategory(Component.literal("Power Saver"));
        power.addEntry(eb.startBooleanToggle(Component.literal("Enable power saver"), cfg.enablePowerSaver)
                .setTooltip(Component.literal("Caps framerate (and optionally hides clouds) while the window is unfocused."))
                .setSaveConsumer(v -> cfg.enablePowerSaver = v)
                .build());
        power.addEntry(eb.startIntSlider(Component.literal("Unfocused framerate limit"), cfg.unfocusedFramerateLimit, 5, 60)
                .setSaveConsumer(v -> cfg.unfocusedFramerateLimit = v)
                .build());
        power.addEntry(eb.startBooleanToggle(Component.literal("Hide clouds when unfocused"), cfg.hideCloudsWhenUnfocused)
                .setSaveConsumer(v -> cfg.hideCloudsWhenUnfocused = v)
                .build());

        ConfigCategory ai = builder.getOrCreateCategory(Component.literal("Mob AI Throttle"));
        ai.addEntry(eb.startBooleanToggle(Component.literal("Enable distant mob AI throttle"), cfg.enableDistantMobAiThrottle)
                .setTooltip(Component.literal("Skips goal-selector AI ticking (not physics) for mobs with no player nearby."))
                .setSaveConsumer(v -> cfg.enableDistantMobAiThrottle = v)
                .build());
        ai.addEntry(eb.startDoubleField(Component.literal("Active radius (blocks)"), cfg.aiActiveRadius)
                .setMin(4.0)
                .setSaveConsumer(v -> cfg.aiActiveRadius = v)
                .build());
        ai.addEntry(eb.startIntSlider(Component.literal("Throttle interval (ticks)"), cfg.aiThrottleInterval, 1, 40)
                .setSaveConsumer(v -> cfg.aiThrottleInterval = v)
                .build());
        ai.addEntry(eb.startBooleanToggle(Component.literal("Enable Brain-based mob throttle"), cfg.enableBrainThrottle)
                .setTooltip(Component.literal("Same idea, for villagers/piglins/etc. — they use Brain.tick() instead of the goal selector."))
                .setSaveConsumer(v -> cfg.enableBrainThrottle = v)
                .build());
        ai.addEntry(eb.startIntSlider(Component.literal("Brain throttle interval (ticks)"), cfg.brainThrottleInterval, 1, 40)
                .setSaveConsumer(v -> cfg.brainThrottleInterval = v)
                .build());

        ConfigCategory items = builder.getOrCreateCategory(Component.literal("Item Merging"));
        items.addEntry(eb.startBooleanToggle(Component.literal("Enable item merging"), cfg.enableItemMerging)
                .setTooltip(Component.literal("Periodically merges nearby stackable item drops near each player."))
                .setSaveConsumer(v -> cfg.enableItemMerging = v)
                .build());
        items.addEntry(eb.startIntField(Component.literal("Merge interval (ticks)"), cfg.itemMergeIntervalTicks)
                .setMin(20)
                .setSaveConsumer(v -> cfg.itemMergeIntervalTicks = v)
                .build());
        items.addEntry(eb.startDoubleField(Component.literal("Scan radius (blocks)"), cfg.itemMergeScanRadius)
                .setMin(4.0)
                .setSaveConsumer(v -> cfg.itemMergeScanRadius = v)
                .build());
        items.addEntry(eb.startDoubleField(Component.literal("Merge radius (blocks)"), cfg.itemMergeRadius)
                .setMin(0.5)
                .setSaveConsumer(v -> cfg.itemMergeRadius = v)
                .build());
        items.addEntry(eb.startDoubleField(Component.literal("Per-tick work budget (ms)"), cfg.itemMergeBudgetMillis)
                .setMin(0.1)
                .setMax(20.0)
                .setTooltip(Component.literal("Caps merge work per server tick to protect 1% and 0.1% lows."))
                .setSaveConsumer(v -> cfg.itemMergeBudgetMillis = v)
                .build());

        ConfigCategory xp = builder.getOrCreateCategory(Component.literal("XP Orb Merging"));
        xp.addEntry(eb.startBooleanToggle(Component.literal("Enable XP orb merging"), cfg.enableXpOrbMerging)
                .setTooltip(Component.literal("Periodically merges nearby XP orbs near each player. Disables itself automatically (no XP lost) if it can't verify merging is safe."))
                .setSaveConsumer(v -> cfg.enableXpOrbMerging = v)
                .build());
        xp.addEntry(eb.startIntField(Component.literal("Merge interval (ticks)"), cfg.xpMergeIntervalTicks)
                .setMin(20)
                .setSaveConsumer(v -> cfg.xpMergeIntervalTicks = v)
                .build());
        xp.addEntry(eb.startDoubleField(Component.literal("Scan radius (blocks)"), cfg.xpMergeScanRadius)
                .setMin(4.0)
                .setSaveConsumer(v -> cfg.xpMergeScanRadius = v)
                .build());
        xp.addEntry(eb.startDoubleField(Component.literal("Merge radius (blocks)"), cfg.xpMergeRadius)
                .setMin(0.5)
                .setSaveConsumer(v -> cfg.xpMergeRadius = v)
                .build());
        xp.addEntry(eb.startDoubleField(Component.literal("Per-tick work budget (ms)"), cfg.xpMergeBudgetMillis)
                .setMin(0.1)
                .setMax(20.0)
                .setTooltip(Component.literal("Caps orb merge work per server tick to protect 1% and 0.1% lows."))
                .setSaveConsumer(v -> cfg.xpMergeBudgetMillis = v)
                .build());

        ConfigCategory sound = builder.getOrCreateCategory(Component.literal("Sound Budget"));
        sound.addEntry(eb.startBooleanToggle(Component.literal("Enable sound budget"), cfg.enableSoundBudget)
                .setTooltip(Component.literal("Caps how many new sounds can start in the same tick, to smooth out audio-engine bursts."))
                .setSaveConsumer(v -> cfg.enableSoundBudget = v)
                .build());
        sound.addEntry(eb.startIntSlider(Component.literal("Max new sounds per tick"), cfg.maxNewSoundsPerTick, 1, 64)
                .setSaveConsumer(v -> cfg.maxNewSoundsPerTick = v)
                .build());
        sound.addEntry(eb.startDoubleField(Component.literal("Low-FPS budget scale"), cfg.lowFpsEffectBudgetScale)
                .setMin(0.1)
                .setMax(1.0)
                .setTooltip(Component.literal("Scales the sound and particle budgets while FPS is below the adaptive threshold."))
                .setSaveConsumer(v -> cfg.lowFpsEffectBudgetScale = v)
                .build());
        sound.addEntry(eb.startIntSlider(Component.literal("Adaptive low-FPS threshold"), cfg.effectBudgetLowFpsThreshold, 10, 120)
                .setSaveConsumer(v -> cfg.effectBudgetLowFpsThreshold = v)
                .build());

        ConfigCategory particles = builder.getOrCreateCategory(Component.literal("Particle Budget"));
        particles.addEntry(eb.startBooleanToggle(Component.literal("Enable particle budget"), cfg.enableParticleBudget)
                .setTooltip(Component.literal("Caps how many new particles can spawn in the same tick, to smooth out bursts (fireworks, potion clouds, explosions)."))
                .setSaveConsumer(v -> cfg.enableParticleBudget = v)
                .build());
        particles.addEntry(eb.startIntSlider(Component.literal("Max new particles per tick"), cfg.maxNewParticlesPerTick, 10, 2000)
                .setSaveConsumer(v -> cfg.maxNewParticlesPerTick = v)
                .build());

        ConfigCategory autoTune = builder.getOrCreateCategory(Component.literal("Low-End Auto-Tune"));
        autoTune.addEntry(eb.startBooleanToggle(Component.literal("Enable low-end auto-tune"), cfg.enableLowEndAutoTune)
                .setTooltip(Component.literal("On first launch, lowers a few visual options if your system looks constrained."))
                .setSaveConsumer(v -> cfg.enableLowEndAutoTune = v)
                .build());
        autoTune.addEntry(eb.startLongField(Component.literal("Max memory threshold (MB)"), cfg.autoTuneMaxMemoryMb)
                .setMin(256L)
                .setSaveConsumer(v -> cfg.autoTuneMaxMemoryMb = v)
                .build());
        autoTune.addEntry(eb.startIntSlider(Component.literal("Min core count threshold"), cfg.autoTuneMinCores, 1, 32)
                .setSaveConsumer(v -> cfg.autoTuneMinCores = v)
                .build());

        ConfigCategory beaconThrottle = builder.getOrCreateCategory(Component.literal("Beacon & Portal Throttle"));
        beaconThrottle.addEntry(eb.startBooleanToggle(Component.literal("Enable beacon throttle"), cfg.enableBeaconThrottle)
                .setTooltip(Component.literal("Skips rendering beacon beams beyond a configurable distance."))
                .setSaveConsumer(v -> cfg.enableBeaconThrottle = v)
                .build());
        beaconThrottle.addEntry(eb.startDoubleField(Component.literal("Beacon throttle distance (blocks)"), cfg.beaconThrottleDistance)
                .setMin(16.0)
                .setSaveConsumer(v -> cfg.beaconThrottleDistance = v)
                .build());
        beaconThrottle.addEntry(eb.startBooleanToggle(Component.literal("Enable portal particle throttle"), cfg.enablePortalParticleThrottle)
                .setTooltip(Component.literal("Skips nether portal ambient particles beyond a configurable distance."))
                .setSaveConsumer(v -> cfg.enablePortalParticleThrottle = v)
                .build());
        beaconThrottle.addEntry(eb.startDoubleField(Component.literal("Portal particle throttle distance (blocks)"), cfg.portalParticleThrottleDistance)
                .setMin(4.0)
                .setSaveConsumer(v -> cfg.portalParticleThrottleDistance = v)
                .build());

        ConfigCategory chunkGenGov = builder.getOrCreateCategory(Component.literal("Chunk Gen Governor"));
        chunkGenGov.addEntry(eb.startBooleanToggle(Component.literal("Enable chunk gen governor"), cfg.enableChunkGenGovernor)
                .setTooltip(Component.literal("Temporarily lowers simulation distance while fast-traveling (e.g. Elytra) to prioritize frame delivery."))
                .setSaveConsumer(v -> cfg.enableChunkGenGovernor = v)
                .build());
        chunkGenGov.addEntry(eb.startDoubleField(Component.literal("Speed threshold (blocks/tick)"), cfg.chunkGenSpeedThreshold)
                .setMin(0.5)
                .setSaveConsumer(v -> cfg.chunkGenSpeedThreshold = v)
                .build());
        chunkGenGov.addEntry(eb.startIntSlider(Component.literal("Sim dist reduction (chunks)"), cfg.chunkGenSimDistReduction, 1, 16)
                .setSaveConsumer(v -> cfg.chunkGenSimDistReduction = v)
                .build());
        chunkGenGov.addEntry(eb.startIntField(Component.literal("Restore cooldown (ticks)"), cfg.chunkGenRestoreCooldownTicks)
                .setMin(20)
                .setSaveConsumer(v -> cfg.chunkGenRestoreCooldownTicks = v)
                .build());

        // NOTE: the "Lightmap Throttle" category that used to be here was
        // removed — the mixin it configured doesn't compile (see
        // disabled-mixins/README.md) and its config fields were removed
        // from ChlorineConfig.java for the same reason a toggle that does
        // nothing shouldn't ship. This screen previously still referenced
        // the removed fields, which was itself a compile error.

        ConfigCategory soundPreCull = builder.getOrCreateCategory(Component.literal("Sound Pre-Cull"));
        soundPreCull.addEntry(eb.startBooleanToggle(Component.literal("Enable sound pre-cull"), cfg.enableSoundPreCull)
                .setTooltip(Component.literal("Drops sounds whose distance-attenuated volume is below a threshold before they hit OpenAL."))
                .setSaveConsumer(v -> cfg.enableSoundPreCull = v)
                .build());
        soundPreCull.addEntry(eb.startDoubleField(Component.literal("Volume threshold (0.0 - 1.0)"), cfg.soundPreCullVolumeThreshold)
                .setMin(0.01)
                .setMax(0.5)
                .setSaveConsumer(v -> cfg.soundPreCullVolumeThreshold = v)
                .build());

        ConfigCategory sodium = builder.getOrCreateCategory(Component.literal("Sodium Workload Governors"));
        sodium.addEntry(eb.startBooleanToggle(Component.literal("Adaptive rebuild scheduling"), cfg.enableSodiumAdaptiveScheduling)
                .setSaveConsumer(v -> cfg.enableSodiumAdaptiveScheduling = v)
                .build());
        sodium.addEntry(eb.startBooleanToggle(Component.literal("Adaptive culling pressure"), cfg.enableSodiumAdaptiveCulling)
                .setTooltip(Component.literal("Only defers synchronous work while the camera is stable; moving cameras keep Sodium's normal path."))
                .setSaveConsumer(v -> cfg.enableSodiumAdaptiveCulling = v)
                .build());
        sodium.addEntry(eb.startBooleanToggle(Component.literal("Adaptive worker pressure"), cfg.enableSodiumWorkerTuning)
                .setTooltip(Component.literal("Reduces synchronous competition with Sodium's worker queue without changing its worker count."))
                .setSaveConsumer(v -> cfg.enableSodiumWorkerTuning = v)
                .build());
        sodium.addEntry(eb.startBooleanToggle(Component.literal("Dynamic terrain workload"), cfg.enableSodiumTerrainScaling)
                .setSaveConsumer(v -> cfg.enableSodiumTerrainScaling = v)
                .build());
        sodium.addEntry(eb.startIntSlider(Component.literal("Low FPS threshold"), cfg.sodiumLowFpsThreshold, 10, 120)
                .setSaveConsumer(v -> cfg.sodiumLowFpsThreshold = v)
                .build());
        sodium.addEntry(eb.startIntField(Component.literal("Averaging window (ticks)"), cfg.sodiumWorkloadWindowTicks)
                .setMin(10)
                .setSaveConsumer(v -> cfg.sodiumWorkloadWindowTicks = v)
                .build());
        sodium.addEntry(eb.startDoubleField(Component.literal("Camera stability threshold (blocks)"), cfg.sodiumCullingMovementThreshold)
                .setMin(0.01)
                .setMax(4.0)
                .setSaveConsumer(v -> cfg.sodiumCullingMovementThreshold = v)
                .build());
        sodium.addEntry(eb.startIntField(Component.literal("Builder queue pressure threshold"), cfg.sodiumBuilderQueueThreshold)
                .setMin(1)
                .setSaveConsumer(v -> cfg.sodiumBuilderQueueThreshold = v)
                .build());
        sodium.addEntry(eb.startIntField(Component.literal("Pressure hysteresis (ticks)"), cfg.sodiumPressureHysteresisTicks)
                .setMin(2)
                .setSaveConsumer(v -> cfg.sodiumPressureHysteresisTicks = v)
                .build());
        sodium.addEntry(eb.startIntSlider(Component.literal("Recovery FPS"), cfg.sodiumRecoveryFps, 10, 120)
                .setSaveConsumer(v -> cfg.sodiumRecoveryFps = v)
                .build());
        sodium.addEntry(eb.startBooleanToggle(Component.literal("Apply Sodium GPU profile"), cfg.enableSodiumGpuProfile)
                .setTooltip(Component.literal("Enables Sodium fog occlusion, block-face culling, and visible-texture animation optimizations at startup."))
                .setSaveConsumer(v -> cfg.enableSodiumGpuProfile = v)
                .build());
        sodium.addEntry(eb.startBooleanToggle(Component.literal("Adaptive chunk upload budget"), cfg.enableSodiumAdaptiveUploadBudget)
                .setTooltip(Component.literal("Lowers Sodium's 10 ms minimum per-frame chunk upload time so uploads can't run longer than a frame at high FPS. New terrain may appear slightly later while exploring."))
                .setSaveConsumer(v -> cfg.enableSodiumAdaptiveUploadBudget = v)
                .build());
        sodium.addEntry(eb.startDoubleField(Component.literal("Minimum upload budget (ms)"), cfg.sodiumMinUploadBudgetMillis)
                .setMin(0.5)
                .setMax(10.0)
                .setSaveConsumer(v -> cfg.sodiumMinUploadBudgetMillis = v)
                .build());
        sodium.addEntry(eb.startIntField(Component.literal("Full upload budget after joining (seconds)"), cfg.sodiumUploadBudgetGraceSeconds)
                .setMin(0)
                .setSaveConsumer(v -> cfg.sodiumUploadBudgetGraceSeconds = v)
                .build());
        sodium.addEntry(eb.startBooleanToggle(Component.literal("Shrink nearby rebuild radius under pressure"), cfg.enableSodiumNearbyRebuildScaling)
                .setTooltip(Component.literal("While FPS is low, fewer nearby block changes are rebuilt on the main thread right away (TNT, pistons, redstone)."))
                .setSaveConsumer(v -> cfg.enableSodiumNearbyRebuildScaling = v)
                .build());
        sodium.addEntry(eb.startDoubleField(Component.literal("Immediate rebuild radius under pressure (blocks)"), cfg.sodiumPressureRebuildDistance)
                .setMin(0.0)
                .setMax(16.0)
                .setSaveConsumer(v -> cfg.sodiumPressureRebuildDistance = v)
                .build());
        sodium.addEntry(eb.startDoubleField(Component.literal("Immediate present radius under pressure (blocks)"), cfg.sodiumPressurePresentDistance)
                .setMin(0.0)
                .setMax(64.0)
                .setSaveConsumer(v -> cfg.sodiumPressurePresentDistance = v)
                .build());
        sodium.addEntry(eb.startBooleanToggle(Component.literal("Async culling under pressure"), cfg.enableSodiumAsyncCullingUnderPressure)
                .setTooltip(Component.literal("While FPS is low, keeps Sodium's visibility culling on its background thread during fast camera movement. Can cause brief pop-in at screen edges while flying."))
                .setSaveConsumer(v -> cfg.enableSodiumAsyncCullingUnderPressure = v)
                .build());

        ConfigCategory rawPerformance = builder.getOrCreateCategory(Component.literal("Raw Performance Guards"));
        rawPerformance.addEntry(eb.startBooleanToggle(Component.literal("Stationary collision cache"), cfg.enableCollisionCache)
                .setTooltip(Component.literal("Caches block collision shapes for stationary entities until a block changes."))
                .setSaveConsumer(v -> cfg.enableCollisionCache = v)
                .build());
        rawPerformance.addEntry(eb.startBooleanToggle(Component.literal("Fluid tick governor"), cfg.enableFluidTickGovernor)
                .setTooltip(Component.literal("Defers excess fluid callbacks by one tick instead of allowing a single burst to monopolize a server tick."))
                .setSaveConsumer(v -> cfg.enableFluidTickGovernor = v)
                .build());
        rawPerformance.addEntry(eb.startIntField(Component.literal("Max fluid ticks per level tick"), cfg.maxFluidTicksPerTick)
                .setMin(1)
                .setSaveConsumer(v -> cfg.maxFluidTicksPerTick = v)
                .build());

        ConfigCategory frameSmoothing = builder.getOrCreateCategory(Component.literal("Frame Smoothing"));
        frameSmoothing.addEntry(eb.startBooleanToggle(Component.literal("Light update smoothing"), cfg.enableLightUpdateSmoothing)
                .setTooltip(Component.literal("Spreads large light-update backlogs (flying into new chunks, teleports) over several frames instead of one."))
                .setSaveConsumer(v -> cfg.enableLightUpdateSmoothing = v)
                .build());
        frameSmoothing.addEntry(eb.startDoubleField(Component.literal("Light update budget (ms)"), cfg.lightUpdateBudgetMillis)
                .setMin(0.25)
                .setMax(20.0)
                .setSaveConsumer(v -> cfg.lightUpdateBudgetMillis = v)
                .build());
        frameSmoothing.addEntry(eb.startIntField(Component.literal("Min light updates per frame"), cfg.minLightUpdatesPerFrame)
                .setMin(10)
                .setSaveConsumer(v -> cfg.minLightUpdatesPerFrame = v)
                .build());
        frameSmoothing.addEntry(eb.startBooleanToggle(Component.literal("Autosave smoothing"), cfg.enableAutosaveSmoothing)
                .setTooltip(Component.literal("Saves only some chunks in the 5-minute autosave tick; vanilla's background saver handles the rest over the next ticks. Shutdown and /save-all still save everything."))
                .setSaveConsumer(v -> cfg.enableAutosaveSmoothing = v)
                .build());
        frameSmoothing.addEntry(eb.startIntField(Component.literal("Chunks saved immediately per autosave"), cfg.autosaveImmediateChunkLimit)
                .setMin(1)
                .setSaveConsumer(v -> cfg.autosaveImmediateChunkLimit = v)
                .build());

        ConfigCategory lod = builder.getOrCreateCategory(Component.literal("Render LOD"));
        lod.addEntry(eb.startIntSlider(Component.literal("Render pressure FPS threshold"), cfg.renderPressureFpsThreshold, 10, 120)
                .setTooltip(Component.literal("Below this FPS, ambient effects and block entity LOD kick in. They switch off again 10 FPS above it."))
                .setSaveConsumer(v -> cfg.renderPressureFpsThreshold = v)
                .build());
        lod.addEntry(eb.startBooleanToggle(Component.literal("Adaptive ambient block effects"), cfg.enableAdaptiveAmbientEffects)
                .setTooltip(Component.literal("Under render pressure, samples fewer blocks per tick for torch flames, drips and leaf particles."))
                .setSaveConsumer(v -> cfg.enableAdaptiveAmbientEffects = v)
                .build());
        lod.addEntry(eb.startDoubleField(Component.literal("Ambient effects scale under pressure"), cfg.ambientEffectsLowFpsScale)
                .setMin(0.1)
                .setMax(1.0)
                .setSaveConsumer(v -> cfg.ambientEffectsLowFpsScale = v)
                .build());
        lod.addEntry(eb.startBooleanToggle(Component.literal("Sign text LOD"), cfg.enableSignTextLod)
                .setTooltip(Component.literal("Skips drawing sign text too far away to read. The sign itself still renders."))
                .setSaveConsumer(v -> cfg.enableSignTextLod = v)
                .build());
        lod.addEntry(eb.startDoubleField(Component.literal("Sign text distance (blocks)"), cfg.signTextRenderDistance)
                .setMin(4.0)
                .setMax(64.0)
                .setSaveConsumer(v -> cfg.signTextRenderDistance = v)
                .build());
        lod.addEntry(eb.startBooleanToggle(Component.literal("Dropped item LOD"), cfg.enableItemDropLod)
                .setTooltip(Component.literal("Draws far-away dropped stacks as one model instead of several copies."))
                .setSaveConsumer(v -> cfg.enableItemDropLod = v)
                .build());
        lod.addEntry(eb.startDoubleField(Component.literal("Dropped item LOD distance (blocks)"), cfg.itemDropLodDistance)
                .setMin(4.0)
                .setSaveConsumer(v -> cfg.itemDropLodDistance = v)
                .build());
        lod.addEntry(eb.startBooleanToggle(Component.literal("Block entity LOD"), cfg.enableBlockEntityLod)
                .setTooltip(Component.literal("Under render pressure, stops drawing chests, banners, skulls and similar block entities beyond a shorter distance."))
                .setSaveConsumer(v -> cfg.enableBlockEntityLod = v)
                .build());
        lod.addEntry(eb.startDoubleField(Component.literal("Block entity distance under pressure (blocks)"), cfg.blockEntityLodDistance)
                .setMin(8.0)
                .setMax(64.0)
                .setSaveConsumer(v -> cfg.blockEntityLodDistance = v)
                .build());

        ConfigCategory tickDiagnostics = builder.getOrCreateCategory(Component.literal("Tick Diagnostics"));
        tickDiagnostics.addEntry(eb.startBooleanToggle(Component.literal("Enable tick diagnostics"), cfg.enableTickDiagnostics)
                .setTooltip(Component.literal("Pure observability, no behavior change — logs a warning if average server tick time creeps above a threshold."))
                .setSaveConsumer(v -> cfg.enableTickDiagnostics = v)
                .build());
        tickDiagnostics.addEntry(eb.startIntField(Component.literal("Averaging window (ticks)"), cfg.tickDiagnosticsIntervalTicks)
                .setMin(20)
                .setSaveConsumer(v -> cfg.tickDiagnosticsIntervalTicks = v)
                .build());
        tickDiagnostics.addEntry(eb.startDoubleField(Component.literal("Warn threshold (ms/tick)"), cfg.tickDiagnosticsWarnMs)
                .setMin(50.0)
                .setSaveConsumer(v -> cfg.tickDiagnosticsWarnMs = v)
                .build());

        return builder.build();
    }
}
