# Native-enhance-pptx upgrade / rollback

## Pinning

Java readiness embeds `NativeEnhanceReadinessContributor.EXPECTED_UPSTREAM_VERSION`.
Ops must keep the deployed `ppt-master.repo-path` checkout aligned with that pin and with
[`native-enhance-pptx-compatibility-matrix.md`](../native-enhance-pptx-compatibility-matrix.md)
(and Phase 0 contract docs).

## Upgrade procedure

1. Run upstream contract / golden fixture tests against the candidate `ppt-master` revision.
2. Deploy Java build that updates `EXPECTED_UPSTREAM_VERSION` together with the repo pin.
3. Keep `production.enabled=false` (or limited test tenants) during soak.
4. Verify readiness UP including `ffprobe` when timings are in scope.
5. Run canary acceptance against [`native-enhance-pptx-rollout-thresholds.md`](native-enhance-pptx-rollout-thresholds.md).
6. Expand allowlist only after all block thresholds pass with sufficient samples.

## Rollback procedure

1. Set `ppt-master.native-enhance-pptx.production.enabled=false` and/or `execution-stop-enabled=true`.
2. Re-pin `ppt-master.repo-path` to the previous validated revision (retain checkout; do not auto-delete job workspaces).
3. Redeploy previous Java build if readiness expectedVersion must match.
4. Confirm existing completed jobs remain downloadable under ownership checks.
5. Record incident + version pair in the canary acceptance log.

## Non-goals

- Do not auto-enable cleanup deletion on upgrade.
- Do not pass `--force` as a recovery tool.
- Do not broaden allowlist while readiness is DOWN.
