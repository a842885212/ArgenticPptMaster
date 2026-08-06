# Native-enhance-pptx production runbook

## Identity prerequisites

Native-enhance creation is **fail-closed** unless all of the following hold:

1. `ppt-master.native-enhance-pptx.production.enabled=true` (or flat `ppt-master.native-enhance-pptx.enabled=true`).
2. Deployment provides a trusted `PptAccessContext` (subject + tenant + roles) — not client-supplied multipart fields.
3. Caller is either in `ppt-master.native-enhance-pptx.production.allowed-tenants` or has `ppt-master.native-enhance-pptx.production.admin-role` (default `ADMIN`).

Running jobs continue when creation is disabled; downloads and confirmations still require ownership checks. Generated audio is sensitive — never expose across jobs.

## Readiness

Endpoint: `GET /actuator/health` (component `nativeEnhancePptxReadiness`).

Checks (redacted):

| Component | Pass criteria | reasonCode on failure |
| --- | --- | --- |
| `python` | Configured interpreter exists and is executable | `PYTHON_MISSING`, `PYTHON_NOT_EXECUTABLE` |
| `native_enhance_pptx` | Script present under pinned repo layout | `SCRIPT_MISSING` |
| `notes_to_audio` | Script present under pinned repo layout | `SCRIPT_MISSING` |
| `ppt_to_md` | Script present under pinned repo layout | `SCRIPT_MISSING` |
| `ffprobe` | Executable available when timings module is used | `FFPROBE_MISSING` / `NATIVE_ENHANCE_FFPROBE_UNAVAILABLE` |

**Do not expand allowlist** while readiness is DOWN. `expectedVersion` must match the pinned upstream documented in [`native-enhance-pptx-upgrade-rollback.md`](native-enhance-pptx-upgrade-rollback.md).

## Hard constraints

Native-enhance must always preserve:

- Visible slide content and layout (no redesign)
- 1:1 page count and order
- Notes / audio / timings / transitions only as confirmed modules
- Never pass `--force`

## Metrics and alerts

Scrape `/actuator/prometheus`. Core counters/timers (`NativeEnhancePptxTelemetry`):

- `native.enhance.pptx.creation.total{outcome}`
- `native.enhance.pptx.stage.total{stage,outcome}`
- `native.enhance.pptx.stage.duration{stage}`
- `native.enhance.pptx.error.total{error_code}`
- `native.enhance.pptx.decision.total{decision}`
- `native.enhance.pptx.recovery.total{outcome}`

See [`native-enhance-pptx-rollout-thresholds.md`](native-enhance-pptx-rollout-thresholds.md).

## Diagnostics

Redacted diagnostic export is optional and off by default (`ppt-master.native-enhance-pptx.production.diagnostics-enabled=false`). When enabled:

- Endpoint: `GET /api/ppt-jobs/{id}/native-enhance-pptx/diagnostics`
- Only owner/admin may request bundles
- Bundles exclude source PPTX, audio/media bytes, notes bodies, absolute paths, and stdout
- All exports are audited and inherit task retention

## Dry-run and cleanup

Defaults:

- `cleanup-dry-run-enabled=true`
- `cleanup-deletion-enabled=false`
- Interval: `ppt-master.native-enhance-pptx-cleanup-interval-ms` (default `3600000`)

Operational sequence:

1. Observe dry-run candidates and audit reasons for at least one canary window.
2. Confirm no path-escape / symlink candidates.
3. Explicitly enable deletion only after independent ops review (not automatic with allowlist expansion).

## Execution stop

`ppt-master.native-enhance-pptx.production.execution-stop-enabled=true` blocks new checkpoints without deleting workspaces or mutating confirmations. Completed validated downloads remain authorized.

## Fault triage

| Symptom | Likely cause | Action |
| --- | --- | --- |
| 503 on create | Feature flag off | Confirm intended; enable only for canary tenants |
| 403 on create | Missing identity / not allowlisted | Fix auth adapter or allowlist |
| Validate / apply fail | Coverage or module mismatch | Inspect plan confirmation + stable error code; never `--force` |
| Audio fail | TTS / notes_to_audio / quota | Retry from `AUDIO_VOICE_CONFIRMED`; keep notes |
| Timings fail | `ffprobe` missing | Fix readiness; do not silent-skip |
| Cross-job audio leak suspected | Authorization bug | Immediately disable creation + execution stop; audit download logs |
