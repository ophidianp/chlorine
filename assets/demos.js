/*
 * Interactive illustrations of Chlorine's features. Each one is a small
 * canvas model of the idea behind a setting, not a measurement of the mod.
 * Any element with data-demo="<name>" becomes a demo; the hero canvas uses
 * data-hero. Demos only animate while on screen.
 *
 * Wrapped in a function so its names don't collide with site.js, since
 * classic scripts share one global scope.
 */
(() => {

const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
let palette = {};

function readPalette() {
  const style = getComputedStyle(document.documentElement);
  const get = (name) => style.getPropertyValue(name).trim();
  palette = {
    bg: get("--bg-2"), surface: get("--surface"), ink: get("--ink"), muted: get("--muted"), faint: get("--faint"),
    line: get("--line"), lineStrong: get("--line-strong"), accent: get("--accent"), accent2: get("--accent-2"),
    warn: get("--warn"), bad: get("--bad"), good: get("--good"),
    light: document.documentElement.dataset.theme === "light",
  };
}
readPalette();
window.addEventListener("chlorine:theme", readPalette);

function alpha(color, a) {
  if (color.startsWith("#")) {
    const n = parseInt(color.slice(1), 16);
    return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
  }
  return color;
}

const rand = (min, max) => min + Math.random() * (max - min);
const clamp = (v, min, max) => Math.max(min, Math.min(max, v));
const lerp = (a, b, t) => a + (b - a) * t;

/* ---------- Shared canvas + loop plumbing ---------- */
function attachCanvas(canvas) {
  const ctx = canvas.getContext("2d");
  const view = { ctx, w: 0, h: 0, dpr: 1 };
  const resize = () => {
    const rect = canvas.getBoundingClientRect();
    view.dpr = Math.min(window.devicePixelRatio || 1, 2);
    view.w = rect.width;
    view.h = rect.height;
    canvas.width = Math.max(1, Math.round(rect.width * view.dpr));
    canvas.height = Math.max(1, Math.round(rect.height * view.dpr));
    ctx.setTransform(view.dpr, 0, 0, view.dpr, 0, 0);
  };
  resize();
  if ("ResizeObserver" in window) new ResizeObserver(resize).observe(canvas);
  else window.addEventListener("resize", resize);
  return view;
}

function runWhileVisible(element, frame) {
  let visible = false;
  let last = 0;
  let raf = 0;
  const loop = (now) => {
    const dt = Math.min(0.05, (now - last) / 1000 || 0.016);
    last = now;
    frame(dt, now / 1000);
    raf = requestAnimationFrame(loop);
  };
  const update = () => {
    const shouldRun = visible && !document.hidden && element.offsetParent !== null;
    if (shouldRun && !raf) { last = performance.now(); raf = requestAnimationFrame(loop); }
    if (!shouldRun && raf) { cancelAnimationFrame(raf); raf = 0; }
  };
  new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; update(); }).observe(element);
  document.addEventListener("visibilitychange", update);
  window.addEventListener("resize", update);
  // Tab panels toggle `hidden`, which IntersectionObserver reports, but poll cheaply as a fallback.
  setInterval(update, 500);
}

function pointerOn(canvas, view) {
  const pointer = { x: 0, y: 0, active: false, down: false };
  const set = (event) => {
    const rect = canvas.getBoundingClientRect();
    pointer.x = event.clientX - rect.left;
    pointer.y = event.clientY - rect.top;
  };
  canvas.addEventListener("pointermove", (event) => { set(event); pointer.active = true; });
  canvas.addEventListener("pointerdown", (event) => { set(event); pointer.active = true; pointer.down = true; });
  canvas.addEventListener("pointerup", () => { pointer.down = false; });
  canvas.addEventListener("pointerleave", () => { pointer.active = false; pointer.down = false; });
  return pointer;
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.roundRect ? ctx.roundRect(x, y, w, h, r) : ctx.rect(x, y, w, h);
}

/* Builds the demo chrome (header, canvas, controls, stats) inside `host`. */
function buildDemo(host, options) {
  host.classList.add("demo");
  host.innerHTML = `
    <div class="demo-head">
      <div><div class="demo-title">${options.title}</div><p class="demo-sub">${options.sub}</p></div>
    </div>
    <div class="demo-canvas${options.tall ? " tall" : ""}">
      <canvas aria-hidden="true"></canvas>
      ${options.label ? `<span class="overlay-label">${options.label}</span>` : ""}
    </div>
    <div class="demo-foot">
      ${options.controls || ""}
      <div class="stats" aria-live="polite">
        ${(options.stats || []).map(([key, label]) => `<div class="stat">${label}<b data-stat="${key}">–</b></div>`).join("")}
      </div>
    </div>`;
  const canvas = host.querySelector("canvas");
  const view = attachCanvas(canvas);
  const stats = {};
  host.querySelectorAll("[data-stat]").forEach((element) => { stats[element.dataset.stat] = element; });
  host.querySelectorAll('input[type="range"]').forEach((input) => {
    const out = host.querySelector(`output[for="${input.id}"]`);
    const sync = () => {
      input.style.setProperty("--fill", `${((input.value - input.min) / (input.max - input.min)) * 100}%`);
      if (out) out.textContent = (input.dataset.format || "{}").replace("{}", input.value);
    };
    input.addEventListener("input", sync);
    sync();
  });
  const lastText = {};
  return {
    canvas, view,
    q: (selector) => host.querySelector(selector),
    stat(key, text, tone = "") {
      const element = stats[key];
      if (!element || lastText[key] === text + tone) return;
      lastText[key] = text + tone;
      element.textContent = text;
      element.className = tone;
    },
  };
}

let uid = 0;
const switchHtml = (label, checked = true) => {
  const id = `sw${++uid}`;
  return `<label class="switch" for="${id}"><input type="checkbox" id="${id}" data-role="toggle" ${checked ? "checked" : ""}><span class="track"></span>${label}</label>`;
};
const rangeHtml = (label, min, max, value, format = "{}", role = "range") => {
  const id = `rg${++uid}`;
  return `<label class="range" for="${id}">${label}<input type="range" id="${id}" data-role="${role}" min="${min}" max="${max}" value="${value}" data-format="${format}"><output for="${id}"></output></label>`;
};
const buttonHtml = (label, role = "action") => `<button class="btn btn-ghost btn-sm" type="button" data-role="${role}">${label}</button>`;

