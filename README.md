# Isotope

**Run Fabric mods built for a different Minecraft 26.x version.**

An isotope is the same element with a different mass number. Isotope gives you
the same mod with a different version number: it takes a mod built for another
26.x release, rewrites it to match the Minecraft you are actually running, and
tells you up front what it could not bridge.

It works in both directions: an older mod on a newer game (a 26.1.2 mod on
26.3) and a newer mod on an older game (a 26.3 mod on 26.2).

> **Status: 0.1.0, early.** Boot-tested on a 26.3 dedicated server with real
> 26.1.x mods from Modrinth (results below). **Not tested on a client yet.**
> Read [What it cannot do](#what-it-cannot-do) before expecting any particular
> mod to work.

## Using it

1. Put `isotope-<version>.jar` in your normal `mods` folder. Needs Fabric
   Loader 0.19.5+ and Java 25. It runs on any 26.x; it has tables for
   **26.1 – 26.1.2, 26.2 and 26.3**.
2. Start the game once. Isotope creates an `isotope` folder next to `mods`.
3. Put the mods that are for **other** game versions in `isotope/mods`.
   Not in the normal `mods` folder: Fabric rejects a wrong-version mod there
   before any mod, Isotope included, gets to run.
4. Start the game. Then read `isotope/report.txt`.

Isotope works out which version a mod was built for by checking which game
version actually contains the classes the mod uses (mod metadata is often
vague, e.g. `>=26.1`). To override that, put the jar in a folder named after
the version, e.g. `isotope/mods/26.1.2/somemod.jar`.

Dependencies still apply. If the old mod needs a library, install that library
normally (a build for your game version if one exists, otherwise drop the old
one into `isotope/mods` too).

### The report

Every launch rewrites `isotope/report.txt`. For each mod:

```
== toofast  [toofast-fabric-26.1-0.5.5.8.jar]
   built for Minecraft 26.1.2, running on 26.3
   0 references redirected, 0 of 1 classes rewritten
   verdict: links cleanly against 26.3 - every game reference resolved
```

or, when something is left over:

```
   method no longer exists (or its signature changed) (1):
     - net.minecraft.core.BlockPos.<init>(Lnet/minecraft/core/Vec3i;)V
         used in com.example.SomeClass
```

"Links cleanly" means every class, method and field the mod refers to exists
in your game. It is the strongest thing that can be said without running the
code; it is not a promise the mod behaves correctly. Anything listed is a
place the mod will throw an error **if that code runs** - a mod with a few
entries in rarely used corners can still be perfectly playable.

### Mods Isotope refuses to load

Some problems are certain to crash the game, so by default Isotope does not
load the mod and says why in the log and the report:

- **"class changed shape"**: a mod class extends a game class that has since
  become `final`, `sealed` or an interface. The JVM refuses to load that class
  at all (26.3 sealed `Holder` and turned `Feature` and `StructureProcessor`
  into interfaces).
- **"fails at startup"**: Isotope follows the mod's own code from its
  initializer, and something on that path no longer exists. Initializers run
  on every launch, so this is a crash on every launch, not "maybe later".

A mod that needs a skipped mod is skipped too. Set
`load-mods-with-fatal-problems=true` to load them anyway (expect a crash).

### Settings

`config/isotope.properties`:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `lenient-mixins` | `true` | When a rewritten mod's mixin no longer fits, skip that one hook instead of crashing the game. The feature behind the hook will not work. |
| `load-mods-with-fatal-problems` | `false` | Load mods even when Isotope is certain they will crash (see above). For testing. |
| `cache` | `true` | Reuse last launch's rewritten jars when nothing changed. |

## How it works

Since 26.1, Minecraft ships unobfuscated: classes and methods have real names
in the game jar, and mods refer to them by those names. So the difference
between two versions can be read straight off the two jars.

**Ahead of time (in this repo):** `DiffGen` compares two game jars. Whatever
exists in the old one but not the new one is "missing", and it tries to prove
where each missing thing went:

- a **class** that moved or was renamed is matched by its member list
  (26.3 moved most of `com.mojang.blaze3d` to `com.mojang.renderpearl`);
- a **method** that was renamed is matched by comparing bytecode bodies
  (`PoseStack.mulPose(Quaternionfc)` became `rotate`);
- a **field** is matched by how it is initialised or which methods use it;
- a method whose **signature** changed in a way callers cannot notice (a
  parameter became a parent type, `void` became a return value) is re-pointed.

Only confident matches become rules. The result is one small text table per
version step, in `src/main/resources/isotope/redirects/`.

A few things no automatic matcher can prove get **hand-written rules** in a
`.manual` file next to the table, each verified against both jars and
explained in a comment. Two kinds exist so far:

- renames proven by usage, e.g. 26.3 renamed *and reordered* every
  `PushReaction` constant (`DESTROY → POPPED` is backed by the same 201
  vanilla blocks switching over);
- **stubs**: a removed API whose whole system the game dropped, so calls to it
  get `null` instead of crashing. Only one system qualifies today: 26.3
  removed per-block codecs entirely, but old mods still build one in a static
  initializer.

**At game start:**

1. Isotope hooks in as a Fabric *language adapter*, the one piece of a mod
   Fabric Loader runs early enough to still add mods.
2. Each jar in `isotope/mods` is unpacked (including jars bundled inside it)
   and rewritten one version step at a time: bytecode, lambdas, the strings
   mixins use to name their targets, access wideners.
3. The result is **link-checked against your actual game**: every reference is
   resolved the way the JVM would resolve it. Leftovers go in the report. Calls
   that only need a different instruction because a class became an interface
   are repaired. Problems that are certain to crash (see above) keep the mod
   from loading.
4. The mod's Minecraft version pin is removed, the jar is written to
   `isotope/cache`, and it is registered with Fabric Loader as a normal mod.

The original jar is never modified.

### How much actually changes between versions

Measured from Mojang's client jars by `DiffGen`:

| Step | Classes | Went missing | Auto-redirected |
|---|---|---|---|
| 26.1.2 → 26.2 | 10,682 → 10,952 | 284 classes, 1,162 methods, 1,728 fields | 188 classes, 89 methods, 23 fields |
| 26.2 → 26.3 | 10,952 → 11,383 | 501 classes, 2,869 methods, 1,209 fields | 215 classes, 171 methods, 31 fields |

Two things follow. The overwhelming majority of the game's API does not change
between releases, which is why many small mods need nothing but their version
pin removed. And most of what does change is a real redesign, not a rename,
which no table can paper over.

## What it cannot do

Be clear-eyed about this; it is a compatibility layer, not a port.

- **Redesigned systems.** 26.3 rewrote world-generation features, removed
  per-block codecs and reworked loot tables. A mod built on the old design
  calls things that have no equivalent. Isotope reports those; it cannot
  invent them. World-generation mods crossing 26.2 → 26.3 will generally not
  work.
- **Changed behaviour.** If a method kept its name but now does something
  different, nothing looks wrong to Isotope. Only testing shows it.
- **Data files.** Recipes, loot tables, worldgen JSON and similar are not
  migrated yet. 26.3 renamed several JSON keys; old mods' data may be skipped
  by the game with errors in the log.
- **Mixins into changed code.** Isotope renames what a mixin points at and
  checks the target method exists. It cannot tell whether the exact spot
  inside that method still exists. With `lenient-mixins` on, a hook that
  misses is skipped.
- **Overrides of re-signed methods.** If the game added a parameter to a
  method your mod overrides, the override silently stops being called. These
  show up in the report as "abstract method the mod does not implement" when
  the method is abstract, and not at all when it is not.
- **Pre-26 versions.** 1.21.x and earlier are obfuscated and use a different
  toolchain. Out of scope.
- **Only tested on a server so far.** Client-only code (rendering, screens,
  client mixins) of rewritten mods is rewritten and link-checked the same
  way, but has not been exercised in a real client run.
- **Startup-crash prediction is partial.** It follows the mod's initializer,
  static initializers and the overrides of classes the mod creates. It does
  not follow event callbacks or reflection, so a mod can pass and still crash
  at startup.

### Real-world results so far

Twelve Fabric mods from Modrinth whose newest build is for 26.1.x, put in
`isotope/mods` on a 26.3 dedicated server (with Fabric API, Cloth Config,
Cardinal Components and Forge Config API Port installed normally):

| Result | Mods |
|---|---|
| Loaded, server reached `Done` | Too Fast, Fishing Real, Nyf's Spiders, Patchouli |
| Not loaded: predicted startup crash | Carved Wood, Thermoo |
| Not loaded: class changed shape | YUNG's API, YUNG's Better Dungeons, Artifacts, Frostiful |
| Not loaded: needs a mod that was not loaded | Paxi (needs YUNG's API) |
| Not tested | Fabric Waystones (needs owo-lib, which has no 26.3 build) |

With the check turned off, Carved Wood, Thermoo, Artifacts and YUNG's API
all really do crash the server at startup (YUNG's API on an
`AbstractMethodError`, before it even reaches the class flagged as changed).
Frostiful was not crash-tested: it also needs YACL, which was not installed.
"Loaded" means the server starts; client-side features of those mods are
untested. Expect small, focused mods to work and big content,
world-generation or rendering mods to need a real port.

## Other projects in this space

- **[Retromod](https://modrinth.com/mod/retromod)** does the closest thing:
  it rewrites old mods' bytecode so they load on current Minecraft, covers a
  much wider range of versions (1.14+ Fabric and other loaders) and is far more
  established. If Retromod handles your mod, use it. Isotope differs in being
  two-way, in deriving its rules from the game jars instead of hand-written
  tables, and in the link-check report.
- **[RetroMod (Bownlux)](https://github.com/Bownlux/RetroMod)** is a separate
  project with a similar approach, targeting 1.12.2 through 26.1.
- **Sinytra Connector** and **Kilt** cross *loaders* (Fabric ↔ NeoForge /
  Forge), not game versions.
- **ViaFabricPlus / ViaVersion** cross *network protocol* versions (joining
  servers on another version). They do not run mods from another version.
- Fabric Loader's own **dependency overrides**
  (`config/fabric_loader_dependencies.json`) remove a version pin and nothing
  else. That is all a mod needs when it touches nothing that changed.

## Building

Java 25, Gradle wrapper included.

```
./gradlew clean build          # jar in build/libs/
./gradlew test                 # unit tests for the rewrite engine
```

Regenerating the tables (needs `curl` and `python3`; Mojang's jars are
downloaded to a folder you choose and are never committed):

```
tools/fetch-jars.sh /path/to/jars
tools/regen.sh /path/to/jars
```

Checking mods without starting the game:

```
java -cp "$(./gradlew -q printDiffgenClasspath | tail -1)" com.isotope.tools.Cli \
    --game /path/to/jars/26.3-client.jar --target 26.3 \
    --lib fabric-api.jar --out /tmp/out  somemod.jar
```

## Layout

| Path | What |
|---|---|
| `src/main/java/com/isotope/boot/` | Early hook, launch orchestration, the one file that touches Fabric Loader internals |
| `src/main/java/com/isotope/rewrite/` | Jar unpacking, per-step rule lookup, bytecode / mixin / access-widener rewriting |
| `src/main/java/com/isotope/link/` | Link check against the running game, mixin check, the report |
| `src/main/java/com/isotope/model/` | Redirect tables, per-version class hierarchy, version graph |
| `src/main/java/com/isotope/tools/` | `DiffGen` (table generator) and `Cli` (offline runner) |
| `src/main/resources/isotope/` | Generated tables (`.redirects`), hand-written additions (`.manual`), hierarchy files |
| `branding/` | Icon source |

## Brand

Violet `#B79CF0` tile, white `#FEFEFE` atom with a two-particle nucleus: one
solid, one hollow (the extra neutron). Same recipe as Chlorine's icon, in a
different hue. Source: `branding/icon.svg`.

## Licence

All rights reserved; see `LICENSE`. Minecraft is a trademark of Mojang AB.
Isotope is not affiliated with Mojang or Microsoft, and contains none of the
game's code: the tables hold names only.
