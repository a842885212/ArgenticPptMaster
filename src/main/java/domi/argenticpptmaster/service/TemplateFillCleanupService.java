package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.security.PptAccessContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 扫描并清理已到期的模板填充任务工作区。
 */
@Component
public class TemplateFillCleanupService {

    private final ModeCleanupService cleanup;

    public TemplateFillCleanupService(
            PptMasterProperties properties,
            TemplateFillLifecycleStore lifecycleStore,
            TemplateFillAuditSink auditSink) {
        this(properties, lifecycleStore, auditSink, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TemplateFillCleanupService(
            PptMasterProperties properties,
            TemplateFillLifecycleStore lifecycleStore,
            TemplateFillAuditSink auditSink,
            TemplateFillTelemetry telemetry) {
        this.cleanup = new ModeCleanupService(new TemplateFillCleanupSupport(
                properties, lifecycleStore, auditSink, telemetry));
    }

    @Scheduled(fixedDelayString = "${ppt-master.template-fill-cleanup-interval-ms:3600000}")
    public void scheduledCleanup() {
        cleanup.scheduledCleanup();
    }

    /** 扫描 workspace/jobs 直接子目录，返回候选任务 ID。 */
    public List<UUID> scanCandidates(boolean dryRun) {
        return cleanup.scanCandidates(dryRun);
    }

    void retryRecycleLeftovers(boolean dryRun) {
        cleanup.retryRecycleLeftovers(dryRun);
    }

    private static final class TemplateFillCleanupSupport implements ModeCleanupSupport {

        private final PptMasterProperties properties;
        private final TemplateFillLifecycleStore lifecycleStore;
        private final TemplateFillAuditSink auditSink;
        private final TemplateFillTelemetry telemetry;

        private TemplateFillCleanupSupport(
                PptMasterProperties properties,
                TemplateFillLifecycleStore lifecycleStore,
                TemplateFillAuditSink auditSink,
                TemplateFillTelemetry telemetry) {
            this.properties = properties;
            this.lifecycleStore = lifecycleStore;
            this.auditSink = auditSink;
            this.telemetry = telemetry;
        }

        @Override
        public boolean cleanupDryRunEnabled() {
            return properties.templateFillProduction().cleanupDryRunEnabled();
        }

        @Override
        public boolean cleanupDeletionEnabled() {
            return properties.templateFillProduction().cleanupDeletionEnabled();
        }

        @Override
        public Path jobsRoot() {
            return properties.workspacePath().toAbsolutePath().normalize().resolve("jobs").normalize();
        }

        @Override
        public Path recycleRoot() {
            return properties.workspacePath().toAbsolutePath().normalize().resolve("recycle").normalize();
        }

        @Override
        public ModeLifecycleStore lifecycleStore() {
            return lifecycleStore.core();
        }

        @Override
        public void auditCleanup(String action, UUID jobId, String reasonCode, boolean dryRun) {
            PptAccessContext internal = PptAccessContext.forInternalService();
            auditSink.record(
                    action,
                    jobId,
                    TemplateFillLifecycleStore.digestSubject(internal.subjectId()),
                    TemplateFillLifecycleStore.digestTenant(internal.tenantId()),
                    dryRun ? "DRY_RUN" : "EXECUTE",
                    reasonCode);
        }

        @Override
        public void recordCleanupSuccess(Duration elapsed) {
            if (telemetry != null) {
                telemetry.recordStage(
                        TemplateFillTelemetry.Stage.CLEANUP,
                        TemplateFillTelemetry.Outcome.SUCCESS,
                        elapsed);
            }
        }

        @Override
        public void recordCleanupFailure(Duration elapsed) {
            if (telemetry != null) {
                telemetry.recordStage(
                        TemplateFillTelemetry.Stage.CLEANUP,
                        TemplateFillTelemetry.Outcome.FAILURE,
                        elapsed);
            }
        }

        @Override
        public String scanFailedLogMessage() {
            return "template_fill_cleanup_scan_failed: reason=IO_ERROR";
        }

        @Override
        public String recycleScanFailedLogMessage() {
            return "template_fill_cleanup_recycle_scan_failed: reason=IO_ERROR";
        }
    }
}
