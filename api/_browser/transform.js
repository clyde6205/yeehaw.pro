import { load } from "cheerio";

const TRACKERS = ["doubleclick.net", "googlesyndication.com", "google-analytics.com", "googletagmanager.com", "facebook.net", "hotjar.com", "segment.io", "mixpanel.com"];
const DROP = "script,noscript,iframe,object,embed,video,audio,source,track,portal,[data-ad],[class*='advert'],[id*='advert'],[class*='cookie-banner'],[id*='cookie-banner']";

function trackerUrl(value, base) {
  try {
    const host = new URL(value, base).hostname.toLowerCase();
    return TRACKERS.some(domain => host === domain || host.endsWith("." + domain));
  } catch { return false; }
}

export function proxyUrl(value, base, endpoint = "/api/resource") {
  if (!value || value.startsWith("#")) return value;
  if (/^javascript:/i.test(value)) return "";
  if (/^(data:|blob:|mailto:|tel:)/i.test(value)) return value;
  try {
    const absolute = new URL(value, base);
    if (!["http:", "https:"].includes(absolute.protocol)) return "";
    return endpoint + "?url=" + encodeURIComponent(absolute.toString());
  } catch { return ""; }
}

export function rewriteCss(css, base) {
  return css
    .replace(/@import\s+(?:url\()?\s*(['"]?)([^'"\s);]+)\1\s*\)?\s*;/gi, (_m, _q, url) => "@import url('" + proxyUrl(url, base) + "');")
    .replace(/url\(\s*(['"]?)(.*?)\1\s*\)/gi, (_m, _q, url) => "url('" + proxyUrl(url, base) + "')");
}

export function optimizeHtml(html, baseUrl) {
  const $ = load(html, { decodeEntities: false });
  $(DROP).remove();
  $("meta[http-equiv='refresh' i]").remove();
  $("link[rel='preload'],link[rel='prefetch'],link[rel='modulepreload'],link[rel='preconnect'],link[rel='dns-prefetch']").remove();
  $("*").each((_i, el) => {
    const node = $(el);
    const tag = (el.tagName || "").toLowerCase();
    for (const attr of Object.keys(el.attribs || {})) {
      const key = attr.toLowerCase();
      const value = node.attr(attr) || "";
      if (key.startsWith("on") || key === "srcdoc" || key === "integrity") { node.removeAttr(attr); continue; }
      if (["href", "src", "action", "poster", "xlink:href"].includes(key)) {
        if (/^javascript:/i.test(value.trim()) || trackerUrl(value, baseUrl)) { node.removeAttr(attr); continue; }
        if (key === "href" && tag === "link" && (node.attr("rel") || "").toLowerCase() === "stylesheet") node.attr(attr, proxyUrl(value, baseUrl));
        else if (key === "href" && tag === "a") node.attr(attr, proxyUrl(value, baseUrl, "/api/render"));
        else if (key === "action") node.attr(attr, proxyUrl(value, baseUrl, "/api/render"));
        else if (key === "src" || key === "poster") node.attr(attr, proxyUrl(value, baseUrl));
      } else if (key === "srcset") {
        node.attr(attr, value.split(",").map(part => {
          const bits = part.trim().split(/\s+/); bits[0] = proxyUrl(bits[0], baseUrl); return bits.join(" ");
        }).filter(Boolean).join(", "));
      }
    }
  });
  $("base").remove();
  $("a[target]").removeAttr("target");
  $("form").attr("target", "_self");
  $("html").attr("data-yehaw-lite", "true");
  $("head").prepend('<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">');
  $("head").append('<style>html{overflow-wrap:anywhere}img{max-width:100%;height:auto}body{max-width:100%;overflow-x:hidden}</style>');
  return $.html();
}
