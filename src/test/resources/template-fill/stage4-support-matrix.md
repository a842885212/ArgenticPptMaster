# Stage 4 Upstream Support Matrix

Pinned repo: `ppt-master.repo-path` → `/home/zhang/PycharmProjects/ppt-master`

Schemas:
- Library: `template_fill_pptx_library.v1`
- Plan: `template_fill_pptx_plan.v1`
- Validate report: `template_fill_pptx_validate.v1`

| Capability | Stable ID field | Plan field | Upstream support | Service policy |
| --- | --- | --- | --- | --- |
| Text slot | `slot_id` | `slides[].replacements[]` | yes | required for text |
| Speaker notes | page-level | `slides[].notes` or `speaker_notes` | yes | optional |
| Table cells | `table_id` | `slides[].table_edits[].cells[]` | yes | row/col bounds checked |
| Chart data | `chart_id` | `slides[].chart_edits[]` (`categories`, `series`) | yes | shape must match library |
| Page transition | n/a | `slides[].transition` | yes (`fade` default; `keep`/`none`/named effects) | default `fade` |
| Object animations | n/a | none | preserve-only via clone | never editable |
| Font size adjust | `text_metrics.font_size_px` (read-only) | none | **not supported by apply** | reject executable font mutations |

Plan slide identity uses `source_slide` (1-based template page index). Output order is the array order of `slides`.

Service-only metadata lives in `analysis/fill_plan.service-meta.json` and must not be required by upstream CLI.

## Confirmation & API

- `POST /api/ppt-jobs` accepts optional `templateConstraints` JSON (TEMPLATE_FILL only).
- Confirmation stage `template_fill_plan` contextData is **server-enriched** via `TemplateFillConfirmationSummary`:
  - `constraintSatisfaction`, bounded `pages` (IDs/counts/transition only), `capacityRisks`, `aggregates`
  - never includes notes/cell/chart raw text or absolute paths
- Digest binds `fill_plan.json` + `fill_plan.service-meta.json` (SHA-256 combined).

## Readback & error codes

- Independent OOXML report: `validation/template-fill-readback.json` (`template_fill_readback.v1`)
- Stable codes: `TEMPLATE_CONSTRAINT_INVALID`, `TEMPLATE_FILL_UNSUPPORTED_FEATURE`, `TEMPLATE_READBACK_FAILED`
- Download opens only when job is `COMPLETED` after upstream `validate` **and** readback has no errors
