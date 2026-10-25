const root = document.documentElement;
const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/* ---------- Theme ---------- */
const themeToggle = document.querySelector(".theme-toggle");
const themeMeta = document.querySelector('meta[name="theme-color"]');

function setTheme(theme, persist = false) {
  const isDark = theme !== "light";
  root.dataset.theme = isDark ? "dark" : "light";
  if (themeToggle) {
    themeToggle.setAttribute("aria-label", `Switch to ${isDark ? "light" : "dark"} mode`);
  }
  if (themeMeta) {
    themeMeta.content = isDark ? "#070b13" : "#f6f8fc";
  }
  if (persist) {
    try { localStorage.setItem("chlorine-theme", root.dataset.theme); } catch (error) { /* storage unavailable */ }
  }
  window.dispatchEvent(new Event("chlorine:theme"));
}

let savedTheme = null;
try { savedTheme = localStorage.getItem("chlorine-theme"); } catch (error) { /* storage unavailable */ }
setTheme(savedTheme === "light" ? "light" : "dark");

themeToggle?.addEventListener("click", () => {
  const next = root.dataset.theme === "dark" ? "light" : "dark";
  if (document.startViewTransition && !reduceMotion) {
    document.startViewTransition(() => setTheme(next, true));
  } else {
    setTheme(next, true);
  }
});

/* ---------- Mobile menu ---------- */
const menuToggle = document.querySelector(".menu-toggle");
const siteNav = document.querySelector(".site-nav");
menuToggle?.addEventListener("click", () => {
  const open = menuToggle.getAttribute("aria-expanded") !== "true";
  menuToggle.setAttribute("aria-expanded", String(open));
  siteNav.classList.toggle("is-open", open);
});

/* ---------- Nav pill that glides between links ---------- */
if (siteNav) {
  const pill = document.createElement("span");
  pill.className = "nav-pill";
  siteNav.prepend(pill);
  const current = siteNav.querySelector('[aria-current="page"]');
  const moveTo = (link) => {
    if (!link) { pill.style.opacity = "0"; return; }
    pill.style.opacity = "1";
    pill.style.width = `${link.offsetWidth}px`;
    pill.style.transform = `translateX(${link.offsetLeft}px)`;
  };
  siteNav.querySelectorAll("a").forEach((link) => link.addEventListener("pointerenter", () => moveTo(link)));
  siteNav.addEventListener("pointerleave", () => moveTo(current));
  requestAnimationFrame(() => moveTo(current));
  window.addEventListener("resize", () => moveTo(current));
}

/* ---------- Scroll progress + header state ---------- */
const header = document.querySelector(".site-header");
const progress = document.querySelector(".scroll-progress");
let scrollQueued = false;
function onScroll() {
  scrollQueued = false;
  const max = document.documentElement.scrollHeight - window.innerHeight;
  progress?.style.setProperty("--progress", max > 0 ? (window.scrollY / max).toFixed(4) : 0);
  header?.classList.toggle("is-scrolled", window.scrollY > 20);
}
window.addEventListener("scroll", () => {
  if (!scrollQueued) { scrollQueued = true; requestAnimationFrame(onScroll); }
}, { passive: true });
onScroll();

/* ---------- Split headlines into words for the mask reveal ---------- */
document.querySelectorAll(".split").forEach((heading) => {
  let index = 0;
  const walk = (node) => {
    [...node.childNodes].forEach((child) => {
      if (child.nodeType === Node.TEXT_NODE) {
        const fragment = document.createDocumentFragment();
        child.textContent.split(/(\s+)/).forEach((part) => {
          if (!part) return;
          if (/^\s+$/.test(part)) { fragment.append(part); return; }
          const outer = document.createElement("span");
          outer.className = "w";
          const inner = document.createElement("span");
          inner.textContent = part;
          // background-clip:text doesn't reach transformed children, so each word carries its own gradient.
          if (node.classList.contains("grad")) inner.className = "grad";
          inner.style.setProperty("--i", index++);
          outer.append(inner);
          fragment.append(outer);
        });
        child.replaceWith(fragment);
      } else if (child.nodeType === Node.ELEMENT_NODE && child.tagName !== "BR") {
        walk(child);
      }
    });
  };
  walk(heading);
  heading.querySelectorAll(".grad:not(.w > .grad)").forEach((element) => element.classList.remove("grad"));
});

