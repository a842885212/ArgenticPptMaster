package domi.argenticpptmaster.service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模式无关的到期工作区清理扫描与回收删除。
 */
public final class ModeCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ModeCleanupService.class);

    private final ModeCleanupSupport support;

    public ModeCleanupService(ModeCleanupSupport support) {
        this.support = support;
    }

    public void scheduledCleanup() {
        if (!support.cleanupDryRunEnabled() && !support.cleanupDeletionEnabled()) {
            return;
        }
        Instant started = Instant.now();
        boolean dryRun = !support.cleanupDeletionEnabled() || support.cleanupDryRunEnabled();
        try {
            scanCandidates(dryRun);
            retryRecycleLeftovers(dryRun);
            support.recordCleanupSuccess(Duration.between(started, Instant.now()));
        } catch (RuntimeException ex) {
            support.recordCleanupFailure(Duration.between(started, Instant.now()));
            throw ex;
        }
    }

    /** 扫描 workspace/jobs 直接子目录，返回候选任务 ID。 */
    public List<UUID> scanCandidates(boolean dryRun) {
        Path jobsRoot = support.jobsRoot();
        if (!Files.isDirectory(jobsRoot)) {
            return List.of();
        }
        List<UUID> candidates = new ArrayList<>();
        try (Stream<Path> entries = Files.list(jobsRoot)) {
            entries.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(entry -> considerCandidate(entry, dryRun, candidates));
        } catch (IOException ex) {
            log.warn(support.scanFailedLogMessage());
        }
        return List.copyOf(candidates);
    }

    void retryRecycleLeftovers(boolean dryRun) {
        Path recycleRoot = support.recycleRoot();
        if (!Files.isDirectory(recycleRoot) || dryRun) {
            return;
        }
        try (Stream<Path> entries = Files.list(recycleRoot)) {
            entries.filter(Files::isDirectory)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .forEach(this::retryDeleteRecycleEntry);
        } catch (IOException ex) {
            log.warn(support.recycleScanFailedLogMessage());
        }
    }

    private void considerCandidate(Path jobDir, boolean dryRun, List<UUID> candidates) {
        if (Files.isSymbolicLink(jobDir)) {
            support.auditCleanup("cleanup_candidate_skipped", null, "SYMLINK_JOB_DIR", dryRun);
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
        Optional<ModeLifecycleManifest> manifestOptional;
        try {
            manifestOptional = support.lifecycleStore().read(jobDir);
        } catch (RuntimeException ex) {
            support.auditCleanup("cleanup_candidate_skipped", jobId, "CORRUPT_MANIFEST", dryRun);
            return;
        }
        if (manifestOptional.isEmpty()) {
            support.auditCleanup("cleanup_candidate_skipped", jobId, "MISSING_MANIFEST", dryRun);
            return;
        }
        ModeLifecycleManifest manifest = manifestOptional.get();
        if (!jobId.equals(manifest.jobId())) {
            support.auditCleanup("cleanup_candidate_skipped", jobId, "JOB_ID_MISMATCH", dryRun);
            return;
        }
        if (manifest.terminalAt() == null) {
            support.auditCleanup("cleanup_candidate_skipped", jobId, "NONTERMINAL", dryRun);
            return;
        }
        if (manifest.cleanupState() == ModeLifecycleManifest.CleanupState.ACTIVE
                && manifest.retentionDeadline() == null) {
            support.auditCleanup("cleanup_candidate_skipped", jobId, "ACTIVE_WITHOUT_DEADLINE", dryRun);
            return;
        }
        Instant deadline = manifest.retentionDeadline();
        if (deadline == null || Instant.now().isBefore(deadline)) {
            support.auditCleanup("cleanup_candidate_skipped", jobId, "RETENTION_NOT_EXPIRED", dryRun);
            return;
        }
        if (manifest.cleanupState() == ModeLifecycleManifest.CleanupState.DELETED
                || manifest.cleanupState() == ModeLifecycleManifest.CleanupState.ISOLATED) {
            return;
        }
        candidates.add(jobId);
        support.auditCleanup("cleanup_candidate", jobId, "EXPIRED_TERMINAL", dryRun);
        if (dryRun) {
            return;
        }
        cleanupExpiredJob(jobDir, jobId);
    }

    private void cleanupExpiredJob(Path jobDir, UUID jobId) {
        try {
            support.lifecycleStore().markTombstoned(jobDir);
            support.auditCleanup("cleanup_tombstoned", jobId, "OK", false);
        } catch (RuntimeException ex) {
            support.auditCleanup("cleanup_tombstone_failed", jobId, "TOMBSTONE_FAILED", false);
            return;
        }
        Path recycleRoot = support.recycleRoot();
        Path recycleDir = recycleRoot.resolve(jobId + "-" + Instant.now().toEpochMilli()).normalize();
        if (!recycleDir.startsWith(recycleRoot)) {
            support.auditCleanup("cleanup_isolate_failed", jobId, "RECYCLE_ESCAPE", false);
            return;
        }
        try {
            Files.createDirectories(recycleRoot);
            moveDirectory(jobDir, recycleDir);
            support.lifecycleStore().markIsolated(recycleDir);
            support.auditCleanup("cleanup_isolated", jobId, "OK", false);
        } catch (IOException | RuntimeException ex) {
            support.auditCleanup("cleanup_isolate_failed", jobId, "ISOLATE_FAILED", false);
            return;
        }
        try {
            deleteRecursively(recycleDir);
            support.auditCleanup("cleanup_deleted", jobId, "OK", false);
        } catch (IOException ex) {
            support.auditCleanup("cleanup_delete_failed", jobId, "DELETE_FAILED", false);
        }
    }

    private void retryDeleteRecycleEntry(Path recycleDir) {
        UUID jobId = parseRecycleJobId(recycleDir.getFileName().toString()).orElse(null);
        try {
            deleteRecursively(recycleDir);
            support.auditCleanup("cleanup_deleted", jobId, "RECYCLE_RETRY_OK", false);
        } catch (IOException ex) {
            support.auditCleanup("cleanup_delete_failed", jobId, "RECYCLE_RETRY_FAILED", false);
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
