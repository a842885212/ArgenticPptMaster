# Template-fill production runbook

## Identity prerequisites

Template-fill creation is **fail-closed** unless all of the following hold:

1. `ppt-master.template-fill-enabled=true` (or nested production `enabled`).
2. Deployment provides a trusted `PptAccessContext` (subject + tenant + roles) — not client-supplied multipart fields.
3. Caller is either in `ppt-master.template-fill-allowed-tenants` or has `ppt-master.template-fill-admin-role` (default `ADMIN`).

Running jobs continue when creation is disabled; downloads and confirmations still require ownership checks.

## Readiness

Endpoint: `GET /actuator/health` (component `templateFillReadiness`).

Checks (redacted):

| Component | Pass criteria | reasonCode on failure |
| --- | --- | --- |
| `python` | Configured interpreter exists and is executable | `PYTHON_MISSING`, `PYTHON_NOT_EXECUTABLE` |
| `project_manager` | Script present under pinned repo layout | `SCRIPT_MISSING` |
| `template_fill_pptx` | Script present under pinned repo layout | `SCRIPT_MISSING` |
| `readiness_marker` | Classpath marker `template-fill/readiness-marker.txt` | `READINESS_MARKER_MISSING` |

**Do not expand allowlist** while readiness is DOWN. `expectedVersion` must match the pinned upstream documented in [`template-fill-upgrade-rollback.md`](template-fill-upgrade-rollback.md).

## Python / ppt-master dependency checks

1. Confirm `ppt-master.python-command` resolves on the host (`which` / file executable).
2. Confirm `ppt-master.repo-path` checkout matches pinned version (`expectedVersion`).
3. Verify scripts exist:
   - `skills/ppt-master/scripts/project_manager.py`
   - `skills/ppt-master/scripts/template_fill_pptx.py`
4. Run contract fixtures from `src/test/resources/template-fill/` against the pinned checkout before production promotion.

## Common OOXML failures

| Symptom | Stable error / metric | Action |
| --- | --- | --- |
| Unsupported SmartArt / OLE | `TEMPLATE_FILL_UNSUPPORTED_FEATURE`, `template_fill_incompatible_total{object_category="SMARTART"|"OLE"}` | Reject plan edits that target unsupported objects; document template limitation to user |
| Apply validation failure | `TEMPLATE_VALIDATE_FAILED` | Inspect bounded validation summary in job events; re-run analyze if template drifted |
| Readback mismatch | `TEMPLATE_READBACK_FAILED` | Check upstream validate output; consider recovery resume from last checkpoint |
| Upload too large | `TEMPLATE_FILL_UPLOAD_TOO_LARGE` | Reduce template/content size per configured limits |

Never paste absolute workspace paths or template text into tickets.

## Metrics and alerts

Scrape `/actuator/prometheus`. Core counters/timers (`TemplateFillTelemetry`):

- `template_fill_creation_total{outcome}`
- `template_fill_stage_total{stage,outcome}`
- `template_fill_stage_duration_seconds{stage}`
- `template_fill_error_total{error_code}`
- `template_fill_decision_total{decision}`
- `template_fill_recovery_total{outcome}`
- `template_fill_incompatible_total{object_category}`

See [`template-fill-rollout-thresholds.md`](template-fill-rollout-thresholds.md) and [`template-fill-alerts.example.yml`](template-fill-alerts.example.yml).

## Diagnostics

Redacted diagnostic export (Group 5) is optional and off by default (`ppt-master.template-fill-production.diagnostics-enabled=false`). When enabled:

- Only owner/admin may request bundles.
- Bundles exclude templates, content sources, exports, full plans, raw OOXML, identities, and absolute paths.
- All exports are audited and inherit task retention.

Until Group 5 ships, use job SSE + stable error codes only.

## Dry-run and cleanup

Defaults:

- `cleanup-dry-run-enabled=true`
- `cleanup-deletion-enabled=false`

Operational sequence (Group 3):

1. Observe dry-run reports for at least one full retention window.
2. Confirm no active/non-terminal jobs appear in candidate lists.
3. Enable deletion only after thresholds pass and legal retention is approved.

## Incidents

| Severity | Condition | Immediate action |
| --- | --- | --- |
| SEV1 | Readiness DOWN + creation enabled | Disable `template-fill-enabled`; stop allowlist expansion |
| SEV1 | Suspected cross-tenant access | Disable creation; rotate credentials; preserve workspaces |
| SEV2 | Apply error spike | Enable execution stop if available; pin upstream rollback |
| SEV3 | Elevated incompatible objects | Warn tenants; tighten templateConstraints guidance |

Post-incident: verify metrics returned to warning band for two windows before re-promotion.
