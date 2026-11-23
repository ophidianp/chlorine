/* Chlorine website: theme toggle, header, mobile menu, the home page's pinned scroll stage,
   the FAQ animation and the first-visit preloader. The saved theme and the preloader flag are
   applied by the small inline script in each page's head, before first paint. */
(() => {
  const $ = (id) => document.getElementById(id);
  const root = document.documentElement;
  const calm = matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* theme toggle, remembered per viewer */
  const themeBtn = $("theme");
  if (themeBtn) themeBtn.addEventListener("click", () => {
    const dark = root.dataset.theme ? root.dataset.theme === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
    const next = dark ? "light" : "dark";
    const go = () => { root.dataset.theme = next; };
    document.startViewTransition && !calm && !document.hidden ? document.startViewTransition(go) : go();
    try { localStorage.setItem("cl-theme", next); } catch (e) {}
  });

  /* header: solid after scroll, always visible; menu sheet on narrow screens */
  const hdr = $("hdr"), menuBtn = $("menu");
  const setMenu = (open) => { hdr.classList.toggle("open", open); menuBtn.setAttribute("aria-expanded", String(open)); };
  if (menuBtn) {
    menuBtn.addEventListener("click", () => setMenu(!hdr.classList.contains("open")));
    addEventListener("keydown", (e) => { if (e.key === "Escape") setMenu(false); });
    addEventListener("resize", () => { if (innerWidth > 880) setMenu(false); });
  }

  /* pinned stage: the steps light up one by one as you scroll through the section */
  const sec = $("inside");
  const steps = sec ? [...sec.querySelectorAll(".steps li")] : [];
  const layers = sec ? [...sec.querySelectorAll(".tstage .layer")] : [];
  const lbl = $("tlbl"), bar = $("bar");
  const flat = matchMedia("(max-width: 900px), (max-height: 700px)");
  let cur = -1;
  const setStep = (n) => {
    if (n === cur) return; cur = n;
    steps.forEach((s, i) => s.classList.toggle("on", i === n));
    layers.forEach((l, i) => l.classList.toggle("on", i === n));
    if (lbl) lbl.textContent = steps[n].dataset.lbl;
  };
  const onTear = () => {
    if (!sec) return;
    if (flat.matches) { setStep(0); return; }
    const r = sec.getBoundingClientRect(), max = sec.offsetHeight - innerHeight;
    const p = Math.min(1, Math.max(0, -r.top / max));
    bar.style.width = (p * 100) + "%";
    setStep(Math.min(steps.length - 1, Math.floor(p * steps.length)));
  };
  steps.forEach((s, i) => s.addEventListener("click", () => {
    if (flat.matches) return;
    const max = sec.offsetHeight - innerHeight;
    scrollTo({ top: sec.offsetTop + max * (i + 0.5) / steps.length, behavior: calm ? "auto" : "smooth" });
  }));

  const onScroll = () => { hdr.classList.toggle("solid", scrollY > 10); onTear(); };
  addEventListener("scroll", onScroll, { passive: true });
  addEventListener("resize", onTear);
  onScroll(); if (sec) setStep(0);

  /* FAQ: slide each answer open and closed instead of snapping */
  document.querySelectorAll(".faq details").forEach((d) => {
    const ans = d.querySelector(":scope > :not(summary)");
    let anim = null;
    d.querySelector("summary").addEventListener("click", (e) => {
      if (!ans || calm) return;
      e.preventDefault();
      const opening = !d.open || d.classList.contains("closing");
      const from = d.open ? ans.offsetHeight : 0;
      if (anim) anim.cancel();
      d.open = true;
      d.classList.toggle("closing", !opening);
      const to = opening ? ans.scrollHeight : 0;
      anim = ans.animate([{ height: from + "px", opacity: opening ? 0 : 1 }, { height: to + "px", opacity: opening ? 1 : 0 }],
        { duration: opening ? 340 : 260, easing: "cubic-bezier(0.2, 0.7, 0.2, 1)" });
      anim.onfinish = () => { anim = null; if (!opening) { d.open = false; d.classList.remove("closing"); } };
    });
  });

  /* first-visit preloader: the logo fills in, then the page fades up */
  if (root.classList.contains("preload")) {
    let done = false;
    const finish = () => { if (done) return; done = true; root.classList.add("pre-done"); setTimeout(() => root.classList.remove("preload", "pre-done"), 650); };
    const go = () => setTimeout(finish, Math.max(0, 1350 - performance.now()));
    document.readyState === "complete" ? go() : addEventListener("load", go);
    setTimeout(finish, 4000);
  }
})();
