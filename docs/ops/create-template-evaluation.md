# Create-template mode evaluation

Status: **evaluation only** — no implementation in `productionize-template-fill`.

## Summary

Upstream `create-template` builds reusable SVG/template packages from arbitrary PPTX input. `TEMPLATE_FILL` instead clones pages from a user-supplied native `.pptx` and fills existing placeholders. These are distinct workflow modes with different artifacts, checkpoints, and risk profiles.

## Contract comparison

| Dimension | `TEMPLATE_FILL` (current) | `create-template` (proposed future) |
| --- | --- | --- |
| Primary input | One template `.pptx` + content sources | Brand PPTX + design rules |
| Primary output | Editable filled `.pptx` | Reusable template package / slide library for later fills |
| Confirmation object | Fill plan (page order + slot mapping) | Template structure / layout decisions |
| Execution scripts | `template_fill_pptx.py` | Separate upstream entrypoints (not pinned today) |
| Failure semantics | OOXML apply/readback | SVG/layout generation fidelity |

## Permissions and lifecycle

A future `CREATE_TEMPLATE` mode would require:

- Stronger brand-asset retention policies (longer retention, legal hold hooks).
- Distinct rollout flag and tenant allowlist — must not reuse `template-fill-enabled` blindly.
- Ownership model consistent with `PptJobAccessAuthorizer` but separate cleanup/manifest schema to avoid cross-mode deletion.

## UI semantics

`TEMPLATE_FILL` UI must state **select/clone template slides; no free redraw**. A `create-template` UI would instead guide layout extraction and package publication — misleading to combine both in one form.

## Migration cost

| Area | Effort |
| --- | --- |
| API | New workflow mode, upload contract, response fields |
| Domain | New checkpoints, artifacts, error codes |
| Agent tools | Separate tool surface; no reuse of fill-plan agent |
| Ops | New readiness scripts, metrics stages, fixtures |
| Data | No automatic migration from fill workspaces to template packages |

## Recommendation

Proceed only after a **separate OpenSpec change** defining `CREATE_TEMPLATE` (or equivalent) with its own specs for intake, execution, confirmation, and artifact governance. Until then:

- Do not expose `create-template` commands through production runners.
- Document upstream manual usage outside this service if needed for template authoring.

## References

- Integration plan: [`../template-fill-pptx-integration-plan.md`](../template-fill-pptx-integration-plan.md) (explicit non-goal)
- Template-fill ops: [`template-fill-runbook.md`](template-fill-runbook.md)
