# Beautify-pptx rollout thresholds

Version: **2026.08-stage5** (aligned with productionize-beautify-pptx canary)

These thresholds gate promotion beyond test tenants. Each row includes a minimum sample size; panels MUST show **insufficient sample** instead of pass when volume is below the minimum.

| Signal | Metric / query basis | Window | Min sample | Warning | Block rollout |
| --- | --- | --- | --- | --- | --- |
| Creation success rate | `rate(beautify_pptx_creation_total{outcome="accepted"}[30m]) / rate(beautify_pptx_creation_total[30m])` | 30m | 20 creations | < 95% | < 90% |
| Identity/intake/inventory P95 | `histogram_quantile(0.95, rate(beautify_pptx_stage_duration_seconds_bucket{stage=~"IDENTITY\|INTAKE\|INVENTORY"}[30m]))` | 30m | 15 analysis completions | > 120s | > 300s |
| Export P95 latency | `histogram_quantile(0.95, rate(beautify_pptx_stage_duration_seconds_bucket{stage="EXPORT"}[30m]))` | 30m | 10 export completions | > 180s | > 600s |
| Read-back P95 latency | `histogram_quantile(0.95, rate(beautify_pptx_stage_duration_seconds_bucket{stage="READBACK"}[30m]))` | 30m | 10 read-back completions | > 90s | > 300s |
| End-to-end / stage failure rate | `sum(rate(beautify_pptx_stage_total{outcome="FAILURE"}[30m])) / sum(rate(beautify_pptx_stage_total[30m]))` | 30m | 30 stage events | > 5% | > 10% |
| Confirmation reject rate | `rate(beautify_pptx_decision_total{decision="REJECT"}[30m]) / rate(beautify_pptx_decision_total[30m])` | 30m | 10 decisions | > 25% | > 40% |
| Complex object alert rate | `sum(rate(beautify_pptx_incompatible_total[30m])) / sum(rate(beautify_pptx_stage_total{stage="INVENTORY"}[30m]))` | 30m | 10 inventory completions | > 20% | > 40% |
| Validation failure rate | `rate(beautify_pptx_stage_total{stage="READBACK",outcome="FAILURE"}[30m]) / rate(beautify_pptx_stage_total{stage="READBACK"}[30m])` | 30m | 10 read-back events | > 5% | > 15% |
| Recovery failure rate | `rate(beautify_pptx_recovery_total{outcome="FAILURE"}[1h]) / rate(beautify_pptx_recovery_total[1h])` | 1h | 5 recovery attempts | > 20% | > 40% |
| Stable error spike | `sum(rate(beautify_pptx_error_total[30m])) by (error_code)` | 30m | 10 errors / code | > 3/min any code except `UNKNOWN` | > 10/min or sustained `BEAUTIFY_READBACK_FAILED` |
| Cleanup safety | manual audit + future `beautify_pptx_cleanup_*` metrics | 24h | 1 dry-run cycle | any path-escape candidate | any deletion outside tombstoned manifest |
| Readiness | `up{job="argentic-ppt-master"} == 0` OR health contributor `beautifyPptxReadiness` DOWN | 5m | 3 consecutive probes | 1 probe fail | 2 consecutive fails |

## Evaluation rules

1. **Block** overrides warning; do not expand `ppt-master.beautify-pptx.production.allowed-tenants` while any block threshold fails.
2. **Insufficient sample** if creation count, stage completions, or error counts in the window are below the row minimum.
3. Compare against the pinned upstream version recorded in readiness (`expectedVersion`); mismatch is an automatic block even if latency looks healthy.
4. Expanding the creation allowlist and enabling cleanup deletion are **independent** decisions — canary pass MUST NOT auto-enable deletion.
5. Rollback criteria: any block threshold sustained for two evaluation windows, or readiness DOWN for 10 minutes.

## Related artifacts

- Alert rules: [`beautify-pptx-alerts.example.yml`](beautify-pptx-alerts.example.yml)
- Design: `openspec/changes/productionize-beautify-pptx/design.md`
