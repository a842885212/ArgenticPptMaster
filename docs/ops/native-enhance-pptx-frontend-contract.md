# Native-enhance-pptx frontend API contract

Framework-independent reference for clients consuming native-enhance without the first-party static page.

## Authentication

All endpoints require deployment-authenticated identity. The server derives tenant/subject from the trusted security context — **never** from request body fields.

## Create job

```
POST /api/ppt-jobs
Content-Type: multipart/form-data
```

| Field | Required | Notes |
| --- | --- | --- |
| `workflowMode` | yes | Must be `native-enhance-pptx` (or `native_enhance_pptx`) |
| `files` | yes | Exactly one `.pptx` source (no template dual slot) |
| `projectName` | no | Sanitized server-side |
| `format` | no | Optional canvas hint |
| `instruction` | no | Soft preference only |

### Success (`202`/`200`)

`PptJobResponse` with `workflowMode=NATIVE_ENHANCE_PPTX`, bounded native-enhance analysis, node states, confirmation payload when waiting. **Excluded:** owner subject, tenant id, workspace paths, notes/audio bodies, export bytes.

### Creation errors

| HTTP | Condition | Stable body |
| --- | --- | --- |
| 503 | Feature disabled | `PptNativeEnhanceUnavailableException` mapping |
| 403 | Missing/unauthorized identity or tenant | Access error without echoing spoofed tenant |
| 400 | Validation (missing file, size limits, template fields) | Prefer `NativeEnhanceErrorCode` / state messages |

## Job detail and SSE

```
GET /api/ppt-jobs/{id}
GET /api/ppt-jobs/{id}/events (SSE)
```

Authorization: owner (same tenant) or native-enhance admin role.

**Safe fields:** job status, current node, confirmation id, bounded analysis, stable error codes, `resumable`, `downloadReady`.

**Excluded:** absolute paths, stdout/stderr, tokens, owner/tenant identifiers, notes/audio plaintext.

## Confirmation

```
POST /api/ppt-jobs/{id}/confirm
```

Stages:

- `enhancement_plan_confirmation` — approve/reject with matching plan `version`/`digest`
- `audio_voice_confirmation` — approve voice/provider when audio enabled

Hard constraint “不改版式与可见内容，仅追加备注/旁白/切换” cannot be relaxed through answers. Never request `--force`.

## Resume / download / diagnostics

```
POST /api/ppt-jobs/{id}/resume
GET /api/ppt-jobs/{id}/download
GET /api/ppt-jobs/{id}/native-enhance-pptx/diagnostics
```

Download only when artifact validated (`downloadReady` / completed + read-back) and authorized. Diagnostics require `diagnostics-enabled=true` plus owner/admin authorization.

## First-party page semantics

`/native-enhance-pptx/` requires hard-constraint acknowledgement before create, submits a single `files` part with `workflowMode=native-enhance-pptx`, shows module plan + voice confirmation UI, and hides download until read-back readiness.

## Configuration reference (client-visible effects)

| Property | Client impact |
| --- | --- |
| `ppt-master.native-enhance-pptx.production.enabled` | Creation returns 503 when false |
| Allowed tenants / admin role | 403 for ineligible callers |
| `execution-stop-enabled` | Resume / new checkpoints unavailable |
| `diagnostics-enabled` | Diagnostics endpoint available when true |
