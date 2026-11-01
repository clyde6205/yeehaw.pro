import test from "node:test";
import assert from "node:assert/strict";
import { optimizeHtml, rewriteCss } from "../api/_browser/transform.js";

test("removes executable and heavy embedded content", () => {
  const input = '<html><head></head><body><script>alert(1)</script><iframe src="https://evil.example"></iframe><video src="movie.mp4"></video><p onclick="alert(1)">Hello</p></body></html>';
  const output = optimizeHtml(input, "https://example.com/");
  assert.doesNotMatch(output, /<script|<iframe|<video|onclick=/i);
  assert.match(output, /Hello/);
  assert.match(output, /data-yehaw-lite/);
});

test("rewrites page links, images and stylesheets through Yehaw APIs", () => {
  const input = '<html><head><link rel="stylesheet" href="/site.css"></head><body><a href="/next">Next</a><img src="/hero.jpg"></body></html>';
  const output = optimizeHtml(input, "https://example.com/home");
  assert.match(output, /\/api\/render\?url=https%3A%2F%2Fexample.com%2Fnext/);
  assert.match(output, /\/api\/resource\?url=https%3A%2F%2Fexample.com%2Fhero.jpg/);
  assert.match(output, /\/api\/resource\?url=https%3A%2F%2Fexample.com%2Fsite.css/);
});

test("removes javascript links", () => {
  const output = optimizeHtml('<a href="javascript:alert(1)">bad</a>', "https://example.com/");
  assert.doesNotMatch(output, /javascript:/i);
});

test("rewrites CSS resource URLs", () => {
  const output = rewriteCss("body{background:url(/bg.jpg)}", "https://example.com/css/main.css");
  assert.match(output, /\/api\/resource\?url=https%3A%2F%2Fexample.com%2Fbg.jpg/);
});
