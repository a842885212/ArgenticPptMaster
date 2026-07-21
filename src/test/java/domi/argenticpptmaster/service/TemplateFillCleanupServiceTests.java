package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.config.TemplateFillProductionProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.domain.TemplateFillLifecycleManifest.CleanupState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateFillCleanupServiceTests {

    @TempDir
    Path tempDir;

    private Path jobsRoot;
    private TemplateFillLifecycleStore lifecycleStore;
    private TemplateFillAuditSink auditSink;

    @BeforeEach
    void setUp() {
        jobsRoot = tempDir.resolve("jobs");
        lifecycleStore = new TemplateFillLifecycleStore(PptMasterProperties.forTemplateFillTest(tempDir, tempDir, "tenant-a"));
        auditSink = new TemplateFillAuditSink();
    }

    @Test
    void dryRunReportsExpiredCandidateWithoutDeleting() throws Exception {
        UUID jobId = seedExpiredJob(Duration.ofDays(-1));

        TemplateFillCleanupService service = cleanupService(true, false);
        List<UUID> candidates = service.scanCandidates(true);

        assertThat(candidates).containsExactly(jobId);
        assertThat(Files.exists(jobsRoot.resolve(jobId.toString()))).isTrue();
        assertThat(lifecycleStore.read(jobsRoot.resolve(jobId.toString())).orElseThrow().cleanupState())
                .isEqualTo(CleanupState.ACTIVE);
    }

    @Test
    void deletesExpiredJobWhenDeletionEnabled() throws Exception {
        UUID jobId = seedExpiredJob(Duration.ofDays(-1));

        TemplateFillCleanupService service = cleanupService(false, true);
        service.scanCandidates(false);

        assertThat(Files.exists(jobsRoot.resolve(jobId.toString()))).isFalse();
        assertThat(Files.list(tempDir.resolve("recycle")).findAny()).isEmpty();
    }

    @Test
    void skipsActiveNonterminalJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = jobsRoot.resolve(jobId.toString());
        Files.createDirectories(workspace);
        PptJob job = new PptJob(jobId, "demo", "ppt169", null, PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.assignOwnership("user-1", "tenant-a");
        lifecycleStore.initialize(job);

        TemplateFillCleanupService service = cleanupService(false, true);
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

        TemplateFillCleanupService service = cleanupService(true, false);
        List<UUID> candidates = service.scanCandidates(true);

        assertThat(candidates).isEmpty();
    }

    @Test
    void skipsCorruptedManifest() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = jobsRoot.resolve(jobId.toString());
        Path manifestPath = TemplateFillLifecycleStore.manifestPath(workspace);
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(manifestPath, "{not-json");

        TemplateFillCleanupService service = cleanupService(true, false);
        List<UUID> candidates = service.scanCandidates(true);

        assertThat(candidates).isEmpty();
    }

    @Test
    void retriesRecycleLeftovers() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path recycleDir = tempDir.resolve("recycle").resolve(jobId + "-20260101120000000");
        Files.createDirectories(recycleDir);
        Files.writeString(recycleDir.resolve("leftover.txt"), "orphan");

        TemplateFillCleanupService service = cleanupService(false, true);
        service.retryRecycleLeftovers(false);

        assertThat(Files.exists(recycleDir)).isFalse();
    }

    private UUID seedExpiredJob(Duration retentionOffset) throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = jobsRoot.resolve(jobId.toString());
        Files.createDirectories(workspace);
        PptJob job = new PptJob(jobId, "demo", "ppt169", null, PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.assignOwnership("user-1", "tenant-a");
        lifecycleStore.initialize(job);
        job.fail("boom");
        lifecycleStore.markTerminal(job, Duration.ofHours(1));

        Instant expiredDeadline = Instant.now().plus(retentionOffset);
        Path manifestPath = TemplateFillLifecycleStore.manifestPath(workspace);
        String json = Files.readString(manifestPath)
                .replaceFirst("\"retentionDeadline\" : \"[^\"]+\"",
                        "\"retentionDeadline\" : \"" + expiredDeadline + "\"");
        Files.writeString(manifestPath, json);
        return jobId;
    }

    private TemplateFillCleanupService cleanupService(boolean dryRunEnabled, boolean deletionEnabled) {
        TemplateFillProductionProperties production = TemplateFillProductionProperties.forTestEnabled("tenant-a")
                .withEnabled(true);
        production = new TemplateFillProductionProperties(
                true,
                production.allowedTenants(),
                production.adminRole(),
                production.retentionCompleted(),
                production.retentionFailed(),
                production.retentionDiagnostic(),
                production.retentionMin(),
                production.retentionMax(),
                dryRunEnabled,
                deletionEnabled,
                production.diagnosticsEnabled(),
                production.executionStopEnabled());
        PptMasterProperties properties = new PptMasterProperties(
                tempDir, tempDir, "python3", Duration.ofMinutes(1), null, 1024,
                0, 0, 0, 0, null, true, List.of("tenant-a"), null, production);
        return new TemplateFillCleanupService(properties, lifecycleStore, auditSink);
    }
}
