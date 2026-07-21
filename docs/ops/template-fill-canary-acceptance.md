# Template-fill canary & cleanup observation record

Release under evaluation: OpenSpec `productionize-template-fill` / thresholds version **2026.07-stage4**.

## Dry-run cleanup observation

| Check | Evidence | Status |
| --- | --- | --- |
| Expired terminal jobs reported without delete | `TemplateFillCleanupServiceTests.dryRunReportsExpiredCandidateWithoutDeleting` | PASS (CI) |
| Symlink / corrupt manifest skipped | cleanup service tests | PASS (CI) |
| Actual deletion remains default-off | `TemplateFillProductionProperties.defaults().cleanupDeletionEnabled() == false` | PASS |
| Production observation window (24h dry-run on shared env) | Not run in this change | PENDING |

**Gate:** do not set `ppt-master.template-fill-cleanup-deletion-enabled=true` until PENDING row is filled with a dated dry-run log.

## Test-tenant canary window

| Blocking threshold | Sample status | Pass? |
| --- | --- | --- |
| Creation success rate | insufficient live sample | NO (hold) |
| Analyze / Apply P95 | insufficient live sample | NO (hold) |
| Stage failure rate | insufficient live sample | NO (hold) |
| Incompatible object rate | insufficient live sample | NO (hold) |
| Recovery failure rate | insufficient live sample | NO (hold) |
| Readiness contributor | covered by unit tests; live probe TBD | HOLD |
| Cleanup safety (path escape) | CI dry-run / symlink tests | PASS (CI only) |

**Rollout decision:** **DO NOT** expand `allowed-tenants` or enable broader creation until every blocking row above is PASS with min sample sizes from the thresholds doc.

## Sign-off

| Role | Date | Decision |
| --- | --- | --- |
| Engineering (this change) | 2026-07-21 | Implementation complete; production promotion blocked on live canary |
| Ops | _TBD_ | _awaiting canary window_ |
