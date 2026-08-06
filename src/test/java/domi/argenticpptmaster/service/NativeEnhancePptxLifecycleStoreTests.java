package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.NativeEnhancePptxLifecycleManifest;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeEnhancePptxLifecycleStoreTests {

    @TempDir
    Path tempDir;

    @Test
    void initializesAndMarksTerminalWithRetention() throws Exception {
        PptMasterProperties properties = PptMasterProperties.forNativeEnhanceTest(tempDir, tempDir, "t1");
        NativeEnhancePptxLifecycleStore store = new NativeEnhancePptxLifecycleStore(properties);
        Path workspace = tempDir.resolve("job");
        Files.createDirectories(workspace);
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", null,
                PptWorkflowMode.NATIVE_ENHANCE_PPTX, workspace);
        job.assignOwnership("user-1", "t1");

        store.initialize(job);
        NativeEnhancePptxLifecycleManifest initial = store.read(job).orElseThrow();
        assertThat(initial.schemaVersion()).isEqualTo(NativeEnhancePptxLifecycleManifest.SCHEMA_VERSION);
        assertThat(initial.cleanupState()).isEqualTo(NativeEnhancePptxLifecycleManifest.CleanupState.ACTIVE);
        assertThat(initial.ownershipDigest()).isNotBlank();

        job.fail("boom");
        store.markTerminal(job, store.retentionForTerminalStatus(PptJobStatus.FAILED));
        NativeEnhancePptxLifecycleManifest terminal = store.read(job).orElseThrow();
        assertThat(terminal.terminalAt()).isNotNull();
        assertThat(terminal.retentionDeadline()).isAfter(terminal.terminalAt());
    }
}
