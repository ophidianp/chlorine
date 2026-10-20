const releasesEndpoint = "https://api.github.com/repos/ophidianp/chlorine/releases?per_page=100";
const releaseStatus = document.querySelector("#release-status");
const releaseResult = document.querySelector("#release-result");
const versionButtons = [...document.querySelectorAll("[data-game-version]")];
const releaseElements = {
  version: document.querySelector("#selected-game-version"),
  name: document.querySelector("#release-name"),
  notes: document.querySelector("#release-notes"),
  page: document.querySelector("#release-page"),
  download: document.querySelector("#download-button"),
};

let publishedReleases = [];
let selectedVersion = new URLSearchParams(window.location.search).get("version") === "26.2" ? "26.2" : "26.3";

function gameVersion(release) {
  const releaseText = `${release.name || ""} ${release.tag_name || ""} ${release.body || ""}`;
  return releaseText.match(/Minecraft\s*[:\-]?\s*(26\.[23])\b/i)?.[1] || null;
}

function jarAsset(release) {
  return release.assets.find((asset) => asset.name.toLowerCase().endsWith(".jar") && !asset.name.toLowerCase().endsWith("-sources.jar"));
}

function renderRelease() {
  versionButtons.forEach((button) => {
    button.setAttribute("aria-pressed", String(button.dataset.gameVersion === selectedVersion));
  });
  releaseElements.version.textContent = selectedVersion;

  const release = publishedReleases.find((item) => gameVersion(item) === selectedVersion && jarAsset(item));
  if (!release) {
    releaseResult.hidden = true;
    releaseStatus.textContent = `No published download is available for Minecraft ${selectedVersion} yet. Browse GitHub releases for the full list.`;
    return;
  }

  const asset = jarAsset(release);
  releaseElements.name.textContent = release.name;
  releaseElements.notes.textContent = release.body || "No release notes have been added.";
  releaseElements.page.href = release.html_url;
  releaseElements.download.href = asset.browser_download_url;
  releaseElements.download.setAttribute("aria-disabled", "false");
  releaseElements.download.setAttribute("download", asset.name);
  releaseResult.hidden = false;
  releaseStatus.textContent = `${asset.name} · ${new Date(release.published_at).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" })}`;
}

versionButtons.forEach((button) => {
  button.addEventListener("click", () => {
    selectedVersion = button.dataset.gameVersion;
    const url = new URL(window.location.href);
    url.searchParams.set("version", selectedVersion);
    window.history.replaceState({}, "", url);
    renderRelease();
  });
});

fetch(releasesEndpoint, { headers: { Accept: "application/vnd.github+json" } })
  .then((response) => {
    if (!response.ok) {
      throw new Error(`GitHub returned ${response.status}`);
    }
    return response.json();
  })
  .then((releases) => {
    publishedReleases = releases.filter((release) => !release.draft && !release.prerelease);
    renderRelease();
  })
  .catch((error) => {
    releaseStatus.textContent = `Could not load release data (${error.message}). You can still download directly from GitHub.`;
  });
