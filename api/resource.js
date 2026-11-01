import sharp from "sharp";
import { cacheGet, cacheSet } from "./_browser/cache.js";
import { safeFetch } from "./_browser/fetch.js";
import { rewriteCss } from "./_browser/transform.js";

const MAX_RESOURCE = 8 * 1024 * 1024;
const IMAGE_TYPES = new Set(["image/jpeg", "image/png", "image/webp", "image/gif", "image/avif", "image/svg+xml"]);
const FONT_TYPES = new Set(["font/woff", "font/woff2", "application/font-woff", "application/vnd.ms-fontobject"]);

function getTarget(req) {
  const value = req.query?.url ?? new URL(req.url, "https://yehaw.app").searchParams.get("url");
  return typeof value === "string" && value.length <= 2048 ? value : null;
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
  if (!target) return res.status(400).json({ error: "Provide a valid resource URL." });

  const key = "resource:" + target;
  const cached = cacheGet(key);
  if (cached) {
    res.setHeader("Content-Type", cached.type);
    res.setHeader("Cache-Control", "public, s-maxage=3600, stale-while-revalidate=86400");
    res.setHeader("X-Yehaw-Cache", "HIT");
    res.setHeader("X-Content-Type-Options", "nosniff");
    return req.method === "HEAD" ? res.status(200).end() : res.status(200).send(cached.body);
  }

  try {
    const upstream = await safeFetch(target, {
      maxBytes: MAX_RESOURCE,
      accept: "image/avif,image/webp,image/png,image/jpeg,image/gif,text/css,font/woff2,font/woff,*/*;q=0.1"
    });
    if (upstream.statusCode < 200 || upstream.statusCode >= 300) return res.status(502).json({ error: "Resource could not be loaded." });
    const type = String(upstream.headers["content-type"] || "").split(";")[0].trim().toLowerCase();
    let body;
    let outputType;

    if (IMAGE_TYPES.has(type)) {
      body = await sharp(upstream.body, { limitInputPixels: 40000000, animated: false })
        .rotate()
        .resize({ width: 1280, height: 1280, fit: "inside", withoutEnlargement: true })
        .webp({ quality: 72, effort: 4 })
        .toBuffer();
      outputType = "image/webp";
    } else if (type === "text/css") {
      body = Buffer.from(rewriteCss(upstream.body.toString("utf8"), upstream.finalUrl.toString()));
      outputType = "text/css; charset=utf-8";
    } else if (FONT_TYPES.has(type)) {
      body = upstream.body;
      outputType = type;
    } else {
      return res.status(415).json({ error: "Resource type is not supported by Yehaw Lite." });
    }

    const cacheable = isCacheable(upstream.headers);
    const value = { body, type: outputType };
    if (cacheable) cacheSet(key, value, 3600000);
    res.setHeader("Content-Type", outputType);
    res.setHeader("Cache-Control", cacheable ? "public, s-maxage=3600, stale-while-revalidate=86400" : "no-store");
    res.setHeader("X-Yehaw-Cache", cacheable ? "MISS" : "BYPASS");
    res.setHeader("X-Yehaw-Original-Bytes", String(upstream.body.length));
    res.setHeader("X-Yehaw-Optimized-Bytes", String(body.length));
    res.setHeader("X-Content-Type-Options", "nosniff");
    return req.method === "HEAD" ? res.status(200).end() : res.status(200).send(body);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Resource optimization failed.";
    const status = /private|restricted|port|protocol|credentials|invalid destination/i.test(message) ? 400 : 502;
    return res.status(status).json({ error: message });
  }
}
