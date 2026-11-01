import test from "node:test";
import assert from "node:assert/strict";
import { cacheClear, cacheGet, cacheSet } from "../api/_browser/cache.js";

test("stores and returns cache values", () => {
  cacheClear();
  cacheSet("a", { html: "ok" }, 1000);
  assert.deepEqual(cacheGet("a"), { html: "ok" });
});

test("expires stale values", async () => {
  cacheClear();
  cacheSet("short", "value", 1);
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(cacheGet("short"), null);
});