/* ---------- Hero: simulation distance around the player ---------- */
function heroScene(canvas) {
  const view = attachCanvas(canvas);
  const pointer = pointerOn(canvas, view);
  const readFps = document.querySelector("[data-hero-fps]");
  const readSim = document.querySelector("[data-hero-sim]");
  const grid = 27;
  let px = 0, py = 0, radius = 8, targetRadius = 8, phase = 0, fps = 144, lastLabel = 0;

  runWhileVisible(canvas, (dt, t) => {
    const { ctx, w, h } = view;
    const cell = Math.min(w, h) / grid;
    const ox = (w - cell * grid) / 2;
    const oy = (h - cell * grid) / 2;

    // A slow "busy moment" cycle: frames dip, Chlorine trims simulation distance, then recovers.
    phase = (phase + dt / 14) % 1;
    const busy = Math.sin(phase * Math.PI * 2) > 0.35;
    targetRadius = busy ? 5 : 8;
    radius = lerp(radius, targetRadius, 1 - Math.exp(-dt * 0.9));
    const fpsTarget = busy ? 68 + (8 - radius) * 22 : 144;
    fps = lerp(fps, fpsTarget, 1 - Math.exp(-dt * 1.6));

    const tx = pointer.active ? clamp(Math.round((pointer.x - ox) / cell - grid / 2 + 0.5), -4, 4) : Math.sin(t * 0.25) * 2.2;
    const ty = pointer.active ? clamp(Math.round((pointer.y - oy) / cell - grid / 2 + 0.5), -4, 4) : Math.cos(t * 0.19) * 2.2;
    const follow = 1 - Math.exp(-dt * (pointer.active ? 4 : 0.8));
    px = lerp(px, tx, follow);
    py = lerp(py, ty, follow);

    ctx.clearRect(0, 0, w, h);
    const cx = grid / 2 + px;
    const cy = grid / 2 + py;
    const ripple = (t * 2.4) % (radius + 6);

    for (let gy = 0; gy < grid; gy++) {
      for (let gx = 0; gx < grid; gx++) {
        const dx = gx + 0.5 - cx;
        const dy = gy + 0.5 - cy;
        const edge = Math.hypot(gx + 0.5 - grid / 2, gy + 0.5 - grid / 2) / (grid / 2);
        if (edge > 1) continue;
        const fade = 1 - Math.pow(edge, 3);
        const cheb = Math.max(Math.abs(dx), Math.abs(dy));
        const inside = cheb <= radius;
        const wave = Math.max(0, 1 - Math.abs(cheb - ripple) / 1.2);
        const x = ox + gx * cell + cell * 0.12;
        const y = oy + gy * cell + cell * 0.12;
        const size = cell * 0.76;
        roundRect(ctx, x, y, size, size, cell * 0.18);
        if (inside) {
          const a = (0.1 + 0.22 * (1 - cheb / (radius + 1)) + wave * 0.35) * fade;
          ctx.fillStyle = alpha(palette.accent, a);
          ctx.fill();
        } else {
          ctx.fillStyle = alpha(palette.light ? "#14284f" : "#96afd7", (0.05 + wave * 0.05) * fade);
          ctx.fill();
        }
      }
    }

    // Simulation boundary
    const half = (radius + 0.5) * cell;
    const bx = ox + cx * cell;
    const by = oy + cy * cell;
    ctx.save();
    ctx.setLineDash([6, 8]);
    ctx.lineDashOffset = -t * 18;
    ctx.strokeStyle = alpha(palette.accent, 0.75);
    ctx.lineWidth = 1.5;
    roundRect(ctx, bx - half, by - half, half * 2, half * 2, cell * 0.4);
    ctx.stroke();
    ctx.restore();

    if (t - lastLabel > 0.25) {
      lastLabel = t;
      if (readFps) readFps.textContent = `${Math.round(fps)} FPS`;
      if (readSim) readSim.textContent = `${Math.round(radius * 1.5)} chunks`;
    }
  });
}

