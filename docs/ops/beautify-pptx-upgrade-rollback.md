# Beautify-pptx upgrade and rollback

## Pinned upgrade sequence

1. **Freeze rollout** — set `ppt-master.beautify-pptx.production.enabled=false`; running jobs continue under ownership rules.
2. **Select candidate** — choose a single `ppt-master` commit/tag; update `BeautifyPptxReadinessContributor.EXPECTED_UPSTREAM_VERSION` and docs in the same release.
3. **Deploy to staging** — point `ppt-master.repo-path` at the candidate checkout; run readiness (`/actuator/health` → `beautifyPptxReadiness`).
4. **Contract gates** — execute Maven tests for beautify analysis, SVG contract, output verifier, and production stage-5 suites.
5. **Golden fixtures** — run identity → intake → inventory → plan confirm → SVG → export → read-back on text/image/table/chart fixtures.
6. **Canary** — enable creation for test tenant only; observe thresholds in [`beautify-pptx-rollout-thresholds.md`](beautify-pptx-rollout-thresholds.md) for the required window.
7. **Promote** — expand `allowed-tenants` gradually; keep previous upstream checkout available on disk for rollback.

## Rollback preserving workspaces

Rollback MUST NOT delete existing job workspaces.

1. Disable new creation (`beautify-pptx.production.enabled=false`).
2. Optionally enable `execution-stop-enabled=true` to halt new checkpoints.
3. Restore previous `ppt-master.repo-path` checkout and previous `EXPECTED_UPSTREAM_VERSION` string.
4. Redeploy application; confirm readiness UP with restored version.
5. Running jobs:
   - Prefer letting in-flight jobs finish on the version they started with.
   - If incompatible, mark job failed with a stable code and offer resume only after operator confirms upstream compatibility.
6. Retain workspaces until lifecycle retention / approved cleanup; downloads remain authorized for owners.

## Configuration checklist

| Setting | Upgrade note |
| --- | --- |
| `ppt-master.python-command` | Re-verify venv after upstream dependency changes |
| `ppt-master.repo-path` | Single pinned checkout; no floating `main` in production |
| `beautify-pptx.production.enabled` | Off during swap; on only after readiness + fixtures |
| `beautify-pptx.production.allowed-tenants` | Shrink before rollback; expand only after metrics green |
| `beautify-pptx.production.execution-stop-enabled` | Halt new stages without deleting workspaces |
| `beautify-pptx.production.cleanup-deletion-enabled` | Remain `false` unless explicitly approved |

## Evidence to record

- Readiness snapshot (component statuses + `expectedVersion`, no paths)
- Fixture test run ID / CI link
- Canary window metrics export
- Rollback decision timestamp and restored version
