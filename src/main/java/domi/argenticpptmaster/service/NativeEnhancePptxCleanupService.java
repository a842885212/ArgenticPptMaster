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
 * 扫描并清理已到期的 native-enhance 任务工作区。
 */
@Component
public class NativeEnhancePptxCleanupService {

    private final ModeCleanupService cleanup;

    public NativeEnhancePptxCleanupService(
            PptMasterProperties properties,
            NativeEnhancePptxLifecycleStore lifecycleStore,
            NativeEnhancePptxAuditSink auditSink) {
        this(properties, lifecycleStore, auditSink, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NativeEnhancePptxCleanupService(
            PptMasterProperties properties,
            NativeEnhancePptxLifecycleStore lifecycleStore,
            NativeEnhancePptxAuditSink auditSink,
            NativeEnhancePptxTelemetry telemetry) {
        this.cleanup = new ModeCleanupService(new NativeEnhanceCleanupSupport(
                properties, lifecycleStore, auditSink, telemetry));
    }

    @Scheduled(fixedDelayString = "${ppt-master.native-enhance-pptx-cleanup-interval-ms:3600000}")
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

    private static final class NativeEnhanceCleanupSupport implements ModeCleanupSupport {

        private final PptMasterProperties properties;
        private final NativeEnhancePptxLifecycleStore lifecycleStore;
        private final NativeEnhancePptxAuditSink auditSink;
        private final NativeEnhancePptxTelemetry telemetry;

        private NativeEnhanceCleanupSupport(
                PptMasterProperties properties,
                NativeEnhancePptxLifecycleStore lifecycleStore,
                NativeEnhancePptxAuditSink auditSink,
                NativeEnhancePptxTelemetry telemetry) {
            this.properties = properties;
            this.lifecycleStore = lifecycleStore;
            this.auditSink = auditSink;
            this.telemetry = telemetry;
        }

        @Override
        public boolean cleanupDryRunEnabled() {
            return properties.nativeEnhancePptx().production().cleanupDryRunEnabled();
        }

        @Override
        public boolean cleanupDeletionEnabled() {
            return properties.nativeEnhancePptx().production().cleanupDeletionEnabled();
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
                    NativeEnhancePptxLifecycleStore.digestSubject(internal.subjectId()),
                    NativeEnhancePptxLifecycleStore.digestTenant(internal.tenantId()),
                    dryRun ? "DRY_RUN" : "EXECUTE",
                    reasonCode);
        }

        @Override
        public void recordCleanupSuccess(Duration elapsed) {
            if (telemetry != null) {
                telemetry.recordStage(
                        NativeEnhancePptxTelemetry.Stage.CLEANUP,
                        NativeEnhancePptxTelemetry.Outcome.SUCCESS,
                        elapsed);
            }
        }

        @Override
        public void recordCleanupFailure(Duration elapsed) {
            if (telemetry != null) {
                telemetry.recordStage(
                        NativeEnhancePptxTelemetry.Stage.CLEANUP,
                        NativeEnhancePptxTelemetry.Outcome.FAILURE,
                        elapsed);
            }
        }

        @Override
        public String scanFailedLogMessage() {
            return "native_enhance_pptx_cleanup_scan_failed: reason=IO_ERROR";
        }

        @Override
        public String recycleScanFailedLogMessage() {
            return "native_enhance_pptx_cleanup_recycle_scan_failed: reason=IO_ERROR";
        }
    }
}
