import http from "node:http";
import https from "node:https";
import { validateAndResolve } from "./security.js";

const MAX_REDIRECTS = 5;
const TIMEOUT_MS = 10000;

function requestOnce(url, resolved, maxBytes, accept) {
  return new Promise((resolve, reject) => {
    const secure = url.protocol === "https:";
    const transport = secure ? https : http;
    const Agent = secure ? https.Agent : http.Agent;
    const agent = new Agent({
      keepAlive: false,
      lookup: (_host, options, callback) => {
        if (options?.all) callback(null, [{ address: resolved.address, family: resolved.family }]);
        else callback(null, resolved.address, resolved.family);
      }
    });
    const req = transport.request(url, {
      method: "GET", agent,
      headers: {
        "User-Agent": "YehawLite/0.2 (+https://yehaw.app)",
        "Accept": accept, "Accept-Encoding": "identity", "Connection": "close"
      }
    }, res => {
      const chunks = [];
      let bytes = 0;
      res.on("data", chunk => {
        bytes += chunk.length;
        if (bytes > maxBytes) return req.destroy(new Error("Destination response exceeds the size limit."));
        chunks.push(chunk);
      });
      res.on("end", () => resolve({ statusCode: res.statusCode || 0, headers: res.headers, body: Buffer.concat(chunks) }));
      res.on("error", reject);
    });
    req.setTimeout(TIMEOUT_MS, () => req.destroy(new Error("Destination request timed out.")));
    req.on("error", reject);
    req.end();
  });
}

export async function safeFetch(input, options = {}) {
  const maxBytes = options.maxBytes ?? 4 * 1024 * 1024;
  const accept = options.accept ?? "text/html,application/xhtml+xml";
  let current = input;
  let previous = null;
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    const { url, resolved } = await validateAndResolve(current, previous);
    const result = await requestOnce(url, resolved, maxBytes, accept);
    if ([301, 302, 303, 307, 308].includes(result.statusCode)) {
      const location = result.headers.location;
      if (!location || hop === MAX_REDIRECTS) throw new Error("Destination redirect limit exceeded.");
      previous = url.toString();
      current = new URL(location, url).toString();
      continue;
    }
    if (result.headers["content-encoding"] && result.headers["content-encoding"] !== "identity") {
      throw new Error("Compressed upstream responses are not supported by this endpoint.");
    }
    return { ...result, finalUrl: url };
  }
  throw new Error("Destination redirect limit exceeded.");
}
