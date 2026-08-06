# Native-enhance-pptx canary & cleanup acceptance record

Release under evaluation: OpenSpec `native-enhance-pptx-phase-5` / thresholds version **2026.08-stage5-native-enhance**.

**Acceptance status:** template for ops sign-off (fill during canary). Defaults keep creation closed and deletion off.

## Dry-run cleanup observation

| Check | Evidence | Status |
| --- | --- | --- |
| Expired terminal jobs reported without delete | cleanup service tests | PASS (CI) |
| Symlink / corrupt manifest skipped | cleanup service tests | PASS (CI) |
| Actual deletion remains default-off | `NativeEnhancePptxProductionProperties.defaults().cleanupDeletionEnabled() == false` | PASS |
| Production observation window (dry-run) | _fill during canary_ | PENDING |

**Gate:** cleanup deletion may be enabled only after ops review; default remains off.

## Test-tenant canary window

| Blocking threshold | Min samples / window | Sample status | Pass? |
| --- | --- | --- | --- |
| Creation success rate | per thresholds doc | _pending_ | PENDING |
| Init / confirm / notes / audio / apply P95 | per thresholds doc | _pending_ | PENDING |
| Validate / read-back failure rate | per thresholds doc | _pending_ | PENDING |
| Confirm reject rate | per thresholds doc | _pending_ | PENDING |
| Recovery success rate | per thresholds doc | _pending_ | PENDING |
| Readiness contributor (incl. ffprobe) | unit tests + live readiness | PASS (CI unit) / PENDING live | PENDING |
| Cleanup safety (path escape) | CI dry-run / symlink tests | PASS (CI) | PASS |

**Rollout decision:** do **not** expand `allowed-tenants` while any blocking threshold fails or samples are insufficient.

## Sign-off

| Role | Date | Decision |
| --- | --- | --- |
| Engineering (this change) | 2026-08-06 | Stage-5 production components implemented; flags remain default-off |
| Functional / canary acceptance | _TBD_ | |
| Ops | _TBD_ | |
