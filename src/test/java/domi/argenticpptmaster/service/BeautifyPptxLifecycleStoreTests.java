package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.BeautifyPptxLifecycleManifest;
import domi.argenticpptmaster.domain.BeautifyPptxLifecycleManifest.CleanupState;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BeautifyPptxLifecycleStoreTests {

    @TempDir
    Path tempDir;

    private BeautifyPptxLifecycleStore store;
    private PptJob job;

    @BeforeEach
    void setUp() {
        store = new BeautifyPptxLifecycleStore(PptMasterProperties.forBeautifyTest(tempDir, tempDir, "tenant-a"));
        job = new PptJob(UUID.randomUUID(), "demo", "ppt169", null, PptWorkflowMode.BEAUTIFY_PPTX, tempDir);
        job.assignOwnership("user-1", "tenant-a");
    }

    @Test
    void initializesManifestAtomicallyWithOwnershipDigest() throws Exception {
        Files.createDirectories(tempDir.resolve("uploads"));
        Files.writeString(tempDir.resolve("uploads/source.pptx"), "source");
        Path project = tempDir.resolve("projects/demo_ppt169_abc");
        Files.createDirectories(project.resolve("images"));
        Files.writeString(project.resolve("images/logo.png"), "img");
        Files.createDirectories(project.resolve("analysis"));
        Files.writeString(project.resolve("analysis/beautify_inventory.json"), "{}");
        Files.writeString(project.resolve("analysis/beautify_plan.draft.json"), "{}");
        Files.createDirectories(project.resolve("pages"));
        Files.writeString(project.resolve("pages/page_001.svg"), "<svg/>");
        Files.createDirectories(project.resolve("validation"));
        Files.writeString(project.resolve("validation/beautify-readback.json"), "{}");
        Files.createDirectories(tempDir.resolve("exports"));
        Files.writeString(tempDir.resolve("exports/out.pptx"), "export");
        Files.createDirectories(tempDir.resolve("diagnostics"));
        Files.writeString(tempDir.resolve("diagnostics/bundle.zip"), "zip");
        job.prepareProject(project);

        store.initialize(job);

        Path manifestPath = BeautifyPptxLifecycleStore.manifestPath(tempDir);
        assertThat(Files.isRegularFile(manifestPath)).isTrue();
        assertThat(Files.list(tempDir.resolve("lifecycle")))
                .noneMatch(path -> path.getFileName().toString().endsWith(".tmp"));

        BeautifyPptxLifecycleManifest manifest = store.read(job).orElseThrow();
        assertThat(manifest.jobId()).isEqualTo(job.id());
        assertThat(manifest.schemaVersion()).isEqualTo(BeautifyPptxLifecycleManifest.SCHEMA_VERSION);
        assertThat(manifest.ownershipDigest())
                .isEqualTo(BeautifyPptxLifecycleStore.digestOwnership("user-1", "tenant-a"));
        assertThat(manifest.cleanupState()).isEqualTo(CleanupState.ACTIVE);
        assertThat(manifest.artifactCount("source")).isEqualTo(1);
        assertThat(manifest.artifactCount("extracted-media")).isEqualTo(1);
        assertThat(manifest.artifactCount("analysis")).isEqualTo(2);
        assertThat(manifest.artifactCount("confirmed-plan")).isEqualTo(1);
        assertThat(manifest.artifactCount("svg")).isEqualTo(1);
        assertThat(manifest.artifactCount("validation")).isEqualTo(1);
        assertThat(manifest.artifactCount("export")).isEqualTo(1);
        assertThat(manifest.artifactCount("diagnostic")).isEqualTo(1);
        assertThat(Files.readString(manifestPath)).doesNotContain("user-1", "tenant-a");
    }

    @Test
    void markTerminalWritesRetentionDeadlineWithoutExtendingOnDownload() {
        store.initialize(job);
        job.complete(tempDir.resolve("exports/out.pptx"));

        store.markTerminal(job, Duration.ofDays(3));

        BeautifyPptxLifecycleManifest terminal = store.read(job).orElseThrow();
        assertThat((Object) terminal.terminalAt()).isNotNull();
        assertThat(terminal.retentionDeadline()).isAfter(terminal.terminalAt());
        Instant originalDeadline = terminal.retentionDeadline();

        store.recordDownload(job);

        BeautifyPptxLifecycleManifest afterDownload = store.read(job).orElseThrow();
        assertThat((Object) afterDownload.lastDownloadedAt()).isNotNull();
        assertThat(afterDownload.retentionDeadline()).isEqualTo(originalDeadline);
    }

    @Test
    void rejectsSymlinkManifest() throws Exception {
        Path target = tempDir.resolve("uploads/source.pptx");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "source");
        store.initialize(job);
        Path manifestPath = BeautifyPptxLifecycleStore.manifestPath(tempDir);
        Files.delete(manifestPath);
        Files.createSymbolicLink(manifestPath, target);

        assertThatThrownBy(() -> store.read(job))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void rejectsCorruptedManifestSchema() throws Exception {
        Path manifestPath = BeautifyPptxLifecycleStore.manifestPath(tempDir);
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(manifestPath, "{\"schemaVersion\":\"other\"}");

        assertThatThrownBy(() -> store.read(job))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("failed to read lifecycle manifest");
    }

    @Test
    void mapsRetentionByTerminalStatus() {
        assertThat(store.retentionForTerminalStatus(PptJobStatus.COMPLETED))
                .isEqualTo(PptMasterProperties.forBeautifyTest(tempDir, tempDir, "tenant-a")
                        .beautifyPptx().production().retentionCompleted());
        assertThat(store.retentionForTerminalStatus(PptJobStatus.FAILED))
                .isEqualTo(PptMasterProperties.forBeautifyTest(tempDir, tempDir, "tenant-a")
                        .beautifyPptx().production().retentionFailed());
    }
}
