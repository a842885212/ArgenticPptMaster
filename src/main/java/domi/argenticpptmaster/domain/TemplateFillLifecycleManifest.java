package domi.argenticpptmaster.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 模板填充任务生命周期清单，对应 {@code lifecycle/manifest.json}。
 * <p>
 * 不含业务原文或原始身份标识，仅保存归属摘要与保留/清理元数据。
 * </p>
 */
public record TemplateFillLifecycleManifest(
        UUID jobId,
        String ownershipDigest,
        Instant createdAt,
        Instant terminalAt,
        Instant retentionDeadline,
        Instant lastDownloadedAt,
        CleanupState cleanupState,
        Map<String, Integer> artifactCounts,
        String schemaVersion) {

    public static final String SCHEMA_VERSION = "template_fill_lifecycle.v1";

    public enum CleanupState {
        ACTIVE,
        TOMBSTONED,
        ISOLATED,
        DELETED
    }

    public TemplateFillLifecycleManifest {
        if (artifactCounts == null || artifactCounts.isEmpty()) {
            artifactCounts = Map.of(
                    "template", 0,
                    "content", 0,
                    "exports", 0,
                    "diagnostics", 0);
        } else {
            artifactCounts = Map.copyOf(artifactCounts);
        }
        if (schemaVersion == null || schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (cleanupState == null) {
            cleanupState = CleanupState.ACTIVE;
        }
    }

    public int artifactCount(String category) {
        return artifactCounts.getOrDefault(category, 0);
    }
}
