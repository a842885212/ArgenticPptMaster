package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeEnhancePptxDiagnosticBundleBuilderTests {

    @TempDir
    Path tempDir;

    @Test
    void excludesAudioAndNotesBodies() throws Exception {
        PptMasterProperties properties = PptMasterProperties.forNativeEnhanceTest(tempDir, tempDir, "t1");
        NativeEnhancePptxLifecycleStore lifecycleStore = new NativeEnhancePptxLifecycleStore(properties);
        NativeEnhancePptxDiagnosticBundleBuilder builder =
                new NativeEnhancePptxDiagnosticBundleBuilder(lifecycleStore);

        Path workspace = tempDir.resolve("ws");
        Path project = workspace.resolve("project");
        Files.createDirectories(project.resolve("notes"));
        Files.createDirectories(project.resolve("audio"));
        Files.writeString(project.resolve("notes/001.md"), "secret note body");
        Files.write(project.resolve("audio/001.mp3"), "AUDIOBYTES".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(workspace.resolve("uploads"));
        Files.write(workspace.resolve("uploads/source.pptx"), "PPTX".getBytes(StandardCharsets.UTF_8));

        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", null,
                PptWorkflowMode.NATIVE_ENHANCE_PPTX, workspace);
        job.assignOwnership("u1", "t1");
        job.prepareProject(project);
        lifecycleStore.initialize(job);

        var bundle = builder.build(job, properties);
        assertThat(bundle.path().getFileName().toString()).isEqualTo("native-enhance-pptx-diagnostics.zip");
        assertThat(bundle.bytes()).isNotEmpty();

        boolean hasServiceMeta = false;
        boolean hasLifecycle = false;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(bundle.path()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                assertThat(name).doesNotEndWith(".mp3");
                assertThat(name).doesNotContain("notes/001.md");
                assertThat(name).doesNotEndWith(".pptx");
                byte[] content = zip.readAllBytes();
                String text = new String(content, StandardCharsets.UTF_8);
                assertThat(text).doesNotContain("secret note body");
                assertThat(text).doesNotContain("AUDIOBYTES");
                if ("service/service-meta.json".equals(name)) {
                    hasServiceMeta = true;
                }
                if ("lifecycle/lifecycle-summary.json".equals(name)) {
                    hasLifecycle = true;
                }
            }
        }
        assertThat(hasServiceMeta).isTrue();
        assertThat(hasLifecycle).isTrue();
    }
}
