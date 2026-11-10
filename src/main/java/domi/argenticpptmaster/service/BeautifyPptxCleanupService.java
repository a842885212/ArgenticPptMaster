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
 * 扫描并清理已到期的 beautify 任务工作区。
 */
@Component
public class BeautifyPptxCleanupService {

    private final ModeCleanupService cleanup;

    public BeautifyPptxCleanupService(
            PptMasterProperties properties,
            BeautifyPptxLifecycleStore lifecycleStore,
            BeautifyPptxAuditSink auditSink) {
        this(properties, lifecycleStore, auditSink, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BeautifyPptxCleanupService(
            PptMasterProperties properties,
            BeautifyPptxLifecycleStore lifecycleStore,
            BeautifyPptxAuditSink auditSink,
            BeautifyPptxTelemetry telemetry) {
        this.cleanup = new ModeCleanupService(new BeautifyCleanupSupport(
                properties, lifecycleStore, auditSink, telemetry));
    }

    @Scheduled(fixedDelayString = "${ppt-master.beautify-pptx-cleanup-interval-ms:3600000}")
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

    private static final class BeautifyCleanupSupport implements ModeCleanupSupport {

        private final PptMasterProperties properties;
        private final BeautifyPptxLifecycleStore lifecycleStore;
        private final BeautifyPptxAuditSink auditSink;
        private final BeautifyPptxTelemetry telemetry;

        private BeautifyCleanupSupport(
                PptMasterProperties properties,
                BeautifyPptxLifecycleStore lifecycleStore,
                BeautifyPptxAuditSink auditSink,
                BeautifyPptxTelemetry telemetry) {
            this.properties = properties;
            this.lifecycleStore = lifecycleStore;
            this.auditSink = auditSink;
            this.telemetry = telemetry;
        }

        @Override
        public boolean cleanupDryRunEnabled() {
            return properties.beautifyPptx().production().cleanupDryRunEnabled();
        }

        @Override
        public boolean cleanupDeletionEnabled() {
            return properties.beautifyPptx().production().cleanupDeletionEnabled();
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
                    BeautifyPptxLifecycleStore.digestSubject(internal.subjectId()),
                    BeautifyPptxLifecycleStore.digestTenant(internal.tenantId()),
                    dryRun ? "DRY_RUN" : "EXECUTE",
                    reasonCode);
        }

        @Override
        public void recordCleanupSuccess(Duration elapsed) {
            if (telemetry != null) {
                telemetry.recordStage(
                        BeautifyPptxTelemetry.Stage.CLEANUP,
                        BeautifyPptxTelemetry.Outcome.SUCCESS,
                        elapsed);
            }
        }

        @Override
        public void recordCleanupFailure(Duration elapsed) {
            if (telemetry != null) {
                telemetry.recordStage(
                        BeautifyPptxTelemetry.Stage.CLEANUP,
                        BeautifyPptxTelemetry.Outcome.FAILURE,
                        elapsed);
            }
        }

        @Override
        public String scanFailedLogMessage() {
            return "beautify_pptx_cleanup_scan_failed: reason=IO_ERROR";
        }

        @Override
        public String recycleScanFailedLogMessage() {
            return "beautify_pptx_cleanup_recycle_scan_failed: reason=IO_ERROR";
        }
    }
}
