package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptBeautifyAccessException;
import domi.argenticpptmaster.exception.PptBeautifyUnavailableException;
import domi.argenticpptmaster.repository.InMemoryPptJobRepository;
import domi.argenticpptmaster.security.FailClosedPptAccessContextResolver;
import domi.argenticpptmaster.security.FixedPptAccessContextResolver;
import domi.argenticpptmaster.security.PptAccessContext;
import domi.argenticpptmaster.security.PptJobAccessAuthorizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/** Stage-5 security gates for beautify creation, ownership and download. */
class BeautifyPptxProductionSecurityTests {

    @TempDir
    Path tempDir;

    @Test
    void rejectsWhenDisabledWithoutSideEffects() {
        PptMasterProperties properties = PptMasterProperties.forTest(tempDir, tempDir);
        PptWorkflowService service = workflow(properties, new FailClosedPptAccessContextResolver());

        assertThatThrownBy(() -> service.createJob(new PptJobCreateCommand(
                List.of(pptx("source.pptx")), null, "demo", "ppt169", null, "beautify-pptx")))
                .isInstanceOf(PptBeautifyUnavailableException.class);
        assertThat(Files.exists(tempDir.resolve("jobs"))).isFalse();
    }

    @Test
    void rejectsMissingTrustedContextEvenWhenEnabled() {
        PptMasterProperties properties = PptMasterProperties.forBeautifyTest(tempDir, tempDir, "tenant-a");
        PptWorkflowService service = workflow(properties, new FailClosedPptAccessContextResolver());

        assertThatThrownBy(() -> service.createJob(new PptJobCreateCommand(
                List.of(pptx("source.pptx")), null, "demo", "ppt169", null, "beautify-pptx")))
                .isInstanceOf(PptBeautifyAccessException.class);
        assertThat(Files.exists(tempDir.resolve("jobs"))).isFalse();
    }

    @Test
    void allowsAllowlistedTenantAndRecordsOwnership() throws Exception {
        PptMasterProperties properties = PptMasterProperties.forBeautifyTest(tempDir, tempDir, "tenant-a");
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.user("owner", "tenant-a", Set.of()));
        PptWorkflowAsyncRunner asyncRunner = mock(PptWorkflowAsyncRunner.class);
        PptWorkflowService service = workflow(properties, access, asyncRunner);

        PptJob job = service.createJob(new PptJobCreateCommand(
                List.of(pptx("source.pptx")), null, "demo", "ppt169", null, "beautify-pptx"));

        assertThat(job.workflowMode()).isEqualTo(PptWorkflowMode.BEAUTIFY_PPTX);
        assertThat(job.hasOwnership()).isTrue();
        assertThat(job.ownerSubjectId()).contains("owner");
        assertThat(job.ownerTenantId()).contains("tenant-a");
        assertThat(Files.isRegularFile(job.workspacePath().resolve("lifecycle/manifest.json"))).isTrue();
    }

    @Test
    void rejectsCrossOwnerDownload() throws Exception {
        PptMasterProperties properties = PptMasterProperties.forBeautifyTest(tempDir, tempDir, "tenant-a");
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.user("owner", "tenant-a", Set.of()));
        BeautifyPptxLifecycleStore lifecycleStore = new BeautifyPptxLifecycleStore(properties);
        PptJobAccessAuthorizer authorizer = new PptJobAccessAuthorizer(properties, access);

        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("jobs").resolve(jobId.toString());
        Files.createDirectories(workspace.resolve("exports"));
        Path export = workspace.resolve("exports/out.pptx");
        Files.writeString(export, "export");
        PptJob job = new PptJob(jobId, "demo", "ppt169", "layout", PptWorkflowMode.BEAUTIFY_PPTX, workspace);
        job.assignOwnership("owner", "tenant-a");
        lifecycleStore.initialize(job);
        job.complete(export);
        assertThat(job.status()).isEqualTo(PptJobStatus.COMPLETED);
        repository.save(job);

        PptWorkflowService workflow = new PptWorkflowService(
                properties,
                repository,
                mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class),
                mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class),
                mock(PptTemplateFillPlanStore.class),
                new TemplateFillConstraintsParser(),
                new TemplateFillRolloutPolicy(properties),
                access,
                authorizer,
                new TemplateFillLifecycleStore(properties),
                new TemplateFillAuditSink(),
                null,
                new BeautifyPptxRolloutPolicy(properties),
                lifecycleStore,
                new BeautifyPptxAuditSink(),
                null);

        access.set(PptAccessContext.user("intruder", "tenant-a", Set.of()));
        assertThatThrownBy(() -> workflow.exportPath(job.id()))
                .isInstanceOf(PptBeautifyAccessException.class);
    }

    @Test
    void preservesBasicModeWhenBeautifyDisabled() {
        PptMasterProperties properties = PptMasterProperties.forTest(tempDir, tempDir);
        assertThat(properties.isBeautifyPptxEnabled()).isFalse();
        PptWorkflowService service = workflow(properties, new FailClosedPptAccessContextResolver());

        PptJob job = service.createJob(new PptJobCreateCommand(
                List.of(md("source.md")), null, "demo", "ppt169", null, "basic"));
        assertThat(job.workflowMode()).isEqualTo(PptWorkflowMode.BASIC);
    }

    private static PptWorkflowService workflow(PptMasterProperties properties,
            domi.argenticpptmaster.security.PptAccessContextResolver access) {
        return workflow(properties, access, mock(PptWorkflowAsyncRunner.class));
    }

    private static PptWorkflowService workflow(
            PptMasterProperties properties,
            domi.argenticpptmaster.security.PptAccessContextResolver access,
            PptWorkflowAsyncRunner asyncRunner) {
        return new PptWorkflowService(
                properties,
                new InMemoryPptJobRepository(),
                mock(PptWorkflowEvents.class),
                asyncRunner,
                mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class),
                mock(PptTemplateFillPlanStore.class),
                new TemplateFillConstraintsParser(),
                new TemplateFillRolloutPolicy(properties),
                access,
                new PptJobAccessAuthorizer(properties, access),
                new TemplateFillLifecycleStore(properties),
                new TemplateFillAuditSink(),
                null,
                new BeautifyPptxRolloutPolicy(properties),
                new BeautifyPptxLifecycleStore(properties),
                new BeautifyPptxAuditSink(),
                null);
    }

    private static MockMultipartFile pptx(String name) {
        return new MockMultipartFile("files", name,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                new byte[] {0x50, 0x4b, 0x03, 0x04, 0x00});
    }

    private static MockMultipartFile md(String name) {
        return new MockMultipartFile("files", name, "text/markdown", "# hi".getBytes());
    }
}
