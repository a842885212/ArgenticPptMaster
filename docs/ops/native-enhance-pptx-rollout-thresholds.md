# Native-enhance-pptx rollout thresholds

Version: **2026.08-stage5-native-enhance**

These thresholds gate promotion beyond test tenants. Each row includes a minimum sample size; panels MUST show **insufficient sample** instead of pass when volume is below the minimum.

| Signal | Metric / query basis | Window | Min sample | Warning | Block rollout |
| --- | --- | --- | --- | --- | --- |
| Creation success rate | `rate(native_enhance_pptx_creation_total{outcome="accepted"}[30m]) / rate(native_enhance_pptx_creation_total[30m])` | 30m | 20 creations | < 95% | < 90% |
| Init/plan P95 | `histogram_quantile(0.95, rate(native_enhance_pptx_stage_duration_seconds_bucket{stage=~"INIT|PLAN"}[30m]))` | 30m | 15 completions | > 120s | > 300s |
| Notes/audio/apply P95 | `histogram_quantile(0.95, rate(native_enhance_pptx_stage_duration_seconds_bucket{stage=~"NOTES|AUDIO|APPLY"}[30m]))` | 30m | 10 completions | > 180s | > 600s |
| Read-back P95 | `histogram_quantile(0.95, rate(native_enhance_pptx_stage_duration_seconds_bucket{stage="READBACK"}[30m]))` | 30m | 10 completions | > 90s | > 300s |
| Stage failure rate | `sum(rate(native_enhance_pptx_stage_total{outcome="FAILURE"}[30m])) / sum(rate(native_enhance_pptx_stage_total[30m]))` | 30m | 30 stage events | > 5% | > 10% |
| Confirmation reject rate | `rate(native_enhance_pptx_decision_total{decision="REJECT"}[30m]) / rate(native_enhance_pptx_decision_total[30m])` | 30m | 10 decisions | > 25% | > 40% |
| Validate failure rate | `rate(native_enhance_pptx_stage_total{stage="VALIDATE",outcome="FAILURE"}[30m]) / rate(native_enhance_pptx_stage_total{stage="VALIDATE"}[30m])` | 30m | 10 events | > 5% | > 15% |
| Recovery failure rate | `rate(native_enhance_pptx_recovery_total{outcome="FAILURE"}[1h]) / rate(native_enhance_pptx_recovery_total[1h])` | 1h | 5 attempts | > 20% | > 40% |
| Stable error spike | `sum(rate(native_enhance_pptx_error_total[30m])) by (error_code)` | 30m | 10 errors / code | > 3/min except `UNKNOWN` | > 10/min or sustained `NATIVE_ENHANCE_APPLY_FAILED` / `NATIVE_ENHANCE_FFPROBE_UNAVAILABLE` |
| Cleanup safety | manual audit + cleanup dry-run | 24h | 1 dry-run cycle | any path-escape candidate | any deletion outside tombstoned manifest |
| Readiness | health contributor `nativeEnhancePptxReadiness` DOWN | 5m | 3 consecutive probes | 1 probe fail | 2 consecutive fails |

## Evaluation rules

1. **Block** overrides warning; do not expand `allowed-tenants` while any block threshold fails.
2. **Insufficient sample** if counts are below the row minimum.
3. Compare against pinned upstream `expectedVersion`; mismatch is an automatic block.
4. Expanding the creation allowlist and enabling cleanup deletion are **independent** decisions.
5. Rollback criteria: any block threshold sustained for two evaluation windows, or readiness DOWN for 10 minutes.

## Related artifacts

- Alert rules: [`native-enhance-pptx-alerts.example.yml`](native-enhance-pptx-alerts.example.yml)
- Design: `openspec/changes/native-enhance-pptx-phase-5/design.md`