/* ---------- 1. Adaptive simulation distance ---------- */
function demoAdaptive(host) {
  const demo = buildDemo(host, {
    title: "Adaptive simulation distance",
    sub: "Push the world load up. Chlorine trims simulation distance when FPS stays under target, then restores it.",
    controls: switchHtml("Chlorine") + rangeHtml("World load", 0, 100, 45, "{}%"),
    stats: [["fps", "FPS"], ["sim", "Sim distance"], ["state", "Status"]],
    label: "Simulation distance · FPS",
  });
  const toggle = demo.q('[data-role="toggle"]');
  const load = demo.q('[data-role="range"]');
  const history = new Array(220).fill(144);
  const threshold = 60;
  let sim = 12, shown = 12, fps = 144, below = 0, above = 0, state = "Stable", acc = 0;

  runWhileVisible(host, (dt, t) => {
    const { ctx, w, h } = demo.view;
    const on = toggle.checked;
    const l = load.value / 100;
    const cost = Math.pow(l, 1.4) * Math.pow(sim / 12, 2);
    const target = 28 + 210 / (1 + cost * 7.5) + Math.sin(t * 3.1) * 4 + Math.sin(t * 7.7) * 3;
    fps = lerp(fps, target, 1 - Math.exp(-dt * 3));

    acc += dt;
    if (acc > 0.05) {
      acc = 0;
      history.push(fps);
      history.shift();
      const recent = history.slice(-20).reduce((a, b) => a + b, 0) / 20;
      if (!on) {
        sim = 12; state = "Fixed"; below = above = 0;
      } else if (recent < threshold) {
        above = 0; below += 0.05;
        state = sim > 5 ? "Scaling down" : "At floor";
        if (below > 0.8 && sim > 5) { sim--; below = 0; }
      } else if (recent > threshold + 30 && sim < 12) {
        below = 0; above += 0.05; state = "Recovering";
        if (above > 2.2) { sim++; above = 0; }
      } else {
        below = above = 0; state = "Stable";
      }
    }
    shown = lerp(shown, sim, 1 - Math.exp(-dt * 5));

    ctx.clearRect(0, 0, w, h);
    const pad = 18;
    const narrow = w < 560;
    const gridSize = narrow ? Math.min(w - pad * 2, h * 0.5 - pad) : Math.min(h - pad * 2 - 14, w * 0.42);
    const gx0 = narrow ? (w - gridSize) / 2 : pad;
    const gy0 = narrow ? pad + 14 : (h - gridSize) / 2 + 7;
    const n = 31;
    const mid = (n - 1) / 2;
    const cell = gridSize / n;
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const d = Math.max(Math.abs(x - mid), Math.abs(y - mid));
        const inside = d <= shown;
        roundRect(ctx, gx0 + x * cell + 1, gy0 + y * cell + 1, cell - 2, cell - 2, 2);
        ctx.fillStyle = inside ? alpha(palette.accent, 0.16 + 0.4 * (1 - d / 13)) : alpha(palette.light ? "#14284f" : "#96afd7", 0.06);
        ctx.fill();
      }
    }
    ctx.fillStyle = palette.ink;
    ctx.beginPath();
    ctx.arc(gx0 + (mid + 0.5) * cell, gy0 + (mid + 0.5) * cell, Math.max(3, cell * 0.38), 0, Math.PI * 2);
    ctx.fill();

    // FPS chart
    const cx0 = narrow ? pad : gx0 + gridSize + 28;
    const cy0 = narrow ? gy0 + gridSize + 16 : pad + 14;
    const cw = w - cx0 - pad;
    const ch = narrow ? h - cy0 - pad : h - cy0 - pad;
    const max = 260;
    const yOf = (v) => cy0 + ch - (clamp(v, 0, max) / max) * ch;
    ctx.strokeStyle = palette.line;
    ctx.lineWidth = 1;
    roundRect(ctx, cx0, cy0, cw, ch, 10);
    ctx.stroke();
    ctx.save();
    ctx.setLineDash([4, 6]);
    ctx.strokeStyle = alpha(palette.warn, 0.8);
    ctx.beginPath();
    ctx.moveTo(cx0, yOf(threshold));
    ctx.lineTo(cx0 + cw, yOf(threshold));
    ctx.stroke();
    ctx.restore();
    ctx.fillStyle = palette.warn;
    ctx.font = "11px 'DM Mono', monospace";
    ctx.fillText(`target ${threshold}`, cx0 + 8, yOf(threshold) - 6);

    const step = cw / (history.length - 1);
    const gradient = ctx.createLinearGradient(0, cy0, 0, cy0 + ch);
    gradient.addColorStop(0, alpha(palette.accent, 0.35));
    gradient.addColorStop(1, alpha(palette.accent, 0));
    ctx.beginPath();
    history.forEach((v, i) => (i ? ctx.lineTo(cx0 + i * step, yOf(v)) : ctx.moveTo(cx0, yOf(v))));
    ctx.lineTo(cx0 + cw, cy0 + ch);
    ctx.lineTo(cx0, cy0 + ch);
    ctx.closePath();
    ctx.fillStyle = gradient;
    ctx.fill();
    ctx.beginPath();
    history.forEach((v, i) => (i ? ctx.lineTo(cx0 + i * step, yOf(v)) : ctx.moveTo(cx0, yOf(v))));
    ctx.strokeStyle = palette.accent;
    ctx.lineWidth = 2;
    ctx.stroke();

    demo.stat("fps", String(Math.round(fps)), fps < threshold ? "bad" : fps < threshold + 30 ? "warn" : "good");
    demo.stat("sim", `${sim} chunks`);
    demo.stat("state", state, state === "Scaling down" ? "warn" : state === "Recovering" ? "good" : "");
  });
}

