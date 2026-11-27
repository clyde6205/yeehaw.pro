# Cloudflare site (yeehaw.pro) — what was wrong and the fix
Cause: `wrangler.jsonc` serves `./public`, but `public/` is generated (git-ignored). If Cloudflare deploys without running the build, the Worker has no assets and every URL 404s. The earlier `check-static.js` path bug is already fixed on this branch: `npm run build && npm run check` pass locally and in CI ("Static product site checks" green).
Fix (Cloudflare dashboard, Workers & Pages → yeehaw-pro → Settings → Build):
- Build command: `npm ci && npm run build`
- Deploy command: `npx wrangler deploy`
- Production branch: `main` (PR #11 was already merged at `3749e215`; later work is on `integration/reconcile-main`).
I cannot read or change these settings from here (the Cloudflare tool only lists the Worker). After saving, push or "Retry deployment", then open https://yeehaw.pro/ and https://yeehaw.pro/get/.
`/get/` currently redirects to INSTALL_URL=https://yeehaw.pro/ — set it to the APK page or Play listing when one exists.
