const menuToggle = document.querySelector(".menu-toggle");
const siteNav = document.querySelector(".site-nav");
const themeToggle = document.querySelector(".theme-toggle");
const themeLabel = document.querySelector(".theme-label");
const themeMeta = document.querySelector('meta[name="theme-color"]');

function setTheme(theme, persist = false) {
  const isDark = theme === "dark";
  document.documentElement.dataset.theme = isDark ? "dark" : "light";
  if (themeToggle) {
    themeToggle.setAttribute("aria-checked", String(isDark));
    themeToggle.setAttribute("aria-label", `Switch to ${isDark ? "light" : "dark"} mode`);
  }
  if (themeLabel) {
    themeLabel.textContent = isDark ? "Dark" : "Light";
  }
  if (themeMeta) {
    themeMeta.content = isDark ? "#0d111b" : "#f7f9fc";
  }
  if (persist) {
    window.localStorage.setItem("chlorine-theme", isDark ? "dark" : "light");
  }
}

const savedTheme = window.localStorage.getItem("chlorine-theme");
setTheme(savedTheme === "light" ? "light" : "dark");

if (themeToggle) {
  themeToggle.addEventListener("click", () => {
    const isDark = themeToggle.getAttribute("aria-checked") === "true";
    setTheme(isDark ? "light" : "dark", true);
  });
}

if (menuToggle && siteNav) {
  menuToggle.addEventListener("click", () => {
    const isExpanded = menuToggle.getAttribute("aria-expanded") === "true";
    menuToggle.setAttribute("aria-expanded", String(!isExpanded));
    siteNav.classList.toggle("is-open", !isExpanded);
  });
}

const revealElements = document.querySelectorAll(
  ".section, .feature-card, .work-heading, .work-item, .flow-heading, .flow-grid article, .compat-copy, .compat-panel, .faq-heading, .faq-list, .download-band, .detail-row, .callout, .release-panel, .install-steps article, .benchmark-placeholder, .benchmark-context article, .benchmark-setup, .result-card, .benchmark-verdict, .lows-section, .next-test",
);

if (window.matchMedia("(prefers-reduced-motion: reduce)").matches || !("IntersectionObserver" in window)) {
  revealElements.forEach((element) => element.classList.add("is-visible"));
} else {
  const revealObserver = new IntersectionObserver((entries, observer) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add("is-visible");
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.12 });

  revealElements.forEach((element) => {
    element.classList.add("reveal");
    revealObserver.observe(element);
  });
}
