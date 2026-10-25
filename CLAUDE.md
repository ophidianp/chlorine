# Chlorine – instructions for Claude Code

Chlorine is a Fabric performance / power-management mod for Minecraft 26.x
(Java 25, mojmap, no remap). Most of the code is AI-assisted; the maintainer
tests every build and wants to learn the code, so explain non-obvious changes.

## Branches (important)
- `main` = the MOD source only.
- `ophidianp-chlorine-website` = the WEBSITE only (plain static HTML/CSS/JS at
  the repo root, deployed to GitHub Pages at https://ophidianp.github.io/chlorine/).
- Website changes go on `ophidianp-chlorine-website`. Mod changes go on `main`.
  Never mix them in one commit. Never create extra feature branches unless asked.
- `main` currently contains a stale `chlorine-website/` folder and its own
  `.github/workflows/pages.yml`. Do NOT edit or "sync" that folder; it is
  outdated. Ask before deleting it.

## Mod layout (on main)
- Version folders: `1.0.0-26.2/` (the last Minecraft 26.2 build),
  `1.0.2-26.3/` (an older 26.3 build, kept for reference) and
  `1.0.6-26.3/` (the current source; Minecraft 26.3, version 1.0.6). The
  newest folder is named after the release it holds, so rename it when the
  version changes. The release number is `mod_version` in that folder's
  `gradle.properties`; confirm the intended number before tagging.
- The repo-root `src/` / `gradle.properties` are the older 26.2 / 1.0.0
  setup. Verify before editing; do not assume root is current.
- Each folder builds on its own: `cd <folder> && ./gradlew build` (Java 25,
  Gradle 9.x). On Windows set `JAVA_HOME` to a Java 25+ JDK first.
- Mod ID/package: `com.chlorine`. Mixins live in `mixin/` and
  `client/mixin/` or `mixin/client/`; parked ones in `disabled-mixins/`.

## Rules for 26.x modding
- Mojmap, unobfuscated, NO remap. Use plugin `net.fabricmc.fabric-loom`; never
  add `remapJar`, yarn or intermediary. Dependencies use `implementation`.
- Always `clean compileJava`; a shrinking error count without clean is unreliable.
- For any API change in a new MC version, derive it with `javap` against the
  target jar; do not guess.
- Validation order: clean compile -> dedicated-server boot to `Done` (grep log
  for `FAILED during APPLY` / `was not applied`) -> real client run.
  Client-side mixins (sound, particle, beacon, portal, Sodium governor) are NOT
  exercised by a server boot, so say clearly when a client test is still needed.
- Sodium-dependent code (1.0.3+) must keep `sodium >=0.9.2` in fabric.mod.json
  and stay isolated in `chlorine.sodium.mixins.json`.
- Prefer MixinExtras (`@WrapOperation`, `@ModifyExpressionValue`) over
  `@Redirect` / `@ModifyConstant` to stay compatible with Sodium, Lithium,
  FerriteCore, EntityCulling, ImmediatelyFast, Iris.
- Change simulation distance, never render distance (avoids chunk graph rebuilds).

## Releases and the website download page
- `assets/releases.js` on the website branch reads GitHub Releases and matches
  each release to a game version by searching its name, tag, or BODY for
  "Minecraft 26.2" or "Minecraft 26.3". Every release body MUST contain that
  phrase and attach the mod `.jar` (not `-sources.jar`).
- If you change `releases.js`, `site.css` or `site.js`, bump the `?v=` cache
  key on the matching `<script>`/`<link>` tags in every HTML page.
- 26.3 builds need Sodium 0.9.2+; 26.2 builds do not.

## Pages deployment
- Only the website branch should deploy Pages (source = GitHub Actions). Its
  workflow uploads the repo root. If a deploy fails, check that the Pages
  source setting is "GitHub Actions" before changing the workflow.

## Git etiquette
- Small, focused commits with clear messages. Run `git status` and `git diff`
  before committing. Never force-push. Never rewrite published history.
- Keep README.md "Current release" and the website text in sync with the
  version actually released.
