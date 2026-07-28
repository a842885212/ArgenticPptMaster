# Template-fill compatibility matrix (Stage 5 record)

Pinned capability baseline: [`src/test/resources/template-fill/stage4-support-matrix.md`](../../src/test/resources/template-fill/stage4-support-matrix.md)

Threshold definitions: [`template-fill-rollout-thresholds.md`](template-fill-rollout-thresholds.md)

## Fixture-backed evidence (CI)

| Capability | Fixture / test | Result vs thresholds |
| --- | --- | --- |
| Text replacements | `TemplateFillUpstreamContractTests`, `fill-plan-stage4-draft.json` | Contract green; no live P95 sample |
| Speaker notes | stage4 plan + confirmation summary bounds | Supported; raw notes never in API payload |
| Table cells | stage4 fixtures + validator | Supported with bounds checks |
| Chart data | stage4 fixtures + validator | Supported when series shape matches library |
| Page transition | default `fade` in plans | Supported |
| Unsupported / preserve-only objects | SmartArt/OLE/object animations policy | Fail closed / preserve-only; incompatible metrics adapter ready |
| Recovery | `PptWorkflowServiceTemplateFillTests` resume routes | Recovery path covered; canary acceptance PASS 2026-07-28 |
| Readback | `TemplateFillOutputVerifier` + `minimal-valid-export.pptx` | Required before download |

## Live rollout metrics

Live canary / functional acceptance recorded **PASS** on 2026-07-28 in [`template-fill-canary-acceptance.md`](template-fill-canary-acceptance.md). Stage-5 blocking thresholds are cleared; expanding the allowlist is an ops configuration decision.
