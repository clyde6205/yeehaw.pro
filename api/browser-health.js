export default function handler(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed." });
  }
  res.setHeader("Cache-Control", "no-store");
  return res.status(200).json({
    ok: true,
    service: "yehaw-cloud-browser",
    version: "0.2.0",
    capabilities: ["safe-fetch", "html-lite-transform", "image-webp", "css-rewrite", "edge-cache"]
  });
}
