# Template-fill upgrade and rollback

## Pinned upgrade sequence

1. **Freeze rollout** — set `ppt-master.template-fill-enabled=false`; running jobs continue under ownership rules.
2. **Select candidate** — choose a single `ppt-master` commit/tag; update `TemplateFillReadinessContributor.EXPECTED_UPSTREAM_VERSION` and docs in the same release.
3. **Deploy to staging** — point `ppt-master.repo-path` at the candidate checkout; run readiness (`/actuator/health`).
4. **Contract gates** — execute Maven tests touching template-fill fixtures (`TemplateFillUpstreamContractTests`, apply editability fixtures, minimal OOXML export).
5. **Golden fixtures** — run analyze → check-plan → apply → validate manually on `src/test/resources/template-fill/minimal-valid-export.pptx` and stage4 matrix assets.
6. **Canary** — enable creation for test tenant only; observe thresholds in [`template-fill-rollout-thresholds.md`](template-fill-rollout-thresholds.md) for ≥24h.
7. **Promote** — expand `template-fill-allowed-tenants` gradually; keep previous upstream checkout available on disk for rollback.

## Rollback preserving workspaces

Rollback MUST NOT delete existing job workspaces.

1. Disable new creation (`template-fill-enabled=false`).
2. Restore previous `ppt-master.repo-path` checkout and previous `EXPECTED_UPSTREAM_VERSION` string.
3. Redeploy application; confirm readiness UP with restored version.
4. Running jobs:
   - Prefer letting in-flight jobs finish on the version they started with (workspace already materialized).
   - If incompatible, mark job failed with stable code and offer resume only after operator confirms upstream compatibility.
5. Retain workspaces until lifecycle retention / approved cleanup (Group 3); downloads remain authorized for owners.

## Configuration checklist

| Setting | Upgrade note |
| --- | --- |
| `ppt-master.python-command` | Re-verify venv after upstream dependency changes |
| `ppt-master.repo-path` | Single pinned checkout; no floating `main` in production |
| `template-fill-enabled` | Off during swap; on only after readiness + fixtures |
| `template-fill-allowed-tenants` | Shrink before rollback; expand only after metrics green |
| `template-fill-production.execution-stop-enabled` | Use to halt new analyze/apply without deleting workspaces |

## Evidence to record

- Readiness snapshot (component statuses + `expectedVersion`, no paths)
- Fixture test run ID / CI link
- Canary window metrics screenshot or query export
- Rollback decision timestamp and restored version
