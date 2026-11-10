package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.TemplateFillLifecycleManifest;
import domi.argenticpptmaster.domain.TemplateFillLifecycleManifest.CleanupState;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 持久化模板填充生命周期清单 {@code lifecycle/manifest.json}。
 */
@Component
public class TemplateFillLifecycleStore {

    static final String MANIFEST_FILE = ModeLifecycleStore.MANIFEST_FILE;
    static final String LIFECYCLE_DIR = ModeLifecycleStore.LIFECYCLE_DIR;

    private final ModeLifecycleStore store;

    @Autowired
    public TemplateFillLifecycleStore(PptMasterProperties properties) {
        this(properties, new ObjectMapper());
    }

    TemplateFillLifecycleStore(PptMasterProperties properties, ObjectMapper objectMapper) {
        this.store = new ModeLifecycleStore(new TemplateFillLifecycleSupport(properties), objectMapper);
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

    public Optional<TemplateFillLifecycleManifest> read(PptJob job) {
        return read(job.workspacePath());
    }

    public Optional<TemplateFillLifecycleManifest> read(Path workspace) {
        return store.read(workspace).map(TemplateFillLifecycleStore::toTyped);
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

    private static TemplateFillLifecycleManifest toTyped(ModeLifecycleManifest manifest) {
        return new TemplateFillLifecycleManifest(
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

    private static final class TemplateFillLifecycleSupport implements ModeLifecycleSupport {

        private final PptMasterProperties properties;

        private TemplateFillLifecycleSupport(PptMasterProperties properties) {
            this.properties = properties;
        }

        @Override
        public String schemaVersion() {
            return TemplateFillLifecycleManifest.SCHEMA_VERSION;
        }

        @Override
        public String ownershipMissingMessage() {
            return "template-fill ownership is missing";
        }

        @Override
        public Map<String, Integer> defaultArtifactCounts() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("template", 0);
            counts.put("content", 0);
            counts.put("exports", 0);
            counts.put("diagnostics", 0);
            return counts;
        }

        @Override
        public Map<String, Integer> countArtifacts(PptJob job) {
            Path workspace = job.workspacePath().toAbsolutePath().normalize();
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("template", ModeLifecycleStore.countRegularFiles(workspace.resolve("uploads/template")));
            counts.put("content", ModeLifecycleStore.countRegularFiles(workspace.resolve("uploads/content")));
            counts.put("exports", ModeLifecycleStore.countRegularFiles(workspace.resolve("exports")));
            counts.put("diagnostics", ModeLifecycleStore.countRegularFiles(workspace.resolve("diagnostics")));
            return counts;
        }

        @Override
        public Duration retentionForTerminalStatus(PptJobStatus status) {
            var production = properties.templateFillProduction();
            return status == PptJobStatus.COMPLETED
                    ? production.retentionCompleted()
                    : production.retentionFailed();
        }
    }
}
