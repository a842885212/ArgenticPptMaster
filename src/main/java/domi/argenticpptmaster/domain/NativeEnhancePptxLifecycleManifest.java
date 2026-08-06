package domi.argenticpptmaster.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Native-enhance 任务生命周期清单，对应 {@code lifecycle/manifest.json}。
 * <p>
 * 不含业务原文或原始身份标识，仅保存归属摘要与保留/清理元数据。
 * 音频等敏感工件仅以计数形式出现，不写入内容。
 * </p>
 */
public record NativeEnhancePptxLifecycleManifest(
        UUID jobId,
        String ownershipDigest,
        Instant createdAt,
        Instant terminalAt,
        Instant retentionDeadline,
        Instant lastDownloadedAt,
        CleanupState cleanupState,
        Map<String, Integer> artifactCounts,
        String schemaVersion) {

    public static final String SCHEMA_VERSION = "native_enhance_pptx_lifecycle.v1";

    public enum CleanupState {
        ACTIVE,
        TOMBSTONED,
        ISOLATED,
        DELETED
    }

    public NativeEnhancePptxLifecycleManifest {
        if (artifactCounts == null || artifactCounts.isEmpty()) {
            artifactCounts = defaultArtifactCounts();
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

    public static Map<String, Integer> defaultArtifactCounts() {
        return Map.of(
                "source", 0,
                "notes", 0,
                "audio", 0,
                "plan", 0,
                "validation", 0,
                "diagnostic", 0,
                "export", 0);
    }

    public int artifactCount(String category) {
        return artifactCounts.getOrDefault(category, 0);
    }
}
