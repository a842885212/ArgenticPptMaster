# Template-fill rollout thresholds

Version: **2026.07-stage4** (aligned with `TemplateFillReadinessContributor.EXPECTED_UPSTREAM_VERSION`)

These thresholds gate promotion beyond test tenants. Each row includes a minimum sample size; panels MUST show **insufficient sample** instead of pass when volume is below the minimum.

| Signal | Metric / query basis | Window | Min sample | Warning | Block rollout |
| --- | --- | --- | --- | --- | --- |
| Creation success rate | `rate(template_fill_creation_total{outcome="accepted"}[30m]) / rate(template_fill_creation_total[30m])` | 30m | 20 creations | < 95% | < 90% |
| Analyze P95 latency | `histogram_quantile(0.95, rate(template_fill_stage_duration_seconds_bucket{stage="ANALYZE"}[30m]))` | 30m | 15 analyze completions | > 120s | > 300s |
| Apply P95 latency | `histogram_quantile(0.95, rate(template_fill_stage_duration_seconds_bucket{stage="APPLY"}[30m]))` | 30m | 10 apply completions | > 180s | > 600s |
| Stage failure rate | `sum(rate(template_fill_stage_total{outcome="FAILURE"}[30m])) / sum(rate(template_fill_stage_total[30m]))` | 30m | 30 stage events | > 5% | > 10% |
| Stable error spike | `sum(rate(template_fill_error_total[30m])) by (error_code)` | 30m | 10 errors / code | > 3/min any code except `UNKNOWN` | > 10/min or sustained `TEMPLATE_APPLY_FAILED` |
| Incompatible object rate | `sum(rate(template_fill_incompatible_total[30m])) / sum(rate(template_fill_stage_total{stage="APPLY"}[30m]))` | 30m | 10 apply completions | > 15% | > 30% |
| Recovery failure rate | `rate(template_fill_recovery_total{outcome="FAILURE"}[1h]) / rate(template_fill_recovery_total[1h])` | 1h | 5 recovery attempts | > 20% | > 40% |
| Cleanup safety | manual audit + future `template_fill_cleanup_*` metrics | 24h | 1 dry-run cycle | any path-escape candidate | any deletion outside tombstoned manifest |
| Readiness | `up{job="argentic-ppt-master"} == 0` OR health contributor `templateFillReadiness` DOWN | 5m | 3 consecutive probes | 1 probe fail | 2 consecutive fails |

## Evaluation rules

1. **Block** overrides warning; do not expand `ppt-master.template-fill-enabled` allowlist while any block threshold fails.
2. **Insufficient sample** if creation count, stage completions, or error counts in the window are below the row minimum.
3. Compare against the pinned upstream version recorded in readiness (`expectedVersion`); mismatch is an automatic block even if latency looks healthy.
4. Rollback criteria: any block threshold sustained for two evaluation windows, or readiness DOWN for 10 minutes.

## Related artifacts

- Alert rules: [`template-fill-alerts.example.yml`](template-fill-alerts.example.yml)
- Runbook: [`template-fill-runbook.md`](template-fill-runbook.md)
- Upgrade / rollback: [`template-fill-upgrade-rollback.md`](template-fill-upgrade-rollback.md)
