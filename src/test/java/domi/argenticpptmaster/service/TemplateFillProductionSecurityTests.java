package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.config.TemplateFillProductionProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptTemplateFillAccessException;
import domi.argenticpptmaster.exception.PptTemplateFillUnavailableException;
import domi.argenticpptmaster.repository.InMemoryPptJobRepository;
import domi.argenticpptmaster.security.FailClosedPptAccessContextResolver;
import domi.argenticpptmaster.security.FixedPptAccessContextResolver;
import domi.argenticpptmaster.security.PptAccessContext;
import domi.argenticpptmaster.security.PptJobAccessAuthorizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/** Stage-5 security gates for forged identity, cross-task access, path escape and disabled creation. */
class TemplateFillProductionSecurityTests {

    @TempDir
    Path tempDir;

    @Test
    void rejectsForgedTenantAndAdminClaimsWithoutTrustedContext() {
        PptMasterProperties properties = PptMasterProperties.forTemplateFillTest(tempDir, tempDir, "allowed-tenant");
        PptWorkflowService failClosed = new PptWorkflowService(
                properties,
                new InMemoryPptJobRepository(),
                mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class),
                mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class),
                mock(PptTemplateFillPlanStore.class),
                new TemplateFillConstraintsParser(),
                new TemplateFillRolloutPolicy(properties),
                new FailClosedPptAccessContextResolver());

        assertThatThrownBy(() -> failClosed.createJob(new PptJobCreateCommand(
                List.of(md("source.md")), pptx("brand.pptx"), "demo", "ppt169", null, "template-fill")))
                .isInstanceOf(PptTemplateFillAccessException.class);
        assertThat(Files.exists(tempDir.resolve("jobs"))).isFalse();
    }

    @Test
    void rejectsCrossOwnerDownloadAndDiagnosticLeakage() throws Exception {
        PptMasterProperties properties = diagnosticsOn(tempDir);
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.user("owner", "tenant-a", Set.of()));
        TemplateFillLifecycleStore lifecycleStore = new TemplateFillLifecycleStore(properties);
        TemplateFillAuditSink auditSink = new TemplateFillAuditSink();
        PptJobAccessAuthorizer authorizer = new PptJobAccessAuthorizer(properties, access);

        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("jobs").resolve(jobId.toString());
        Files.createDirectories(workspace.resolve("exports"));
        Path export = workspace.resolve("exports/out.pptx");
        Files.writeString(export, "export");
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
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
                lifecycleStore,
                auditSink,
                null);

        access.set(PptAccessContext.user("intruder", "tenant-a", Set.of()));
        assertThatThrownBy(() -> workflow.exportPath(job.id()))
                .isInstanceOf(PptTemplateFillAccessException.class);

        TemplateFillDiagnosticService diagnostics = new TemplateFillDiagnosticService(
                properties,
                repository,
                authorizer,
                access,
                new TemplateFillDiagnosticBundleBuilder(lifecycleStore, new PptTemplateFillAnalysisReader()),
                lifecycleStore,
                auditSink);
        assertThatThrownBy(() -> diagnostics.exportDiagnostics(job.id()))
                .isInstanceOf(PptTemplateFillAccessException.class);
    }

    @Test
    void disabledFeatureLeavesNoJobSideEffectsAndBasicStillWorks() {
        PptMasterProperties disabled = new PptMasterProperties(
                tempDir, tempDir, "python3", Duration.ofSeconds(30), null, 1_048_576L,
                0, 0, 0, 0, null, false, List.of("test-tenant"), null,
                TemplateFillProductionProperties.defaults());
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.user("user-1", "test-tenant", Set.of()));
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        PptWorkflowService service = new PptWorkflowService(
                disabled,
                repository,
                mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class),
                mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class),
                mock(PptTemplateFillPlanStore.class),
                new TemplateFillConstraintsParser(),
                new TemplateFillRolloutPolicy(disabled),
                access);

        assertThatThrownBy(() -> service.createJob(new PptJobCreateCommand(
                List.of(md("a.md")), pptx("t.pptx"), "demo", "ppt169", null, "template-fill")))
                .isInstanceOf(PptTemplateFillUnavailableException.class);
        assertThat(Files.exists(tempDir.resolve("jobs"))).isFalse();

        PptJob basic = service.createJob(new PptJobCreateCommand(
                List.of(md("basic.md")), null, "demo", "ppt169", null, "basic"));
        assertThat(basic.workflowMode()).isEqualTo(PptWorkflowMode.BASIC);
        assertThat(basic.hasOwnership()).isFalse();
    }

    @Test
    void cleanupSkipsSymlinkJobDirectories() throws Exception {
        Path jobsRoot = tempDir.resolve("jobs");
        Files.createDirectories(jobsRoot);
        UUID jobId = UUID.randomUUID();
        Path real = jobsRoot.resolve("real-dir");
        Files.createDirectories(real);
        Files.createSymbolicLink(jobsRoot.resolve(jobId.toString()), real);

        PptMasterProperties properties = PptMasterProperties.forTemplateFillTest(tempDir, tempDir, "tenant-a");
        TemplateFillCleanupService cleanup = new TemplateFillCleanupService(
                properties,
                new TemplateFillLifecycleStore(properties),
                new TemplateFillAuditSink());
        assertThat(cleanup.scanCandidates(true)).isEmpty();
    }

    @Test
    void diagnosticBundleExcludesTemplateAndContentBytes() throws Exception {
        PptMasterProperties properties = diagnosticsOn(tempDir);
        Path workspace = tempDir.resolve("job");
        Files.createDirectories(workspace.resolve("uploads/template"));
        Files.createDirectories(workspace.resolve("uploads/content"));
        Files.writeString(workspace.resolve("uploads/template/0-brand.pptx"), "SECRET-TEMPLATE");
        Files.writeString(workspace.resolve("uploads/content/0-source.md"), "SECRET-CONTENT");
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.assignOwnership("owner", "tenant-a");
        TemplateFillLifecycleStore lifecycleStore = new TemplateFillLifecycleStore(properties);
        lifecycleStore.initialize(job);

        var bundle = new TemplateFillDiagnosticBundleBuilder(
                lifecycleStore, new PptTemplateFillAnalysisReader()).build(job, properties);
        StringBuilder combined = new StringBuilder();
        try (ZipFile zip = new ZipFile(bundle.path().toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                combined.append(new String(zip.getInputStream(entry).readAllBytes()));
            }
        }
        assertThat(combined.toString()).doesNotContain("SECRET-TEMPLATE", "SECRET-CONTENT");
    }

    private static PptMasterProperties diagnosticsOn(Path root) {
        return new PptMasterProperties(
                root, root, "python3", Duration.ofSeconds(30), null, 1_048_576L,
                0, 0, 0, 0, null, true, List.of("tenant-a"), null,
                TemplateFillProductionProperties.forTestEnabled("tenant-a"));
    }

    private static MockMultipartFile pptx(String name) {
        return new MockMultipartFile(
                "templateFile", name, "application/octet-stream", "pptx".getBytes());
    }

    private static MockMultipartFile md(String name) {
        return new MockMultipartFile("files", name, "text/markdown", "# x".getBytes());
    }
}
