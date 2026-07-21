package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.domain.TemplateFillLifecycleManifest;
import domi.argenticpptmaster.domain.TemplateFillLifecycleManifest.CleanupState;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateFillLifecycleStoreTests {

    @TempDir
    Path tempDir;

    private TemplateFillLifecycleStore store;
    private PptJob job;

    @BeforeEach
    void setUp() {
        store = new TemplateFillLifecycleStore(PptMasterProperties.forTemplateFillTest(tempDir, tempDir, "tenant-a"));
        job = new PptJob(UUID.randomUUID(), "demo", "ppt169", null, PptWorkflowMode.TEMPLATE_FILL, tempDir);
        job.assignOwnership("user-1", "tenant-a");
    }

    @Test
    void initializesManifestAtomicallyWithOwnershipDigest() throws Exception {
        Files.createDirectories(tempDir.resolve("uploads/template"));
        Files.writeString(tempDir.resolve("uploads/template/t.pptx"), "template");
        Files.createDirectories(tempDir.resolve("uploads/content"));
        Files.writeString(tempDir.resolve("uploads/content/c.md"), "content");

        store.initialize(job);

        Path manifestPath = TemplateFillLifecycleStore.manifestPath(tempDir);
        assertThat(Files.isRegularFile(manifestPath)).isTrue();
        assertThat(Files.list(tempDir.resolve("lifecycle")))
                .noneMatch(path -> path.getFileName().toString().endsWith(".tmp"));

        TemplateFillLifecycleManifest manifest = store.read(job).orElseThrow();
        assertThat(manifest.jobId()).isEqualTo(job.id());
        assertThat(manifest.ownershipDigest())
                .isEqualTo(TemplateFillLifecycleStore.digestOwnership("user-1", "tenant-a"));
        assertThat(manifest.cleanupState()).isEqualTo(CleanupState.ACTIVE);
        assertThat(manifest.artifactCount("template")).isEqualTo(1);
        assertThat(manifest.artifactCount("content")).isEqualTo(1);
        assertThat(Files.readString(manifestPath)).doesNotContain("user-1", "tenant-a");
    }

    @Test
    void markTerminalWritesRetentionDeadlineWithoutExtendingOnDownload() {
        store.initialize(job);
        job.complete(tempDir.resolve("exports/out.pptx"));

        store.markTerminal(job, Duration.ofDays(3));

        TemplateFillLifecycleManifest terminal = store.read(job).orElseThrow();
        assertThat((Object) terminal.terminalAt()).isNotNull();
        assertThat(terminal.retentionDeadline()).isAfter(terminal.terminalAt());
        Instant originalDeadline = terminal.retentionDeadline();

        store.recordDownload(job);

        TemplateFillLifecycleManifest afterDownload = store.read(job).orElseThrow();
        assertThat((Object) afterDownload.lastDownloadedAt()).isNotNull();
        assertThat(afterDownload.retentionDeadline()).isEqualTo(originalDeadline);
    }

    @Test
    void rejectsSymlinkManifest() throws Exception {
        Path target = tempDir.resolve("uploads/content/c.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "content");
        store.initialize(job);
        Path manifestPath = TemplateFillLifecycleStore.manifestPath(tempDir);
        Files.delete(manifestPath);
        Files.createSymbolicLink(manifestPath, target);

        assertThatThrownBy(() -> store.read(job))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void rejectsCorruptedManifestSchema() throws Exception {
        Path manifestPath = TemplateFillLifecycleStore.manifestPath(tempDir);
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(manifestPath, "{\"schemaVersion\":\"other\"}");

        assertThatThrownBy(() -> store.read(job))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("failed to read lifecycle manifest");
    }

    @Test
    void mapsRetentionByTerminalStatus() {
        assertThat(store.retentionForTerminalStatus(PptJobStatus.COMPLETED))
                .isEqualTo(PptMasterProperties.forTemplateFillTest(tempDir, tempDir, "tenant-a")
                        .templateFillProduction().retentionCompleted());
        assertThat(store.retentionForTerminalStatus(PptJobStatus.FAILED))
                .isEqualTo(PptMasterProperties.forTemplateFillTest(tempDir, tempDir, "tenant-a")
                        .templateFillProduction().retentionFailed());
    }
}
