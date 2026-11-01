import dns from "node:dns/promises";
import net from "node:net";
import ipaddr from "ipaddr.js";

const PROTOCOLS = new Set(["http:", "https:"]);
const PORTS = new Set(["", "80", "443"]);

export function isPublicAddress(address) {
  try { return ipaddr.process(address).range() === "unicast"; }
  catch { return false; }
}

export function validateTargetUrl(input, previousUrl = null) {
  let url;
  try { url = new URL(input); } catch { throw new Error("Invalid destination URL."); }
  if (!PROTOCOLS.has(url.protocol)) throw new Error("Only HTTP and HTTPS destinations are supported.");
  if (url.username || url.password) throw new Error("URLs containing credentials are not allowed.");
  if (!PORTS.has(url.port)) throw new Error("Destination port is not allowed.");
  if (!url.hostname || url.hostname.endsWith(".") || url.hostname.length > 253) throw new Error("Invalid destination host.");
  const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (host === "localhost" || host.endsWith(".localhost")) throw new Error("Private network destinations are not allowed.");
  if (previousUrl && new URL(previousUrl).protocol === "https:" && url.protocol !== "https:") throw new Error("HTTPS-to-HTTP redirects are not allowed.");
  url.hash = "";
  return url;
}

export async function resolvePublicHost(hostname) {
  const host = hostname.replace(/^\[|\]$/g, "");
  if (net.isIP(host)) {
    if (!isPublicAddress(host)) throw new Error("Private network destinations are not allowed.");
    return { address: host, family: net.isIP(host) };
  }
  let records;
  try { records = await dns.lookup(host, { all: true, verbatim: true }); }
  catch { throw new Error("Destination host could not be resolved."); }
  if (!records.length || records.some(record => !isPublicAddress(record.address))) {
    throw new Error("Destination host resolves to a restricted network.");
  }
  return records[0];
}

export async function validateAndResolve(input, previousUrl = null) {
  const url = validateTargetUrl(input, previousUrl);
  return { url, resolved: await resolvePublicHost(url.hostname) };
}
