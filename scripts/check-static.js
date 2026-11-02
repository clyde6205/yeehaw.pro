import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const html = await readFile(new URL("../index.html", import.meta.url), "utf8");
const manifest = JSON.parse(await readFile(new URL("../manifest.json", import.meta.url), "utf8"));
const serviceWorker = await readFile(new URL("../sw.js", import.meta.url), "utf8");

assert.match(html, /Yehaw — Lightweight Android Browser/, "Product page must identify the Yehaw Android browser");
assert.match(html, /signed public release is not available yet/i, "Do not imply an APK is available before a signed release exists");
assert.equal(manifest.name, "Yehaw — Lightweight Android Browser", "PWA metadata must identify the browser product");
assert.doesNotMatch(html, /Filipino directory|directory listings|service directory/i, "Retired directory content must not return to the public product page");
assert.match(serviceWorker, /unregister/, "Retired service worker must unregister itself");
assert.match(serviceWorker, /caches\.keys/, "Retired service worker must clear old application caches");

console.log("Yehaw static product checks passed.");
