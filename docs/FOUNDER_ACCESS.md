# Founder access and secret rotation (no secret ever goes into a chat)

## Open the production console
1. In your own browser open https://api.yeehaw.pro/admin/ .
2. Paste ADMIN_TOKEN into the sign-in box on that page. It is kept only in that tab's sessionStorage (cleared when the tab closes) and sent only to api.yeehaw.pro.
3. Open the **Agents** tab: eight agent cards, engine controls (enable / disable / interval / run now), findings, diffs, approvals, roll-back, audit trail, trends, costs.

If you no longer have ADMIN_TOKEN (Vercel hides sensitive values after saving): rotate it, do not recover it.
- `openssl rand -hex 32` in your own terminal; copy the output.
- Vercel dashboard > yeehaw-api > Settings > Environment Variables > ADMIN_TOKEN (Production) > edit > paste > mark Sensitive > save.
- Deployments > latest production deployment (built from Git, Root Directory console/backend) > Redeploy. Env vars are read at build time.

## Rotate AGENT_TOKEN without it passing through any assistant
Generate one value in your terminal and put it in all three places, typing it only into those prompts:
- `wrangler secret put AGENT_TOKEN --name yeehaw-agents`
- Vercel > yeehaw-api > Environment Variables > AGENT_TOKEN (Production, Sensitive), then Redeploy as above.
- `gh secret set AGENT_TOKEN --repo clyde6205/yeehaw.pro` (the 6-hourly GitHub job uses it too).
Verify: open https://yeehaw-agents.clydeusa.workers.dev/selftest — `"accepted": true` means the API accepts the Worker's token (the value is never shown).

## Read-only infrastructure collection (optional, enables unattended cost/usage reports)
Repository secrets, each read-only: VERCEL_TOKEN (token of a Viewer-role member), CLOUDFLARE_API_TOKEN (only "Workers Scripts: Read"), NEON_API_KEY (viewer/read-only). Until set, the console shows exactly which one is missing.

## GitHub Actions secrets (found NOT set on 2026-11-28)
The "Maintenance agents" job proved that `CONSOLE_URL` and `AGENT_TOKEN` are empty in this repository's Actions secrets, so the 6-hourly agents and the infrastructure collector have not been reporting to the console. Set both in your own terminal:
- `gh secret set CONSOLE_URL --repo clyde6205/yeehaw.pro --body https://api.yeehaw.pro`
- `gh secret set AGENT_TOKEN --repo clyde6205/yeehaw.pro` (paste the same value you put in Vercel and the Worker)
The job now ends with a check that fails loudly if the API rejects the secret ("stale") or if it is missing.
