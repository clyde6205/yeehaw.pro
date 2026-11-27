# Unattended AI repair: secure setup

The cycle (`.github/workflows/ai-repair.yml`, daily + manual) is: run watched tests -> if one fails, send ONLY the failing output and the source files that test imports to the model -> exact-match edits -> isolated worktree -> tests -> independent QA on a fresh checkout -> push a `repair/*` branch -> DRAFT pull request. Nothing is merged or deployed automatically; edits to tests, secrets, lockfiles and keystores are refused; edits to workflows, deploy config, migrations, auth code or the manifest are labelled `founder-approval`. With every test green, no model call is made and no cost is incurred.

## One-time setup (you do this in your own GitHub; no secret goes in chat)
1. Create an Anthropic API key in the Anthropic Console with a spend limit (e.g. USD 5/month) on its own workspace.
2. In a terminal where you are logged in to `gh`: `gh secret set ANTHROPIC_API_KEY --repo clyde6205/yeehaw.pro` (paste when prompted).
3. Repository Settings > Actions > General > Workflow permissions: enable "Allow GitHub Actions to create and approve pull requests" (needed for the draft PR; it can still never merge without you).
4. Optional: `REPAIR_MODEL` env in the workflow chooses the model (default claude-sonnet-5-5).
Verify: Actions > "AI repair cycle" > Run workflow. Expected with green tests: "all watched tests pass; no model call made". Without the secret: the console shows a warning "AI repair provider not configured".

## What stays under founder control
Merging any repair PR, production deployments, database migrations and any change the workflow labels `founder-approval`.
