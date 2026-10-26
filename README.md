# Chlorine Website

This branch hosts the Chlorine website, deployed via GitHub Pages at the repository root.

## Pages
- **Home** — `index.html`
- **Features** — `features.html`
- **Download** — `downloads.html` (live GitHub Releases, Minecraft 26.2 / 26.3)
- **Benchmarks** — `benchmarks.html`
- **404** — `404.html`

Shared assets: `assets/site.css` (styles and motion), `assets/site.js` (theme,
navigation, scroll reveals), `assets/demos.js` (the interactive feature demos
on Home and Features) and `assets/releases.js` (live download data).

The current Minecraft 26.3 / Chlorine 1.0.8 release requires Sodium 0.9.2
or newer. The site describes the Sodium-aware workload governor, which uses
Sodium's existing builder queue and worker metrics to protect frame pacing
under sustained chunk-build pressure. Other companion performance mods remain
optional.

## Benchmarks

Sampled Minecraft FPS comparisons, tested at 12-chunk render distance on an AMD Ryzen 5 5500, Radeon RX 6500 XT, 16 GB DDR4.

| Setup | Avg FPS | Low | High |
|-------|---------|-----|------|
| Vanilla | 326.5 | 156 | 486 |
| Only Chlorine | 333.6 | 284 | 381 |
| Chlorine + performance mods | 531.6 | 152 | 1,162 |

These are sampled readings from recordings, not a full frame-time capture.

The mod source code lives on the `main` branch — switch branches in the repo selector to view it.
