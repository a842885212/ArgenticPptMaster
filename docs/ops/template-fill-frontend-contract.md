# Template-fill frontend API contract

Framework-independent reference for clients consuming template-fill without the first-party static page (Group 6).

## Authentication

All endpoints require deployment-authenticated identity. The server derives tenant/subject from the trusted security context — **never** from request body fields.

## Create job

```
POST /api/ppt-jobs
Content-Type: multipart/form-data
```

| Field | Required | Notes |
| --- | --- | --- |
| `workflowMode` | yes | Must be `template-fill` |
| `templateFile` | yes | Single `.pptx` template |
| `files` | yes | One or more content sources (non-empty) |
| `projectName` | no | Sanitized server-side |
| `format` | no | Recorded; canvas inherits template |
| `instruction` | no | Soft preference only |
| `templateConstraints` | no | JSON string; allowed keys documented in integration plan |

### Success (`200`)

`PptJobResponse` with `workflowMode=template-fill`, safe template/content metadata, `templateAnalysisReady`, `fillPlanStatus`, `templateFillProgress`, node states. **Excluded:** owner subject, tenant id, workspace paths, raw slide text, fill plan body.

### Creation errors

| HTTP | Condition | Stable body |
| --- | --- | --- |
| 503 | Feature disabled | `PptTemplateFillUnavailableException` mapping |
| 403 | Missing/unauthorized identity or tenant | Access error without echoing spoofed tenant |
| 400 | Validation (missing template, bad constraints, upload limits) | Message prefix may include `TemplateFillErrorCode` |

## Job detail and SSE

```
GET /api/ppt-jobs/{id}
GET /api/ppt-jobs/{id}/events (SSE)
```

Authorization: owner (same tenant) or admin role.

**SSE payload fields (safe):** job status, current node, confirmation id, bounded `templateFillProgress`, stable error codes, monotonic version fields for outline/plan when applicable.

**Excluded from SSE/JSON:** absolute paths, environment variables, command stdout/stderr, authentication tokens, owner/tenant identifiers, full slot text, raw OOXML, uploaded file bytes.

## Confirmation

```
POST /api/ppt-jobs/{id}/confirmations/{confirmationId}
```

Body includes action (`ACCEPT` / `REJECT`), expected plan version/digest for template-fill plan confirmation.

Responses use stale-version errors when digest/version mismatch; clients must refresh job detail before retry.

## Resume / download

```
POST /api/ppt-jobs/{id}/resume
GET /api/ppt-jobs/{id}/download
```

Download only when artifact validated and authorized. URLs are relative API paths, not filesystem paths.

## Error field conventions

- Prefer stable `TemplateFillErrorCode` names in `errorMessage` or structured event payloads.
- HTTP status reflects transport/authorization; business failure codes remain stable across releases.
- Never expose stack traces or internal exception messages containing paths.

## Configuration reference (client-visible effects)

| Property | Client impact |
| --- | --- |
| `ppt-master.template-fill-enabled` | Creation returns 503 when false |
| Allowed tenants / admin role | 403 for ineligible callers |
| Upload size limits | 400 with `TEMPLATE_FILL_UPLOAD_TOO_LARGE` |

Clients should treat disabled creation by disabling UI submit while still showing server error if probed.
