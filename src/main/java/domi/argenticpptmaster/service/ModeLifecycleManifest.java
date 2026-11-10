package domi.argenticpptmaster.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 模式无关的生命周期清单快照，对应 {@code lifecycle/manifest.json} 的公共字段。
 */
public record ModeLifecycleManifest(
        UUID jobId,
        String ownershipDigest,
        Instant createdAt,
        Instant terminalAt,
        Instant retentionDeadline,
        Instant lastDownloadedAt,
        CleanupState cleanupState,
        Map<String, Integer> artifactCounts,
        String schemaVersion) {

    public enum CleanupState {
        ACTIVE,
        TOMBSTONED,
        ISOLATED,
        DELETED
    }

    public ModeLifecycleManifest {
        if (artifactCounts == null) {
            artifactCounts = Map.of();
        } else {
            artifactCounts = Map.copyOf(artifactCounts);
        }
        if (cleanupState == null) {
            cleanupState = CleanupState.ACTIVE;
        }
    }
}