/* ---------- 2. Particle budget ---------- */
function demoParticles(host) {
  const demo = buildDemo(host, {
    title: "Particle budget",
    sub: "Launch fireworks. With the budget on, each tick can only create so many particles; the rest are skipped.",
    controls: switchHtml("Particle budget") + buttonHtml("Launch fireworks"),
    stats: [["live", "Live particles"], ["dropped", "Skipped"], ["frame", "Frame time"]],
    label: "Simulated frame time below",
  });
  const toggle = demo.q('[data-role="toggle"]');
  const pointer = pointerOn(demo.canvas, demo.view);
  const particles = [];
  const rockets = [];
  const frames = new Array(140).fill(6);
  const budgetPerTick = 140;
  let pending = 0, idle = 0, dropped = 0, frameMs = 6, spawnedThisFrame = 0;

  const launch = (x) => {
    const { w, h } = demo.view;
    rockets.push({ x: x ?? rand(w * 0.2, w * 0.8), y: h - 70, vy: -rand(320, 420), targetY: rand(h * 0.15, h * 0.42), hue: Math.random() });
  };
  demo.q('[data-role="action"]').addEventListener("click", () => { launch(); launch(); launch(); idle = 0; });
  demo.canvas.addEventListener("pointerdown", () => { launch(pointer.x); idle = 0; });

  const explode = (rocket) => {
    const count = 420;
    let allowed = count;
    if (toggle.checked) {
      allowed = Math.max(0, Math.min(count, budgetPerTick - pending));
      dropped += count - allowed;
    }
    pending += allowed;
    spawnedThisFrame += allowed;
    const colors = [palette.accent, palette.accent2, palette.light ? "#7b6cf0" : "#c8b8ff", palette.warn];
    const color = colors[Math.floor(rocket.hue * colors.length)];
    for (let i = 0; i < allowed; i++) {
      const angle = rand(0, Math.PI * 2);
      const speed = rand(30, 230);
      particles.push({ x: rocket.x, y: rocket.y, vx: Math.cos(angle) * speed, vy: Math.sin(angle) * speed, life: rand(1.2, 2.2), age: 0, color });
    }
  };

  let tickAcc = 0;
  runWhileVisible(host, (dt) => {
    const { ctx, w, h } = demo.view;
    idle += dt;
    if (idle > 2.4 && !reduceMotion) { launch(); idle = rand(0, 1.2); }
    tickAcc += dt;
    while (tickAcc > 0.05) { tickAcc -= 0.05; pending = 0; }

    ctx.clearRect(0, 0, w, h);
    for (let i = rockets.length - 1; i >= 0; i--) {
      const r = rockets[i];
      r.y += r.vy * dt;
      ctx.fillStyle = palette.ink;
      ctx.fillRect(r.x - 1, r.y, 2, 8);
      if (r.y <= r.targetY) { explode(r); rockets.splice(i, 1); }
    }
    for (let i = particles.length - 1; i >= 0; i--) {
      const p = particles[i];
      p.age += dt;
      if (p.age > p.life) { particles.splice(i, 1); continue; }
      p.vx *= 1 - dt * 1.6;
      p.vy = p.vy * (1 - dt * 1.6) + 60 * dt;
      p.x += p.vx * dt;
      p.y += p.vy * dt;
      ctx.globalAlpha = 1 - p.age / p.life;
      ctx.fillStyle = p.color;
      ctx.fillRect(p.x - 1.25, p.y - 1.25, 2.5, 2.5);
    }
    ctx.globalAlpha = 1;

    // Simulated frame time: a base cost, plus live particles, plus a burst cost for new ones.
    const target = 6 + particles.length * 0.012 + spawnedThisFrame * 0.06;
    frameMs = Math.max(target, lerp(frameMs, target, 0.3));
    spawnedThisFrame = 0;
    frames.push(frameMs);
    frames.shift();

    const ch = 54, cy = h - ch - 10, cx = 14, cw = w - 28;
    const yOf = (v) => cy + ch - (clamp(v, 0, 40) / 40) * ch;
    ctx.strokeStyle = palette.line;
    roundRect(ctx, cx, cy, cw, ch, 8);
    ctx.stroke();
    ctx.save();
    ctx.setLineDash([3, 5]);
    ctx.strokeStyle = alpha(palette.warn, 0.7);
    ctx.beginPath(); ctx.moveTo(cx, yOf(16.7)); ctx.lineTo(cx + cw, yOf(16.7)); ctx.stroke();
    ctx.restore();
    const bw = cw / frames.length;
    frames.forEach((v, i) => {
      ctx.fillStyle = v > 16.7 ? palette.bad : alpha(palette.accent, 0.8);
      ctx.fillRect(cx + i * bw + 0.5, yOf(v), Math.max(1, bw - 1), cy + ch - yOf(v));
    });

    demo.stat("live", particles.length.toLocaleString());
    demo.stat("dropped", dropped.toLocaleString(), dropped ? "good" : "");
    demo.stat("frame", `${frameMs.toFixed(1)} ms`, frameMs > 16.7 ? "bad" : "good");
  });
  toggle.addEventListener("change", () => { dropped = 0; });
}

