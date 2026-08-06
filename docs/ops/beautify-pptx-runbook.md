# Beautify-pptx production runbook

## Identity prerequisites

Beautify creation is **fail-closed** unless all of the following hold:

1. `ppt-master.beautify-pptx.production.enabled=true`.
2. Deployment provides a trusted `PptAccessContext` (subject + tenant + roles) — not client-supplied multipart fields.
3. Caller is either in `ppt-master.beautify-pptx.production.allowed-tenants` or has `ppt-master.beautify-pptx.production.admin-role` (default `ADMIN`).

Running jobs continue when creation is disabled; downloads and confirmations still require ownership checks.

## Readiness

Endpoint: `GET /actuator/health` (component `beautifyPptxReadiness`).

Checks (redacted):

| Component | Pass criteria | reasonCode on failure |
| --- | --- | --- |
| `python` | Configured interpreter exists and is executable | `PYTHON_MISSING`, `PYTHON_NOT_EXECUTABLE` |
| `beautify_identity` | Script present under pinned repo layout | `SCRIPT_MISSING` |
| `beautify_inventory` | Script present under pinned repo layout | `SCRIPT_MISSING` |
| `pptx_intake` | Script present under pinned repo layout | `SCRIPT_MISSING` |
| `project_manager` | Script present under pinned repo layout | `SCRIPT_MISSING` |
| `finalize_svg` | Script present under pinned repo layout | `SCRIPT_MISSING` |
| `svg_to_pptx` | Script present under pinned repo layout | `SCRIPT_MISSING` |
| `readiness_marker` | Classpath marker `beautify-pptx/readiness-marker.txt` | `READINESS_MARKER_MISSING` |

**Do not expand allowlist** while readiness is DOWN. `expectedVersion` must match the pinned upstream documented in [`beautify-pptx-upgrade-rollback.md`](beautify-pptx-upgrade-rollback.md).

## Python / ppt-master dependency checks

1. Confirm `ppt-master.python-command` resolves on the host.
2. Confirm `ppt-master.repo-path` checkout matches pinned version (`BeautifyPptxReadinessContributor.EXPECTED_UPSTREAM_VERSION`).
3. Verify scripts exist under `skills/ppt-master/scripts/` (list above).
4. Confirm fonts / PPT-safe fallbacks against the compatibility matrix before canary.

## Hard constraints

Beautify must always preserve:

- Text and table/chart data verbatim
- 1:1 page count and order
- Layout-only redesign (dense pages may only warn)

## Metrics and alerts

Scrape `/actuator/prometheus`. Core counters/timers (`BeautifyPptxTelemetry`):

- `beautify.pptx.creation.total{outcome}`
- `beautify.pptx.stage.total{stage,outcome}`
- `beautify.pptx.stage.duration{stage}`
- `beautify.pptx.error.total{error_code}`
- `beautify.pptx.decision.total{decision}`
- `beautify.pptx.recovery.total{outcome}`
- `beautify.pptx.incompatible.total{object_category}`

See [`beautify-pptx-rollout-thresholds.md`](beautify-pptx-rollout-thresholds.md).

## Diagnostics

Redacted diagnostic export is optional and off by default (`ppt-master.beautify-pptx.production.diagnostics-enabled=false`). When enabled:

- Endpoint: `GET /api/ppt-jobs/{id}/beautify-pptx/diagnostics`
- Only owner/admin may request bundles
- Bundles exclude source PPTX, images, identity/inventory/plan bodies, SVG, exports, OOXML, absolute paths, and stdout
- All exports are audited and inherit task retention

## Dry-run and cleanup

Defaults:

- `cleanup-dry-run-enabled=true`
- `cleanup-deletion-enabled=false`
- Interval: `ppt-master.beautify-pptx-cleanup-interval-ms` (default `3600000`)

Operational sequence:

1. Observe dry-run candidates and audit reasons for at least one canary window.
2. Keep deletion off until ops sign-off.
3. Enable deletion only with dry-run still observed / audited.

## Execution stop

`ppt-master.beautify-pptx.production.execution-stop-enabled=true` blocks new stage entry at idempotent checkpoints without deleting workspaces. Use during incident response before rollback.

## First-party page

Static entry: `/beautify-pptx/` — single-file upload, hard-constraint acknowledgement, SSE reconnect/backoff, version/digest-aware plan confirm, download only when read-back ready.
