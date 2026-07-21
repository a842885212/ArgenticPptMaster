package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.TemplateFillLifecycleManifest;
import domi.argenticpptmaster.domain.TemplateFillLifecycleManifest.CleanupState;
import domi.argenticpptmaster.security.PptAccessContext;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 扫描并清理已到期的模板填充任务工作区。
 */
@Component
public class TemplateFillCleanupService {

    private static final Logger log = LoggerFactory.getLogger(TemplateFillCleanupService.class);

    private final PptMasterProperties properties;
    private final TemplateFillLifecycleStore lifecycleStore;
    private final TemplateFillAuditSink auditSink;
    private final TemplateFillTelemetry telemetry;

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
        this.properties = properties;
        this.lifecycleStore = lifecycleStore;
        this.auditSink = auditSink;
        this.telemetry = telemetry;
    }

    @Scheduled(fixedDelayString = "${ppt-master.template-fill-cleanup-interval-ms:3600000}")
    public void scheduledCleanup() {
        var production = properties.templateFillProduction();
        if (!production.cleanupDryRunEnabled() && !production.cleanupDeletionEnabled()) {
            return;
        }
        Instant started = Instant.now();
        boolean dryRun = !production.cleanupDeletionEnabled() || production.cleanupDryRunEnabled();
        try {
            scanCandidates(dryRun);
            retryRecycleLeftovers(dryRun);
            if (telemetry != null) {
                telemetry.recordStage(
                        TemplateFillTelemetry.Stage.CLEANUP,
                        TemplateFillTelemetry.Outcome.SUCCESS,
                        java.time.Duration.between(started, Instant.now()));
            }
        } catch (RuntimeException ex) {
            if (telemetry != null) {
                telemetry.recordStage(
                        TemplateFillTelemetry.Stage.CLEANUP,
                        TemplateFillTelemetry.Outcome.FAILURE,
                        java.time.Duration.between(started, Instant.now()));
            }
            throw ex;
        }
    }

    /** 扫描 workspace/jobs 直接子目录，返回候选任务 ID。 */
    public List<UUID> scanCandidates(boolean dryRun) {
        Path jobsRoot = jobsRoot();
        if (!Files.isDirectory(jobsRoot)) {
            return List.of();
        }
        List<UUID> candidates = new ArrayList<>();
        try (Stream<Path> entries = Files.list(jobsRoot)) {
            entries.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(entry -> considerCandidate(entry, dryRun, candidates));
        } catch (IOException ex) {
            log.warn("template_fill_cleanup_scan_failed: reason=IO_ERROR");
        }
        return List.copyOf(candidates);
    }

    private void considerCandidate(Path jobDir, boolean dryRun, List<UUID> candidates) {
        if (Files.isSymbolicLink(jobDir)) {
            auditCleanup("cleanup_candidate_skipped", null, "SYMLINK_JOB_DIR", dryRun);
            return;
        }
        if (!Files.isDirectory(jobDir)) {
            return;
        }
        UUID jobId;
        try {
            jobId = UUID.fromString(jobDir.getFileName().toString());
        } catch (IllegalArgumentException ex) {
            return;
        }
        Optional<TemplateFillLifecycleManifest> manifestOptional;
        try {
            manifestOptional = lifecycleStore.read(jobDir);
        } catch (RuntimeException ex) {
            auditCleanup("cleanup_candidate_skipped", jobId, "CORRUPT_MANIFEST", dryRun);
            return;
        }
        if (manifestOptional.isEmpty()) {
            auditCleanup("cleanup_candidate_skipped", jobId, "MISSING_MANIFEST", dryRun);
            return;
        }
        TemplateFillLifecycleManifest manifest = manifestOptional.get();
        if (!jobId.equals(manifest.jobId())) {
            auditCleanup("cleanup_candidate_skipped", jobId, "JOB_ID_MISMATCH", dryRun);
            return;
        }
        if (manifest.terminalAt() == null) {
            auditCleanup("cleanup_candidate_skipped", jobId, "NONTERMINAL", dryRun);
            return;
        }
        if (manifest.cleanupState() == CleanupState.ACTIVE && manifest.retentionDeadline() == null) {
            auditCleanup("cleanup_candidate_skipped", jobId, "ACTIVE_WITHOUT_DEADLINE", dryRun);
            return;
        }
        Instant deadline = manifest.retentionDeadline();
        if (deadline == null || Instant.now().isBefore(deadline)) {
            auditCleanup("cleanup_candidate_skipped", jobId, "RETENTION_NOT_EXPIRED", dryRun);
            return;
        }
        if (manifest.cleanupState() == CleanupState.DELETED || manifest.cleanupState() == CleanupState.ISOLATED) {
            return;
        }
        candidates.add(jobId);
        auditCleanup("cleanup_candidate", jobId, "EXPIRED_TERMINAL", dryRun);
        if (dryRun) {
            return;
        }
        cleanupExpiredJob(jobDir, jobId);
    }

    private void cleanupExpiredJob(Path jobDir, UUID jobId) {
        try {
            lifecycleStore.markTombstoned(jobDir);
            auditCleanup("cleanup_tombstoned", jobId, "OK", false);
        } catch (RuntimeException ex) {
            auditCleanup("cleanup_tombstone_failed", jobId, "TOMBSTONE_FAILED", false);
            return;
        }
        Path recycleDir = recycleRoot().resolve(jobId + "-" + Instant.now().toEpochMilli()).normalize();
        if (!recycleDir.startsWith(recycleRoot())) {
            auditCleanup("cleanup_isolate_failed", jobId, "RECYCLE_ESCAPE", false);
            return;
        }
        try {
            Files.createDirectories(recycleRoot());
            moveDirectory(jobDir, recycleDir);
            lifecycleStore.markIsolated(recycleDir);
            auditCleanup("cleanup_isolated", jobId, "OK", false);
        } catch (IOException | RuntimeException ex) {
            auditCleanup("cleanup_isolate_failed", jobId, "ISOLATE_FAILED", false);
            return;
        }
        try {
            deleteRecursively(recycleDir);
            auditCleanup("cleanup_deleted", jobId, "OK", false);
        } catch (IOException ex) {
            auditCleanup("cleanup_delete_failed", jobId, "DELETE_FAILED", false);
        }
    }

    void retryRecycleLeftovers(boolean dryRun) {
        Path recycleRoot = recycleRoot();
        if (!Files.isDirectory(recycleRoot) || dryRun) {
            return;
        }
        try (Stream<Path> entries = Files.list(recycleRoot)) {
            entries.filter(Files::isDirectory)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .forEach(this::retryDeleteRecycleEntry);
        } catch (IOException ex) {
            log.warn("template_fill_cleanup_recycle_scan_failed: reason=IO_ERROR");
        }
    }

    private void retryDeleteRecycleEntry(Path recycleDir) {
        UUID jobId = parseRecycleJobId(recycleDir.getFileName().toString()).orElse(null);
        try {
            deleteRecursively(recycleDir);
            auditCleanup("cleanup_deleted", jobId, "RECYCLE_RETRY_OK", false);
        } catch (IOException ex) {
            auditCleanup("cleanup_delete_failed", jobId, "RECYCLE_RETRY_FAILED", false);
        }
    }

    private static Optional<UUID> parseRecycleJobId(String directoryName) {
        int separator = directoryName.indexOf('-');
        if (separator <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(directoryName.substring(0, separator)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Path jobsRoot() {
        return properties.workspacePath().toAbsolutePath().normalize().resolve("jobs").normalize();
    }

    private Path recycleRoot() {
        return properties.workspacePath().toAbsolutePath().normalize().resolve("recycle").normalize();
    }

    private void auditCleanup(String action, UUID jobId, String reasonCode, boolean dryRun) {
        PptAccessContext internal = PptAccessContext.forInternalService();
        auditSink.record(
                action,
                jobId,
                TemplateFillLifecycleStore.digestSubject(internal.subjectId()),
                TemplateFillLifecycleStore.digestTenant(internal.tenantId()),
                dryRun ? "DRY_RUN" : "EXECUTE",
                reasonCode);
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    if (Files.isSymbolicLink(path)) {
                        throw new IOException("refusing to delete symbolic link");
                    }
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
        } catch (java.io.UncheckedIOException ex) {
            throw ex.getCause();
        }
    }
}
