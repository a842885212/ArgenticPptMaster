# Beautify-pptx frontend API contract

Framework-independent reference for clients consuming beautify without the first-party static page.

## Authentication

All endpoints require deployment-authenticated identity. The server derives tenant/subject from the trusted security context — **never** from request body fields.

## Create job

```
POST /api/ppt-jobs
Content-Type: multipart/form-data
```

| Field | Required | Notes |
| --- | --- | --- |
| `workflowMode` | yes | Must be `beautify-pptx` |
| `files` | yes | Exactly one `.pptx` source (no template dual slot) |
| `projectName` | no | Sanitized server-side |
| `format` | no | May be refined from identity |
| `instruction` | no | Soft preference only |

### Success (`202`/`200`)

`PptJobResponse` with `workflowMode=BEAUTIFY_PPTX`, bounded `beautifyAnalysis`, node states, confirmation payload when waiting. **Excluded:** owner subject, tenant id, workspace paths, identity/inventory bodies, SVG, export bytes.

### Creation errors

| HTTP | Condition | Stable body |
| --- | --- | --- |
| 503 | Feature disabled | `PptBeautifyUnavailableException` mapping |
| 403 | Missing/unauthorized identity or tenant | Access error without echoing spoofed tenant |
| 400 | Validation (missing file, size limits, unsupported content) | Prefer `BeautifyPptxErrorCode` |

## Job detail and SSE

```
GET /api/ppt-jobs/{id}
GET /api/ppt-jobs/{id}/events (SSE)
```

Authorization: owner (same tenant) or beautify admin role.

**Safe fields:** job status, current node, confirmation id, bounded `beautifyAnalysis`, stable error codes, `resumable`, `downloadReady`.

**Excluded:** absolute paths, stdout/stderr, tokens, owner/tenant identifiers, source text, identity candidates raw values, SVG/OOXML.

SSE clients must use bounded reconnect/backoff and re-fetch job detail; duplicate events must not regress status.

## Confirmation

```
POST /api/ppt-jobs/{id}/confirm
```

For `beautify_plan_confirmation` / `contextData.type=beautify_pptx_plan`, clients MUST send the expected plan `version` and `digest` (via confirmation answers or payload fields the server validates). Stale version/digest responses require refreshing job detail before retry.

Hard constraints cannot be relaxed through answers.

## Resume / download / diagnostics

```
POST /api/ppt-jobs/{id}/resume
GET /api/ppt-jobs/{id}/download
GET /api/ppt-jobs/{id}/beautify-pptx/diagnostics
```

Download only when artifact validated (`downloadReady` / completed + read-back) and authorized. Diagnostics require `diagnostics-enabled=true` plus owner/admin authorization.

## First-party page semantics

`/beautify-pptx/` requires hard-constraint acknowledgement before create, submits a single `files` part with `workflowMode=beautify-pptx`, and hides download until read-back readiness.

## Configuration reference (client-visible effects)

| Property | Client impact |
| --- | --- |
| `ppt-master.beautify-pptx.production.enabled` | Creation returns 503 when false |
| Allowed tenants / admin role | 403 for ineligible callers |
| Max source size | 400 on oversized upload |
| `diagnostics-enabled` | Diagnostics endpoint unavailable when false |
