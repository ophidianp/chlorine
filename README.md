# Chlorine Website

This branch hosts the Chlorine website, deployed via GitHub Pages at the repository root.

## Pages
- **Home** — `index.html`
- **Features** — `features.html`
- **Download** — `downloads.html` (live GitHub Releases, Minecraft 26.2 / 26.3)
- **Benchmarks** — `benchmarks.html`
- **404** — `404.html`

The design follows the Mouse 33 site: a floating header, an oversized hero, scroll
reveals, a pinned scroll stage on the home page, light and dark themes with a toggle,
and Chlorine's own sky-blue accent. Shared assets: `assets/site.css` (all styles),
`assets/site.js` (theme toggle, header and mobile menu, pinned stage, FAQ, first-visit
preloader) and `assets/releases.js` (live download data). Pages are plain HTML with no build step.

## Logo

The "chlorine" wordmark is drawn by `brand/logo_gen.py`: a flat pen nib swept along
Bezier centrelines, unioned and slanted 44°, the same technique as the Mouse 33 logo.
`python -I brand/make_assets.py` rewrites `brand/logo.svg`, `brand/logo.json` (the
path each page inlines as `<symbol id="cl">`), `favicon.svg`, `apple-touch-icon.png`
and `og.png`. It needs numpy, shapely and Pillow.

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

The mod source code lives on the `main` branch â€” switch branches in the repo selector to view it.
