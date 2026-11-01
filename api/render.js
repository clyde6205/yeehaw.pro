import { cacheGet, cacheSet } from "./_browser/cache.js";
import { safeFetch } from "./_browser/fetch.js";
import { optimizeHtml } from "./_browser/transform.js";

const MAX_URL = 2048;
const MAX_HTML = 4 * 1024 * 1024;
const CSP = "default-src 'self' data: blob:; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; script-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action http: https:";

function getTarget(req) {
  const value = req.query?.url ?? new URL(req.url, "https://yehaw.app").searchParams.get("url");
  return typeof value === "string" && value.length <= MAX_URL ? value : null;
}
function securityHeaders(res) {
  res.setHeader("Content-Type", "text/html; charset=utf-8");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "no-referrer");
  res.setHeader("Content-Security-Policy", CSP);
}
function isCacheable(headers) {
  const policy = String(headers["cache-control"] || "").toLowerCase();
  return !headers["set-cookie"] && !/(?:private|no-store|no-cache)/.test(policy) && !String(headers.vary || "").includes("*");
}

export default async function handler(req, res) {
  if (!["GET", "HEAD"].includes(req.method)) {
    res.setHeader("Allow", "GET, HEAD");
    return res.status(405).json({ error: "Method not allowed." });
  }
  const target = getTarget(req);
  if (!target) return res.status(400).json({ error: "Provide a valid destination URL." });

  const key = "html:" + target;
  const cached = cacheGet(key);
  if (cached) {
    securityHeaders(res);
    res.setHeader("Cache-Control", "public, s-maxage=60, stale-while-revalidate=300");
    res.setHeader("X-Yehaw-Cache", "HIT");
    return req.method === "HEAD" ? res.status(200).end() : res.status(200).send(cached);
  }

  try {
    const upstream = await safeFetch(target, { maxBytes: MAX_HTML, accept: "text/html,application/xhtml+xml" });
    const contentType = String(upstream.headers["content-type"] || "").toLowerCase();
    if (!contentType.includes("text/html") && !contentType.includes("application/xhtml+xml")) {
      return res.status(415).json({ error: "This destination does not return an HTML page." });
    }
    if (upstream.statusCode < 200 || upstream.statusCode >= 300) {
      return res.status(502).json({ error: "The destination website could not be loaded." });
    }

    const rendered = optimizeHtml(upstream.body.toString("utf8"), upstream.finalUrl.toString());
    const cacheable = isCacheable(upstream.headers);
    if (cacheable) cacheSet(key, rendered, 60000);
    securityHeaders(res);
    res.setHeader("Cache-Control", cacheable ? "public, s-maxage=60, stale-while-revalidate=300" : "no-store");
    res.setHeader("X-Yehaw-Cache", cacheable ? "MISS" : "BYPASS");
    res.setHeader("X-Yehaw-Original-Bytes", String(upstream.body.length));
    res.setHeader("X-Yehaw-Rendered-Bytes", String(Buffer.byteLength(rendered)));
    res.setHeader("X-Yehaw-Transform", "lite-html-v1");
    return req.method === "HEAD" ? res.status(200).end() : res.status(200).send(rendered);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Browser rendering failed.";
    const status = /private|restricted|port|protocol|credentials|invalid destination/i.test(message) ? 400 : 502;
    return res.status(status).json({ error: message });
  }
}