/* ---------- Scroll reveal (with stagger for [data-stagger] children) ---------- */
document.querySelectorAll("[data-stagger]").forEach((group) => {
  [...group.children].forEach((child, i) => {
    child.classList.add("reveal");
    child.style.setProperty("--d", `${i * 110}ms`);
  });
});
document.querySelectorAll("[data-reveal]").forEach((element) => element.classList.add("reveal"));

const revealTargets = document.querySelectorAll(".reveal, .split, .steps, .result");
if (reduceMotion || !("IntersectionObserver" in window)) {
  revealTargets.forEach((element) => element.classList.add("in"));
} else {
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add("in");
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.14, rootMargin: "0px 0px -6% 0px" });
  revealTargets.forEach((element) => observer.observe(element));
}

/* ---------- Count-up numbers ---------- */
document.querySelectorAll("[data-count]").forEach((element) => {
  const target = parseFloat(element.dataset.count);
  const decimals = (element.dataset.count.split(".")[1] || "").length;
  const format = (value) => value.toLocaleString(undefined, { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
  if (reduceMotion || !("IntersectionObserver" in window)) { element.firstChild.textContent = format(target); return; }
  element.firstChild.textContent = format(0);
  const observer = new IntersectionObserver(([entry]) => {
    if (!entry.isIntersecting) return;
    observer.disconnect();
    const start = performance.now();
    const duration = 2200;
    const tick = (now) => {
      const t = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - t, 4);
      element.firstChild.textContent = format(target * eased);
      if (t < 1) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }, { threshold: 0.5 });
  observer.observe(element);
});

/* ---------- Pointer effects: card spotlight + magnetic buttons ---------- */
const finePointer = window.matchMedia("(pointer: fine)").matches;
if (finePointer && !reduceMotion) {
  document.querySelectorAll(".card").forEach((card) => {
    card.addEventListener("pointermove", (event) => {
      const rect = card.getBoundingClientRect();
      card.style.setProperty("--mx", `${event.clientX - rect.left}px`);
      card.style.setProperty("--my", `${event.clientY - rect.top}px`);
    });
  });
  document.querySelectorAll(".btn-primary, .btn-ghost").forEach((button) => {
    button.addEventListener("pointermove", (event) => {
      const rect = button.getBoundingClientRect();
      button.style.setProperty("--bx", `${(event.clientX - rect.left - rect.width / 2) * 0.18}px`);
      button.style.setProperty("--by", `${(event.clientY - rect.top - rect.height / 2) * 0.28}px`);
    });
    button.addEventListener("pointerleave", () => {
      button.style.setProperty("--bx", "0px");
      button.style.setProperty("--by", "0px");
    });
  });
}

/* ---------- Features page table of contents ---------- */
const tocLinks = [...document.querySelectorAll(".toc a")];
if (tocLinks.length && "IntersectionObserver" in window) {
  const sections = tocLinks.map((link) => document.querySelector(link.getAttribute("href")));
  const tocObserver = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        tocLinks.forEach((link) => link.classList.toggle("active", link.getAttribute("href") === `#${entry.target.id}`));
      }
    });
  }, { rootMargin: "-40% 0px -55% 0px" });
  sections.forEach((section) => section && tocObserver.observe(section));
}

/* ---------- Demo lab tabs ---------- */
document.querySelectorAll(".lab").forEach((lab) => {
  const tabs = [...lab.querySelectorAll(".lab-tab")];
  const select = (tab, focus = false) => {
    tabs.forEach((other) => {
      const selected = other === tab;
      other.setAttribute("aria-selected", String(selected));
      other.tabIndex = selected ? 0 : -1;
      document.getElementById(other.getAttribute("aria-controls")).hidden = !selected;
    });
    if (focus) tab.focus();
    tab.scrollIntoView({ block: "nearest", inline: "nearest", behavior: reduceMotion ? "auto" : "smooth" });
    window.dispatchEvent(new Event("resize"));
  };
  tabs.forEach((tab, i) => {
    tab.addEventListener("click", () => select(tab));
    tab.addEventListener("keydown", (event) => {
      const step = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[event.key];
      if (!step) return;
      event.preventDefault();
      select(tabs[(i + step + tabs.length) % tabs.length], true);
    });
  });
});
