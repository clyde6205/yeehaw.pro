import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const html = await readFile(new URL("../index.html", import.meta.url), "utf8");
const manifest = JSON.parse(await readFile(new URL("../manifest.json", import.meta.url), "utf8"));
const serviceWorker = await readFile(new URL("../sw.js", import.meta.url), "utf8");
const getPage = await readFile(new URL("../public/get/index.html", import.meta.url), "utf8");

assert.match(html, /YeeHaw™ — Lightweight Android Browser/, "Product page must identify the YeeHaw Android browser");
assert.match(html, /Public APK download is being prepared/i, "Do not imply an APK is available before a signed release exists");
assert.match(manifest.name, /Lightweight Android Browser/, "PWA metadata must identify the browser product");
assert.doesNotMatch(html, /github\.com/i, "No internal repository or CI links on the public page");
assert.match(html, /href="\/privacy\/"/, "Privacy link required");
assert.match(html, /href="\/terms\/"/, "Terms link required");
assert.match(html, /href="\/support\/"/, "Support link required");
assert.match(html, /role="search"/, "Working search form required");
for (const f of ["privacy/index.html", "terms/index.html", "support/index.html", "404.html", "_headers"]) {
  await readFile(new URL("../public/" + f, import.meta.url), "utf8");
}
const headers = await readFile(new URL("../public/_headers", import.meta.url), "utf8");
assert.match(headers, /Strict-Transport-Security/, "Security headers must ship");
assert.doesNotMatch(html, /Filipino directory|directory listings|service directory/i, "Retired directory content must not return to the public product page");
assert.match(serviceWorker, /unregister/, "Retired service worker must unregister itself");
assert.match(serviceWorker, /caches\.keys/, "Retired service worker must clear old application caches");

assert.match(getPage, /<title>Get YeeHaw<\/title>/, "The install landing page must be built at /get/");
assert.match(getPage, /INSTALL_URL/, "The install landing page must use an explicit install destination");

console.log("Yehaw static product checks passed.");
