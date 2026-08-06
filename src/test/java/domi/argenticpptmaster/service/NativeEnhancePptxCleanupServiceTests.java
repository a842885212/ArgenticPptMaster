package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.NativeEnhancePptxProductionProperties;
import domi.argenticpptmaster.config.NativeEnhancePptxProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.NativeEnhancePptxLifecycleManifest.CleanupState;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeEnhancePptxCleanupServiceTests {

    @TempDir
    Path tempDir;

    private Path jobsRoot;
    private NativeEnhancePptxLifecycleStore lifecycleStore;
    private NativeEnhancePptxAuditSink auditSink;

    @BeforeEach
    void setUp() {
        jobsRoot = tempDir.resolve("jobs");
        lifecycleStore = new NativeEnhancePptxLifecycleStore(PptMasterProperties.forNativeEnhanceTest(tempDir, tempDir, "tenant-a"));
        auditSink = new NativeEnhancePptxAuditSink();
    }

    @Test
    void dryRunReportsExpiredCandidateWithoutDeleting() throws Exception {
        UUID jobId = seedExpiredJob(Duration.ofDays(-1));

        NativeEnhancePptxCleanupService service = cleanupService(true, false);
        List<UUID> candidates = service.scanCandidates(true);

        assertThat(candidates).containsExactly(jobId);
        assertThat(Files.exists(jobsRoot.resolve(jobId.toString()))).isTrue();
        assertThat(lifecycleStore.read(jobsRoot.resolve(jobId.toString())).orElseThrow().cleanupState())
                .isEqualTo(CleanupState.ACTIVE);
    }

    @Test
    void deletesExpiredJobWhenDeletionEnabled() throws Exception {
        UUID jobId = seedExpiredJob(Duration.ofDays(-1));

        NativeEnhancePptxCleanupService service = cleanupService(false, true);
        service.scanCandidates(false);

        assertThat(Files.exists(jobsRoot.resolve(jobId.toString()))).isFalse();
        assertThat(Files.list(tempDir.resolve("recycle")).findAny()).isEmpty();
    }

    @Test
    void skipsActiveNonterminalJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = jobsRoot.resolve(jobId.toString());
        Files.createDirectories(workspace);
        PptJob job = new PptJob(jobId, "demo", "ppt169", null, PptWorkflowMode.NATIVE_ENHANCE_PPTX, workspace);
        job.assignOwnership("user-1", "tenant-a");
        lifecycleStore.initialize(job);

        NativeEnhancePptxCleanupService service = cleanupService(false, true);
        List<UUID> candidates = service.scanCandidates(false);

        assertThat(candidates).isEmpty();
        assertThat(Files.exists(workspace)).isTrue();
    }

    @Test
    void skipsSymlinkJobDirectory() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path realDir = jobsRoot.resolve("real");
        Files.createDirectories(realDir);
        Files.createSymbolicLink(jobsRoot.resolve(jobId.toString()), realDir);

        NativeEnhancePptxCleanupService service = cleanupService(true, false);
        List<UUID> candidates = service.scanCandidates(true);

        assertThat(candidates).isEmpty();
    }

    @Test
    void skipsCorruptedManifest() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = jobsRoot.resolve(jobId.toString());
        Path manifestPath = NativeEnhancePptxLifecycleStore.manifestPath(workspace);
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(manifestPath, "{not-json");

        NativeEnhancePptxCleanupService service = cleanupService(true, false);
        List<UUID> candidates = service.scanCandidates(true);

        assertThat(candidates).isEmpty();
    }

    @Test
    void retriesRecycleLeftovers() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path recycleDir = tempDir.resolve("recycle").resolve(jobId + "-20260101120000000");
        Files.createDirectories(recycleDir);
        Files.writeString(recycleDir.resolve("leftover.txt"), "orphan");

        NativeEnhancePptxCleanupService service = cleanupService(false, true);
        service.retryRecycleLeftovers(false);

        assertThat(Files.exists(recycleDir)).isFalse();
    }

    private UUID seedExpiredJob(Duration retentionOffset) throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = jobsRoot.resolve(jobId.toString());
        Files.createDirectories(workspace);
        PptJob job = new PptJob(jobId, "demo", "ppt169", null, PptWorkflowMode.NATIVE_ENHANCE_PPTX, workspace);
        job.assignOwnership("user-1", "tenant-a");
        lifecycleStore.initialize(job);
        job.fail("boom");
        lifecycleStore.markTerminal(job, Duration.ofHours(1));

        Instant expiredDeadline = Instant.now().plus(retentionOffset);
        Path manifestPath = NativeEnhancePptxLifecycleStore.manifestPath(workspace);
        String json = Files.readString(manifestPath)
                .replaceFirst("\"retentionDeadline\" : \"[^\"]+\"",
                        "\"retentionDeadline\" : \"" + expiredDeadline + "\"");
        Files.writeString(manifestPath, json);
        return jobId;
    }

    private NativeEnhancePptxCleanupService cleanupService(boolean dryRunEnabled, boolean deletionEnabled) {
        NativeEnhancePptxProductionProperties production = new NativeEnhancePptxProductionProperties(
                true,
                List.of("tenant-a"),
                "ADMIN",
                NativeEnhancePptxProductionProperties.defaults().retentionCompleted(),
                NativeEnhancePptxProductionProperties.defaults().retentionFailed(),
                NativeEnhancePptxProductionProperties.defaults().retentionDiagnostic(),
                NativeEnhancePptxProductionProperties.defaults().retentionMin(),
                NativeEnhancePptxProductionProperties.defaults().retentionMax(),
                dryRunEnabled,
                deletionEnabled,
                false,
                false);
        PptMasterProperties properties = new PptMasterProperties(
                tempDir, tempDir, "python3", Duration.ofMinutes(1), null, 1024,
                0, 0, 0, 0, null, null, null, null, null, null,
                new NativeEnhancePptxProperties(0, null, null, null, null, null, 0, 0, null, null, null, production));
        return new NativeEnhancePptxCleanupService(properties, lifecycleStore, auditSink);
    }
}