/* ---------- 3. Distant mob AI throttle ---------- */
function demoMobs(host) {
  const demo = buildDemo(host, {
    title: "Distant mob AI throttle",
    sub: "Move your pointer to steer the player. Far-away mobs think less often; every mob still moves every frame.",
    controls: switchHtml("AI throttle"),
    stats: [["thinks", "AI decisions / sec"], ["saved", "Work saved"]],
    label: "Rings flash when a mob makes a decision",
  });
  const toggle = demo.q('[data-role="toggle"]');
  const pointer = pointerOn(demo.canvas, demo.view);
  const mobs = Array.from({ length: 54 }, (_, i) => ({ id: i, x: Math.random(), y: Math.random(), a: rand(0, 6.28), flash: 0 }));
  const player = { x: 0.5, y: 0.5 };
  let tickAcc = 0, tickNo = 0, thinks = 0, windowStart = 0, rate = 0, fullRate = 0, full = 0;

  runWhileVisible(host, (dt, t) => {
    const { ctx, w, h } = demo.view;
    const tx = pointer.active ? pointer.x / w : 0.5 + Math.sin(t * 0.3) * 0.25;
    const ty = pointer.active ? pointer.y / h : 0.5 + Math.cos(t * 0.23) * 0.2;
    player.x = lerp(player.x, tx, 1 - Math.exp(-dt * 3));
    player.y = lerp(player.y, ty, 1 - Math.exp(-dt * 3));
    const near = Math.min(w, h) * 0.3;

    tickAcc += dt;
    while (tickAcc > 0.05) {
      tickAcc -= 0.05;
      tickNo++;
      mobs.forEach((m) => {
        const dist = Math.hypot((m.x - player.x) * w, (m.y - player.y) * h);
        full++;
        const thinksNow = !toggle.checked || dist < near || (tickNo + m.id) % 8 === 0;
        if (thinksNow && Math.random() < 0.35) {
          m.a += rand(-1.4, 1.4);
          m.flash = 1;
        }
        if (thinksNow) thinks++;
      });
    }
    if (t - windowStart > 1) {
      rate = thinks / (t - windowStart);
      fullRate = full / (t - windowStart);
      thinks = full = 0;
      windowStart = t;
    }

    ctx.clearRect(0, 0, w, h);
    const px = player.x * w, py = player.y * h;
    const g = ctx.createRadialGradient(px, py, 0, px, py, near);
    g.addColorStop(0, alpha(palette.accent, 0.16));
    g.addColorStop(1, alpha(palette.accent, 0));
    ctx.fillStyle = g;
    ctx.beginPath(); ctx.arc(px, py, near, 0, Math.PI * 2); ctx.fill();
    ctx.save();
    ctx.setLineDash([5, 7]);
    ctx.lineDashOffset = -t * 12;
    ctx.strokeStyle = alpha(palette.accent, 0.6);
    ctx.stroke();
    ctx.restore();

    mobs.forEach((m) => {
      m.x += (Math.cos(m.a) * 26 * dt) / w;
      m.y += (Math.sin(m.a) * 26 * dt) / h;
      if (m.x < 0.02 || m.x > 0.98) { m.a = Math.PI - m.a; m.x = clamp(m.x, 0.02, 0.98); }
      if (m.y < 0.03 || m.y > 0.97) { m.a = -m.a; m.y = clamp(m.y, 0.03, 0.97); }
      m.flash = Math.max(0, m.flash - dt * 2.2);
      const x = m.x * w, y = m.y * h;
      const close = Math.hypot(x - px, y - py) < near;
      if (m.flash > 0) {
        ctx.strokeStyle = alpha(close ? palette.accent : palette.warn, m.flash * 0.7);
        ctx.lineWidth = 1.5;
        ctx.beginPath(); ctx.arc(x, y, 6 + (1 - m.flash) * 12, 0, Math.PI * 2); ctx.stroke();
      }
      ctx.fillStyle = close ? palette.ink : alpha(palette.light ? "#14284f" : "#c9d6ea", 0.45);
      roundRect(ctx, x - 4, y - 4, 8, 8, 2);
      ctx.fill();
    });
    ctx.fillStyle = palette.accent;
    ctx.beginPath(); ctx.arc(px, py, 7, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = palette.surface; ctx.lineWidth = 2; ctx.stroke();

    demo.stat("thinks", Math.round(rate).toLocaleString());
    const saved = fullRate ? Math.max(0, 1 - rate / fullRate) : 0;
    demo.stat("saved", `${Math.round(saved * 100)}%`, saved > 0.2 ? "good" : "");
  });
}

/* ---------- 4. Item and XP merging ---------- */
function demoMerge(host) {
  const demo = buildDemo(host, {
    title: "Item & XP orb merging",
    sub: "Click or tap to drop loot. Matching drops that land close together are combined into one entity.",
    controls: switchHtml("Merging") + buttonHtml("Clear"),
    stats: [["entities", "Entities"], ["items", "Items held"], ["merges", "Merges"]],
  });
  const toggle = demo.q('[data-role="toggle"]');
  const pointer = pointerOn(demo.canvas, demo.view);
  let items = [];
  let merges = 0, idle = 1.5, mergeAcc = 0;
  const kinds = () => [palette.accent, palette.warn, palette.good, palette.light ? "#7b6cf0" : "#c8b8ff"];

  const drop = (x, y) => {
    const { w, h } = demo.view;
    x ??= rand(w * 0.15, w * 0.85);
    y ??= rand(h * 0.2, h * 0.8);
    for (let i = 0; i < 18; i++) {
      const a = rand(0, Math.PI * 2), s = rand(60, 280);
      items.push({ x, y, vx: Math.cos(a) * s, vy: Math.sin(a) * s, kind: Math.floor(rand(0, 4)), count: 1, born: 0, target: null, scale: 0 });
    }
    if (items.length > 500) items = items.slice(-500);
  };
  demo.canvas.addEventListener("pointerdown", () => { drop(pointer.x, pointer.y); idle = 0; });
  demo.q('[data-role="action"]').addEventListener("click", () => { items = []; merges = 0; });

  runWhileVisible(host, (dt) => {
    const { ctx, w, h } = demo.view;
    idle += dt;
    if (idle > 3.2 && items.length < 260 && !reduceMotion) { drop(); idle = 0; }

    mergeAcc += dt;
    if (toggle.checked && mergeAcc > 0.35) {
      mergeAcc = 0;
      for (const a of items) {
        if (a.target || a.born < 0.6) continue;
        for (const b of items) {
          if (b === a || b.target || b.kind !== a.kind || b.born < 0.6) continue;
          if (Math.hypot(a.x - b.x, a.y - b.y) < 34 && a.count >= b.count) { b.target = a; }
        }
      }
    }

    ctx.clearRect(0, 0, w, h);
    const colors = kinds();
    for (let i = items.length - 1; i >= 0; i--) {
      const it = items[i];
      it.born += dt;
      it.scale = lerp(it.scale, 1, 1 - Math.exp(-dt * 8));
      if (it.target) {
        it.x = lerp(it.x, it.target.x, 1 - Math.exp(-dt * 10));
        it.y = lerp(it.y, it.target.y, 1 - Math.exp(-dt * 10));
        if (Math.hypot(it.x - it.target.x, it.y - it.target.y) < 2 || !items.includes(it.target)) {
          if (items.includes(it.target)) { it.target.count += it.count; it.target.scale = 1.5; merges++; }
          items.splice(i, 1);
          continue;
        }
      } else {
        it.vx *= 1 - dt * 3.5; it.vy *= 1 - dt * 3.5;
        it.x = clamp(it.x + it.vx * dt, 8, w - 8);
        it.y = clamp(it.y + it.vy * dt, 8, h - 8);
      }
    }
    items.forEach((it) => {
      const size = (8 + Math.log2(it.count) * 3) * it.scale;
      ctx.fillStyle = colors[it.kind];
      ctx.globalAlpha = it.target ? 0.6 : 1;
      ctx.save();
      ctx.translate(it.x, it.y);
      ctx.rotate(Math.PI / 4);
      roundRect(ctx, -size / 2, -size / 2, size, size, 2);
      ctx.fill();
      ctx.restore();
      if (it.count > 1) {
        ctx.globalAlpha = 1;
        ctx.fillStyle = palette.ink;
        ctx.font = "600 11px 'DM Sans', sans-serif";
        ctx.fillText(String(it.count), it.x + size * 0.55, it.y + size * 0.7 + 4);
      }
    });
    ctx.globalAlpha = 1;
    const held = items.reduce((sum, it) => sum + it.count, 0);
    demo.stat("entities", items.length.toLocaleString(), items.length > 150 ? "warn" : "");
    demo.stat("items", held.toLocaleString());
    demo.stat("merges", merges.toLocaleString(), merges ? "good" : "");
  });
}

/* ---------- 5. Render LOD (signs, item drops, block entities) ---------- */
function demoLod(host) {
  const demo = buildDemo(host, {
    title: "Render LOD",
    sub: "Move the camera. Past the LOD distance, sign text is skipped, item stacks draw as one item, and small block entities are culled.",
    controls: switchHtml("Render LOD") + rangeHtml("LOD distance", 8, 48, 24, "{} blocks"),
    stats: [["work", "Render work"], ["skipped", "Details skipped"]],
    label: "Top-down view",
  });
  const toggle = demo.q('[data-role="toggle"]');
  const range = demo.q('[data-role="range"]');
  const pointer = pointerOn(demo.canvas, demo.view);
  const objects = Array.from({ length: 70 }, () => ({ x: Math.random(), y: Math.random(), type: Math.floor(rand(0, 3)), stack: Math.floor(rand(2, 6)), detail: 1 }));
  const cam = { x: 0.5, y: 0.6 };
  const blockPx = 4;

  runWhileVisible(host, (dt, t) => {
    const { ctx, w, h } = demo.view;
    const tx = pointer.active ? pointer.x / w : 0.5 + Math.sin(t * 0.2) * 0.3;
    const ty = pointer.active ? pointer.y / h : 0.5 + Math.cos(t * 0.27) * 0.25;
    cam.x = lerp(cam.x, tx, 1 - Math.exp(-dt * 3));
    cam.y = lerp(cam.y, ty, 1 - Math.exp(-dt * 3));
    const cx = cam.x * w, cy = cam.y * h;
    const radius = range.value * blockPx;
    const on = toggle.checked;

    ctx.clearRect(0, 0, w, h);
    if (on) {
      const g = ctx.createRadialGradient(cx, cy, radius * 0.6, cx, cy, radius);
      g.addColorStop(0, alpha(palette.accent, 0.07));
      g.addColorStop(1, alpha(palette.accent, 0.02));
      ctx.fillStyle = g;
      ctx.beginPath(); ctx.arc(cx, cy, radius, 0, Math.PI * 2); ctx.fill();
      ctx.save();
      ctx.setLineDash([5, 7]);
      ctx.lineDashOffset = -t * 12;
      ctx.strokeStyle = alpha(palette.accent, 0.7);
      ctx.lineWidth = 1.5;
      ctx.stroke();
      ctx.restore();
    }

    let work = 0, fullWork = 0, skipped = 0;
    const dim = alpha(palette.light ? "#14284f" : "#c9d6ea", 0.35);
    objects.forEach((o) => {
      const x = o.x * w, y = o.y * h;
      const far = on && Math.hypot(x - cx, y - cy) > radius;
      o.detail = lerp(o.detail, far ? 0 : 1, 1 - Math.exp(-dt * 4));
      const d = o.detail;
      if (o.type === 0) {
        // Sign: board + text lines
        fullWork += 7; work += 1 + 6 * (far ? 0 : 1); if (far) skipped++;
        roundRect(ctx, x - 13, y - 9, 26, 18, 3);
        ctx.fillStyle = palette.light ? "#c99a5b" : "#8a6a42";
        ctx.fill();
        ctx.fillStyle = alpha(palette.light ? "#3a2a12" : "#f3e5cc", 0.9 * d);
        [-4, 0, 4].forEach((ly, i) => ctx.fillRect(x - 9, y + ly - 1, [18, 14, 10][i], 1.6));
      } else if (o.type === 1) {
        // Item stack: several items, or just one when far
        fullWork += o.stack; work += far ? 1 : o.stack; if (far) skipped++;
        for (let i = o.stack - 1; i >= 0; i--) {
          const a = i === 0 ? 1 : d;
          if (a < 0.02) continue;
          ctx.globalAlpha = a;
          ctx.fillStyle = palette.accent2;
          roundRect(ctx, x - 5 + i * 3, y - 5 - i * 3, 10, 10, 2);
          ctx.fill();
          ctx.strokeStyle = palette.surface; ctx.lineWidth = 1; ctx.stroke();
        }
        ctx.globalAlpha = 1;
      } else {
        // Block entity (chest-like): drawn in full, or culled to a faint outline
        fullWork += 4; work += far ? 0 : 4; if (far) skipped++;
        ctx.globalAlpha = 0.25 + 0.75 * d;
        roundRect(ctx, x - 9, y - 8, 18, 16, 3);
        ctx.fillStyle = d > 0.5 ? (palette.light ? "#b07a2e" : "#a9772f") : "transparent";
        ctx.fill();
        if (d <= 0.5) { ctx.strokeStyle = dim; ctx.setLineDash([2, 3]); ctx.stroke(); ctx.setLineDash([]); }
        ctx.fillStyle = alpha("#000000", 0.25 * d);
        ctx.fillRect(x - 9, y - 2, 18, 1.5);
        ctx.globalAlpha = 1;
      }
    });

    ctx.fillStyle = palette.ink;
    ctx.beginPath();
    ctx.moveTo(cx, cy - 9); ctx.lineTo(cx + 7, cy + 7); ctx.lineTo(cx, cy + 3); ctx.lineTo(cx - 7, cy + 7);
    ctx.closePath(); ctx.fill();

    const pct = Math.round((work / fullWork) * 100);
    demo.stat("work", `${pct}%`, pct < 70 ? "good" : "");
    demo.stat("skipped", String(skipped));
  });
}

/* ---------- 6. Autosave smoothing ---------- */
function demoAutosave(host) {
  const demo = buildDemo(host, {
    title: "Autosave smoothing",
    sub: "Vanilla writes every changed chunk in one tick. Chlorine saves a few now and lets the background saver finish over the next ticks.",
    controls: buttonHtml("Trigger autosave"),
    stats: [["vanilla", "Worst tick · Vanilla"], ["chlorine", "Worst tick · Chlorine"]],
    tall: true,
  });
  const lanes = [
    { name: "VANILLA", ticks: new Array(70).fill(9), queue: 0, worst: 0, immediate: Infinity },
    { name: "CHLORINE", ticks: new Array(70).fill(9), queue: 0, worst: 0, immediate: 32 },
  ];
  const chunkMs = 0.32, unsaved = 180;
  let tickAcc = 0, idle = 0, progress = 0;

  const trigger = () => {
    lanes.forEach((lane) => {
      lane.worst = 0;
      const now = Math.min(unsaved, lane.immediate);
      lane.burst = now;
      lane.queue = unsaved - now;
    });
    idle = 0;
  };
  demo.q('[data-role="action"]').addEventListener("click", trigger);

  runWhileVisible(host, (dt) => {
    const { ctx, w, h } = demo.view;
    idle += dt;
    if (idle > 6 && !reduceMotion) trigger();
    tickAcc += dt;
    while (tickAcc > 0.09) {
      tickAcc -= 0.09;
      lanes.forEach((lane) => {
        let ms = 8 + Math.random() * 3;
        if (lane.burst) { ms += lane.burst * chunkMs; lane.burst = 0; }
        else if (lane.queue > 0) { const n = Math.min(20, lane.queue); lane.queue -= n; ms += n * chunkMs; }
        lane.worst = Math.max(lane.worst, ms);
        lane.ticks.push(ms);
        lane.ticks.shift();
      });
    }
    progress = tickAcc / 0.09;

    ctx.clearRect(0, 0, w, h);
    const pad = 16, gap = 18;
    const laneH = (h - pad * 2 - gap) / 2;
    const max = 70;
    lanes.forEach((lane, li) => {
      const y0 = pad + li * (laneH + gap);
      ctx.strokeStyle = palette.line;
      ctx.lineWidth = 1;
      roundRect(ctx, pad, y0, w - pad * 2, laneH, 12);
      ctx.stroke();
      const yOf = (v) => y0 + laneH - 8 - (clamp(v, 0, max) / max) * (laneH - 30);
      ctx.save();
      ctx.setLineDash([4, 6]);
      ctx.strokeStyle = alpha(palette.warn, 0.7);
      ctx.beginPath(); ctx.moveTo(pad, yOf(50)); ctx.lineTo(w - pad, yOf(50)); ctx.stroke();
      ctx.restore();
      ctx.font = "11px 'DM Mono', monospace";
      ctx.fillStyle = palette.faint;
      ctx.fillText(lane.name, pad + 12, y0 + 18);
      ctx.fillStyle = palette.warn;
      ctx.fillText("50 ms tick budget", w - pad - 118, yOf(50) - 6);
      const inner = w - pad * 2 - 16;
      const bw = inner / (lane.ticks.length - 1);
      lane.ticks.forEach((v, i) => {
        const x = pad + 8 + (i - progress) * bw;
        if (x < pad + 4) return;
        ctx.fillStyle = v > 50 ? palette.bad : v > 16 ? alpha(palette.warn, 0.9) : alpha(palette.accent, 0.75);
        roundRect(ctx, x, yOf(v), Math.max(2, bw - 3), y0 + laneH - 8 - yOf(v), 2);
        ctx.fill();
      });
    });
    const worst = (lane) => (lane.worst ? `${Math.round(lane.worst)} ms` : "–");
    demo.stat("vanilla", worst(lanes[0]), lanes[0].worst > 50 ? "bad" : "");
    demo.stat("chlorine", worst(lanes[1]), lanes[1].worst && lanes[1].worst < 50 ? "good" : "");
  });
}

/* ---------- 7. Unfocused-window power saver ---------- */
function demoPower(host) {
  const demo = buildDemo(host, {
    title: "Unfocused-window power saver",
    sub: "Click outside this browser window (or flip the switch). The scene drops to a low FPS cap and hides clouds until you come back.",
    controls: switchHtml("Simulate unfocused", false) + switchHtml("Hide clouds", true).replace('data-role="toggle"', 'data-role="clouds"'),
    stats: [["window", "Window"], ["fps", "FPS"], ["power", "GPU work"]],
  });
  const simulate = demo.q('[data-role="toggle"]');
  const hideClouds = demo.q('[data-role="clouds"]');
  let focused = document.hasFocus();
  window.addEventListener("blur", () => { focused = false; });
  window.addEventListener("focus", () => { focused = true; });
  // Soft, layered landscape that pans slowly, so a low frame cap reads as visibly choppier motion.
  const clouds = Array.from({ length: 6 }, (_, i) => ({ x: i / 6 + rand(0, 0.1), y: rand(0.12, 0.38), s: rand(0.7, 1.25), v: rand(0.006, 0.014) }));
  const stars = Array.from({ length: 60 }, () => ({ x: Math.random(), y: Math.random() * 0.55, r: rand(0.4, 1.2), p: rand(0, 6.28) }));
  const layers = [
    { base: 0.58, amp: 0.07, freq: 1.3, speed: 0.012, seed: 1.7, depth: 0.25 },
    { base: 0.68, amp: 0.06, freq: 2.1, speed: 0.024, seed: 4.2, depth: 0.55 },
    { base: 0.78, amp: 0.05, freq: 3.2, speed: 0.045, seed: 8.9, depth: 0.85 },
  ];
  const frameLog = [];
  let lastDraw = -1, fps = 0, cloudFade = 1, dim = 0, pan = 0;

  const ridge = (layer, u) => layer.base
    + Math.sin((u + layer.seed) * layer.freq * 2.1) * layer.amp
    + Math.sin((u * 2.7 + layer.seed) * layer.freq) * layer.amp * 0.45;

  const cloud = (ctx, x, y, s, a) => {
    const g = ctx.createLinearGradient(0, y - 22 * s, 0, y + 12 * s);
    g.addColorStop(0, alpha("#ffffff", a));
    g.addColorStop(1, alpha("#ffffff", a * 0.7));
    ctx.fillStyle = g;
    ctx.beginPath();
    [[0, 0, 16], [18, -9, 20], [40, -2, 17], [58, 3, 12], [-16, 4, 11]].forEach(([ox, oy, r]) => {
      ctx.moveTo(x + ox * s + r * s, y + oy * s);
      ctx.arc(x + ox * s, y + oy * s, r * s, 0, Math.PI * 2);
    });
    ctx.fill();
  };

  runWhileVisible(host, (dt, now) => {
    const { ctx, w, h } = demo.view;
    const light = palette.light;
    const unfocused = simulate.checked || !focused;
    const cap = unfocused ? 10 : 0;
    if (cap && now - lastDraw < 1 / cap - 0.002) return;
    const step = lastDraw < 0 ? 0.016 : Math.min(0.2, now - lastDraw);
    lastDraw = now;
    frameLog.push(now);
    while (frameLog.length && now - frameLog[0] > 1.6) frameLog.shift();
    fps = frameLog.filter((t) => now - t <= 1).length;
    pan += step;
    cloudFade = lerp(cloudFade, unfocused && hideClouds.checked ? 0 : 1, 1 - Math.exp(-step * 6));
    dim = lerp(dim, unfocused ? 1 : 0, 1 - Math.exp(-step * 6));

    // Sky
    const sky = ctx.createLinearGradient(0, 0, 0, h);
    sky.addColorStop(0, light ? "#bcd8ff" : "#070d1c");
    sky.addColorStop(0.65, light ? "#e3efff" : "#122245");
    sky.addColorStop(1, light ? "#f4f8ff" : "#1b2f5c");
    ctx.fillStyle = sky;
    ctx.fillRect(0, 0, w, h);

    if (!light) {
      stars.forEach((s) => {
        ctx.fillStyle = alpha("#ffffff", 0.35 + 0.35 * Math.sin(now * 1.3 + s.p));
        ctx.beginPath(); ctx.arc(s.x * w, s.y * h, s.r, 0, Math.PI * 2); ctx.fill();
      });
    }

    // Moon or sun with a soft halo
    const mx = w * 0.8, my = h * 0.24;
    const halo = ctx.createRadialGradient(mx, my, 0, mx, my, 90);
    halo.addColorStop(0, light ? "rgba(255, 236, 170, 0.75)" : alpha(palette.accent2, 0.35));
    halo.addColorStop(1, "rgba(255, 255, 255, 0)");
    ctx.fillStyle = halo;
    ctx.fillRect(mx - 90, my - 90, 180, 180);
    ctx.fillStyle = light ? "#fff3c4" : "#eaf2ff";
    ctx.beginPath(); ctx.arc(mx, my, 17, 0, Math.PI * 2); ctx.fill();

    if (cloudFade > 0.01) {
      clouds.forEach((c) => {
        c.x = (c.x + c.v * step) % 1.25;
        cloud(ctx, (c.x - 0.12) * w, c.y * h, c.s, cloudFade * (light ? 0.9 : 0.2));
      });
    }

    // Ridges, back to front, tinted toward the accent with atmospheric fade
    layers.forEach((layer) => {
      const offset = pan * layer.speed;
      const top = h * (layer.base - layer.amp * 1.5);
      const g = ctx.createLinearGradient(0, top, 0, h);
      if (light) {
        g.addColorStop(0, `hsl(${215 - layer.depth * 10}, ${45 + layer.depth * 20}%, ${82 - layer.depth * 30}%)`);
        g.addColorStop(1, `hsl(215, 50%, ${90 - layer.depth * 25}%)`);
      } else {
        g.addColorStop(0, `hsl(214, ${55 + layer.depth * 10}%, ${38 - layer.depth * 18}%)`);
        g.addColorStop(1, `hsl(220, 50%, ${12 - layer.depth * 4}%)`);
      }
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.moveTo(0, h);
      for (let x = 0; x <= w + 8; x += 8) ctx.lineTo(x, ridge(layer, x / w + offset) * h);
      ctx.lineTo(w, h);
      ctx.closePath();
      ctx.fill();
      // Faint rim light along the crest
      ctx.strokeStyle = alpha(palette.accent2, light ? 0.25 : 0.12 * (1 + layer.depth));
      ctx.lineWidth = 1;
      ctx.beginPath();
      for (let x = 0; x <= w + 8; x += 8) {
        const y = ridge(layer, x / w + offset) * h;
        x ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
      }
      ctx.stroke();
    });

    // Unfocused wash and status pill
    if (dim > 0.01) {
      ctx.fillStyle = light ? `rgba(240, 244, 250, ${0.45 * dim})` : `rgba(4, 8, 16, ${0.5 * dim})`;
      ctx.fillRect(0, 0, w, h);
      const label = "Window unfocused  ·  capped at 10 FPS";
      ctx.font = "500 12px 'DM Mono', monospace";
      const tw = ctx.measureText(label).width;
      const bw = tw + 44, bh = 34, bx = (w - bw) / 2, by = h * 0.42 - bh / 2;
      ctx.globalAlpha = dim;
      roundRect(ctx, bx, by, bw, bh, 17);
      ctx.fillStyle = light ? "rgba(255, 255, 255, 0.9)" : "rgba(14, 21, 33, 0.85)";
      ctx.fill();
      ctx.strokeStyle = palette.lineStrong;
      ctx.stroke();
      ctx.fillStyle = palette.warn;
      ctx.beginPath(); ctx.arc(bx + 18, by + bh / 2, 3.5, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = palette.ink;
      ctx.fillText(label, bx + 30, by + bh / 2 + 4);
      ctx.globalAlpha = 1;
    }

    // Frame timeline: one tick per rendered frame over the last 1.5 seconds
    const tlH = 38, tlY = h - tlH - 12, tlX = 14, tlW = w - 28;
    roundRect(ctx, tlX, tlY, tlW, tlH, 10);
    ctx.fillStyle = light ? "rgba(255, 255, 255, 0.72)" : "rgba(7, 11, 19, 0.62)";
    ctx.fill();
    ctx.strokeStyle = palette.line;
    ctx.stroke();
    ctx.font = "500 10px 'DM Mono', monospace";
    ctx.fillStyle = palette.faint;
    ctx.fillText("FRAMES RENDERED", tlX + 12, tlY + 15);
    const span = 1.5, innerX = tlX + 12, innerW = tlW - 24;
    frameLog.forEach((t) => {
      const age = now - t;
      if (age > span) return;
      const x = innerX + innerW * (1 - age / span);
      ctx.fillStyle = alpha(unfocused ? palette.warn : palette.accent, 0.35 + 0.65 * (1 - age / span));
      ctx.fillRect(x - 0.75, tlY + 21, 1.5, 10);
    });

    demo.stat("window", unfocused ? "Unfocused" : "Focused", unfocused ? "warn" : "good");
    demo.stat("fps", String(fps));
    demo.stat("power", unfocused ? "Low" : "Full", unfocused ? "good" : "");
  });
}

const demos = { adaptive: demoAdaptive, particles: demoParticles, mobs: demoMobs, merge: demoMerge, lod: demoLod, autosave: demoAutosave, power: demoPower };

document.querySelectorAll("[data-demo]").forEach((host) => {
  const build = demos[host.dataset.demo];
  if (build) build(host);
});
document.querySelectorAll("[data-hero]").forEach(heroScene);
})();
