import { getDatabaseUrl } from "./env.js";

export default function handler(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed." });
  }

  let databaseConfigured = false;
  try {
    getDatabaseUrl();
    databaseConfigured = true;
  } catch {
    // Report readiness without exposing environment values.
  }

  res.setHeader("Cache-Control", "no-store");
  return res.status(databaseConfigured ? 200 : 503).json({
    ok: databaseConfigured,
    service: "yehaw-rewards",
    pointsLedger: "policy-foundation",
    databaseConfigured,
    token: {
      status: "not-deployed",
      supportedNetworks: ["solana", "base"],
      issuanceEnabled: false
    },
    note: "Points are not cryptocurrency. No token has been minted or enabled."
  });
}
