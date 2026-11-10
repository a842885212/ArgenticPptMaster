package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.NativeEnhancePptxLifecycleManifest;
import domi.argenticpptmaster.domain.NativeEnhancePptxLifecycleManifest.CleanupState;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 持久化 native-enhance 生命周期清单 {@code lifecycle/manifest.json}。
 */
@Component
public class NativeEnhancePptxLifecycleStore {

    static final String MANIFEST_FILE = ModeLifecycleStore.MANIFEST_FILE;
    static final String LIFECYCLE_DIR = ModeLifecycleStore.LIFECYCLE_DIR;

    private final ModeLifecycleStore store;

    @Autowired
    public NativeEnhancePptxLifecycleStore(PptMasterProperties properties) {
        this(properties, new ObjectMapper());
    }

    NativeEnhancePptxLifecycleStore(PptMasterProperties properties, ObjectMapper objectMapper) {
        this.store = new ModeLifecycleStore(new NativeEnhanceLifecycleSupport(properties), objectMapper);
    }

    /** 工作区创建后写入初始清单。 */
    public void initialize(PptJob job) {
        store.initialize(job);
    }

    /** 任务进入终态时写入保留截止时间。 */
    public void markTerminal(PptJob job, Duration retention) {
        store.markTerminal(job, retention);
    }

    /** 诊断包生成成功后刷新 artifact 计数，不延长保留期。 */
    public void recordDiagnostic(PptJob job) {
        store.recordDiagnostic(job);
    }

    /** 授权下载成功后更新最近下载时间，不延长保留期。 */
    public void recordDownload(PptJob job) {
        store.recordDownload(job);
    }

    public void markTombstoned(PptJob job) {
        markTombstoned(job.workspacePath());
    }

    public void markTombstoned(Path workspace) {
        store.markTombstoned(workspace);
    }

    public void markIsolated(PptJob job) {
        markIsolated(job.workspacePath());
    }

    public void markIsolated(Path workspace) {
        store.markIsolated(workspace);
    }

    public Optional<NativeEnhancePptxLifecycleManifest> read(PptJob job) {
        return read(job.workspacePath());
    }

    public Optional<NativeEnhancePptxLifecycleManifest> read(Path workspace) {
        return store.read(workspace).map(NativeEnhancePptxLifecycleStore::toTyped);
    }

    public Duration retentionForTerminalStatus(PptJobStatus status) {
        return store.retentionForTerminalStatus(status);
    }

    public static String digestOwnership(String subjectId, String tenantId) {
        return ModeLifecycleStore.digestOwnership(subjectId, tenantId);
    }

    public static String digestSubject(String subjectId) {
        return ModeLifecycleStore.digestSubject(subjectId);
    }

    public static String digestTenant(String tenantId) {
        return ModeLifecycleStore.digestTenant(tenantId);
    }

    static Path manifestPath(Path workspace) {
        return ModeLifecycleStore.manifestPath(workspace);
    }

    ModeLifecycleStore core() {
        return store;
    }

    private static NativeEnhancePptxLifecycleManifest toTyped(ModeLifecycleManifest manifest) {
        return new NativeEnhancePptxLifecycleManifest(
                manifest.jobId(),
                manifest.ownershipDigest(),
                manifest.createdAt(),
                manifest.terminalAt(),
                manifest.retentionDeadline(),
                manifest.lastDownloadedAt(),
                CleanupState.valueOf(manifest.cleanupState().name()),
                manifest.artifactCounts(),
                manifest.schemaVersion());
    }

    private static final class NativeEnhanceLifecycleSupport implements ModeLifecycleSupport {

        private final PptMasterProperties properties;

        private NativeEnhanceLifecycleSupport(PptMasterProperties properties) {
            this.properties = properties;
        }

        @Override
        public String schemaVersion() {
            return NativeEnhancePptxLifecycleManifest.SCHEMA_VERSION;
        }

        @Override
        public String ownershipMissingMessage() {
            return "native-enhance ownership is missing";
        }

        @Override
        public Map<String, Integer> defaultArtifactCounts() {
            return NativeEnhancePptxLifecycleManifest.defaultArtifactCounts();
        }

        @Override
        public Map<String, Integer> countArtifacts(PptJob job) {
            Path workspace = job.workspacePath().toAbsolutePath().normalize();
            Path project = job.projectPath().map(Path::toAbsolutePath).map(Path::normalize).orElse(null);
            Map<String, Integer> counts = new LinkedHashMap<>();
            int sourceCount = ModeLifecycleStore.countRegularFiles(workspace.resolve("uploads"));
            int notesCount = 0;
            int audioCount = 0;
            int planCount = 0;
            int validationCount = ModeLifecycleStore.countRegularFiles(workspace.resolve("validation"));
            int exportCount = ModeLifecycleStore.countRegularFiles(workspace.resolve("exports"));
            if (project != null) {
                sourceCount += ModeLifecycleStore.countRegularFiles(project.resolve("sources"));
                notesCount = ModeLifecycleStore.countRegularFiles(project.resolve("notes"));
                audioCount = ModeLifecycleStore.countRegularFiles(project.resolve("audio"))
                        + ModeLifecycleStore.countRegularFiles(project.resolve("media"));
                planCount = ModeLifecycleStore.countNamed(project.resolve("analysis"), "enhancement_plan.json")
                        + ModeLifecycleStore.countNamed(project.resolve("analysis"), "enhancement_plan.draft.json");
                validationCount += ModeLifecycleStore.countRegularFiles(project.resolve("validation"));
                exportCount += ModeLifecycleStore.countRegularFiles(project.resolve("exports"));
            }
            counts.put("source", sourceCount);
            counts.put("notes", notesCount);
            counts.put("audio", audioCount);
            counts.put("plan", planCount);
            counts.put("validation", validationCount);
            counts.put("diagnostic", ModeLifecycleStore.countRegularFiles(workspace.resolve("diagnostics")));
            counts.put("export", exportCount);
            return counts;
        }

        @Override
        public Duration retentionForTerminalStatus(PptJobStatus status) {
            var production = properties.nativeEnhancePptx().production();
            return status == PptJobStatus.COMPLETED
                    ? production.retentionCompleted()
                    : production.retentionFailed();
        }
    }
}
