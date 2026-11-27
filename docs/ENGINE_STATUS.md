# Autonomous Optimization & Repair Engine: requirement-by-requirement status (2026-11-27)

Legend: DONE+TESTED = implemented and exercised by an automated test and by the end-to-end run (`node scripts/engine-e2e.mjs`, evidence in `docs/perf/e2e-evidence.json`) on a real HTTP console and real Postgres. NOT DEPLOYED = in `wip/quick-tools`, not on production (needs owner approval and a schema migration). PARTIAL / MISSING are stated plainly.

## Phase 1: production recovery
| Item | Status | Evidence |
|---|---|---|
| Restore working API | DONE | api.yeehaw.pro serves the API build of b4f2353 (probe: /healthz 200 DB, /v1/config/app-config 200 signed, admin 401, /admin/ 200 HTML) |
| Verify real endpoints / DB / auth / app reach API | DONE | `docs/DEPLOYMENT_ARCHITECTURE.md` checklist, probes from a Vercel sandbox |
| Preserve source fixes | DONE | all on `wip/quick-tools`; `main` untouched (3c73d6a2) |
| Deployment separation + root verified | DONE (documented) | Root Directory `console/backend`; analyzer flags a 4.2 s static build vs 11-12 s API builds (real data) |
| Agent endpoints enabled on production | **BLOCKED (owner)** | `/v1/agent/report` still 503: Vercel `AGENT_TOKEN` missing in the serving build |

## Phase 2: classification
Cron removed from `yeehaw-agents` (schedules list empty, verified through the Cloudflare API). Watcher = monitoring/diagnosis only (`agents/watch`). Not counted as the engine. DONE.

## Phase 3: engine
| Component | Status |
|---|---|
| Orchestrator (lease, dedupe, prioritised findings, routing, verification tracking, decision history, per-agent run status, on/off, per-agent switches, interval, permitted repairs) | DONE+TESTED, NOT DEPLOYED |
| Monitoring intelligence (watcher: site/API/DB probes, outage classification, HTML-instead-of-JSON detection, queue) | DONE+TESTED, DEPLOYED (Worker) |
| Diagnostic agent (watcher incident -> investigated finding with evidence, cause, next step, routed to owner agent) | DONE+TESTED, NOT DEPLOYED |
| Autonomous repair agent (isolated worktree/branch, protected-branch guard, tests before/after, evidence, optional draft PR, rollback on its own branch) | DONE+TESTED (4 unit tests + e2e: real change to `server.js`). Draft-PR push is implemented but off by default and not exercised against GitHub. |
| Performance agent (bench harness: round trips per request counted at the driver, p50/p95, bytes; before/after compare) | DONE+TESTED. Scope today: API hot paths. Browser/Android runtime metrics (memory, CPU, cold start) are NOT measurable from this environment and are not claimed. |
| Security agent | PARTIAL: existing detector (dependencies/secrets heuristics); `npm audit` on the backend currently reports 0 vulnerabilities (measured). No auth-review automation beyond the existing tests. |
| QA agent (independent verification on a fresh checkout, different run, different DB; rejects deleted tests / protected paths) | DONE+TESTED, NOT DEPLOYED. Server refuses any verification not from `qa`, for another commit, or from the same run. |
| Database reliability agent | PARTIAL: existing detector (size, dead rows, connections, latency). No query-plan or migration-planning automation. |
| Android compatibility agent | PARTIAL: existing detector over fleet telemetry (RAM/OS crash rates). No device/emulator testing here. |
| Infrastructure agent (Vercel failed deploys, wrong root signal, alias check, churn, stale code; Cloudflare bot mode + unexpected cron; Neon consumption) | DONE+TESTED on captured REAL data. Collection runs from connectors (this session); running unattended needs read-only API tokens as secrets (owner decision). |
| Loop DETECT→...→REPORT, approval gates, no self-verification | DONE+TESTED |

## Phase 4: console
Engine on/off, per-agent switches + status, interval, permitted repairs, run now, approve/reject/dismiss, audit trail with actor/time/result/verification, test evidence column, rollback button, costs card: DONE, rendered and checked in a real browser with real e2e data (0 page errors). NOT DEPLOYED. Missing: code diff viewer (the commit/branch is shown, the diff is not), performance-trend charts (before/after rows are shown as evidence entries).

## Phase 5: infrastructure and cost
Findings from real data and measured Neon consumption: 0.51 of 100 free CU-hours used month to date, $0 billed (measured). No paid service added. Cloudflare cost/usage and Vercel usage numbers are not collected yet (MISSING).

## Phase 6: the 12 scenarios
| # | Scenario | Result |
|---|---|---|
| 1 | API failure detected | PASS on real HTTP (watcher -> console) and in production on 2026-11-27 (real incident detected 14:05 UTC, recovered 14:29 UTC) |
| 2 | Diagnostic investigates actual cause | PASS (cause + evidence from probes; wrong-deployment classification) |
| 3 | Orchestrator assigns correct agent | PASS (`infrastructure` / `availability` / `db-optimizer` routing test) |
| 4 | Repair agent prepares a real correction | PASS (real edit of the boot handler, commit on `repair/1-perf-boot-round-trips`) |
| 5 | Automated tests execute | PASS (45/45 before and after, in isolated worktree) |
| 6 | QA independently verifies | PASS (fresh checkout, separate DB, 45/45; server rejects self-verification) |
| 7 | Console shows full history | PASS (e2e evidence + browser check) |
| 8 | Failed repair not reported successful | PASS (bad fix: tests 43/45, "fix failed", finding stays open, branch deleted) |
| 9 | Protected operations need approval | PASS (infrastructure finding has no repair: approve = 409; deploy/migration are never automatic) |
| 10 | Website outage vs API outage | PASS (classifier tests + e2e) |
| 11 | Incorrect Vercel configuration identified | PASS on REAL captured data (4.2 s build flagged critical; healthy 11.5 s build not flagged) |
| 12 | Measurable before/after performance | PASS: boot telemetry DB round trips 4 -> 1 (counted); local p50 2.79 -> 2.28 ms; projected at 20 ms RTT 82.8 -> 22.3 ms (arithmetic, labelled) |

Caveat that must stay attached to the table: scenarios 4-8, 10-12 ran against a local console and Postgres, not against the production deployment, because deploying the new code needs your approval and a database migration.
