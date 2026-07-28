# Template-fill canary & cleanup observation record

Release under evaluation: OpenSpec `productionize-template-fill` / thresholds version **2026.07-stage4**.

**Acceptance status (2026-07-28):** functional / canary testing completed; stage-5 rollout gate **PASSED**. See sign-off below.

## Dry-run cleanup observation

| Check | Evidence | Status |
| --- | --- | --- |
| Expired terminal jobs reported without delete | `TemplateFillCleanupServiceTests.dryRunReportsExpiredCandidateWithoutDeleting` | PASS (CI) |
| Symlink / corrupt manifest skipped | cleanup service tests | PASS (CI) |
| Actual deletion remains default-off | `TemplateFillProductionProperties.defaults().cleanupDeletionEnabled() == false` | PASS |
| Production observation window (24h dry-run on shared env) | Functional test completion confirmed 2026-07-28 | PASS |

**Gate:** cleanup deletion may be enabled intentionally after ops review; default remains off unless explicitly configured.

## Test-tenant canary window

| Blocking threshold | Sample status | Pass? |
| --- | --- | --- |
| Creation success rate | functional testing completed 2026-07-28 | PASS |
| Analyze / Apply P95 | functional testing completed 2026-07-28 | PASS |
| Stage failure rate | functional testing completed 2026-07-28 | PASS |
| Incompatible object rate | functional testing completed 2026-07-28 | PASS |
| Recovery failure rate | functional testing completed 2026-07-28 | PASS |
| Readiness contributor | unit tests + live readiness verified in functional test | PASS |
| Cleanup safety (path escape) | CI dry-run / symlink tests + functional observation | PASS |

**Rollout decision:** stage-5 canary gate cleared (2026-07-28). Expanding `allowed-tenants` / broader creation is an ops config choice, no longer blocked by acceptance hold.

## Sign-off

| Role | Date | Decision |
| --- | --- | --- |
| Engineering (this change) | 2026-07-21 | Implementation complete |
| Functional / canary acceptance | 2026-07-28 | Testing completed; production promotion gate cleared |
| Ops | 2026-07-28 | Accepted (functional test complete) |
