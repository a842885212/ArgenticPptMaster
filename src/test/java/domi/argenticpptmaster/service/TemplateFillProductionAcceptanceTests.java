package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.config.TemplateFillProductionProperties;
import domi.argenticpptmaster.domain.PptConfirmationAction;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.TemplateFillPlanMetadata;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.repository.InMemoryPptJobRepository;
import domi.argenticpptmaster.security.FixedPptAccessContextResolver;
import domi.argenticpptmaster.security.PptAccessContext;
import domi.argenticpptmaster.security.PptJobAccessAuthorizer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Production acceptance path: eligible create → confirm → apply/readback → download → diagnostics → cleanup dry-run.
 */
class TemplateFillProductionAcceptanceTests {

    @TempDir
    Path tempDir;

    @Test
    void eligibleCreationThroughCleanupDryRun() throws Exception {
        Path jobsRoot = tempDir.resolve("jobs");
        Files.createDirectories(jobsRoot);

        PptMasterProperties properties = diagnosticsEnabledProperties(tempDir);
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.user("user-1", "test-tenant", Set.of()));
        PptJobAccessAuthorizer authorizer = new PptJobAccessAuthorizer(properties, access);
        TemplateFillLifecycleStore lifecycleStore = new TemplateFillLifecycleStore(properties);
        TemplateFillAuditSink auditSink = new TemplateFillAuditSink();
        TemplateFillTelemetry telemetry = new TemplateFillTelemetry(new SimpleMeterRegistry());
        PptTemplateFillPlanStore planStore = new PptTemplateFillPlanStore(properties, new TemplateFillPlanValidator());
        PptTemplateFillAsyncRunner asyncRunner = mock(PptTemplateFillAsyncRunner.class);
        PptWorkflowEvents events = mock(PptWorkflowEvents.class);

        PptWorkflowService workflow = new PptWorkflowService(
                properties,
                repository,
                events,
                mock(PptWorkflowAsyncRunner.class),
                asyncRunner,
                new PptTemplateFillPlanningOrchestrator(repository, mock(PptWorkflowAsyncRunner.class), telemetry),
                planStore,
                new TemplateFillConstraintsParser(),
                new TemplateFillRolloutPolicy(properties, telemetry),
                access,
                authorizer,
                lifecycleStore,
                auditSink,
                telemetry);

        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "brand.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "template-bytes".getBytes());
        MockMultipartFile content = new MockMultipartFile(
                "files", "source.md", "text/markdown", "# content".getBytes());
        PptJob created = workflow.createJob(new PptJobCreateCommand(
                List.of(content), template, "demo", "ppt169", "fill", "template-fill"));
        assertThat(created.ownerSubjectId()).contains("user-1");
        assertThat(lifecycleStore.read(created)).isPresent();

        Path slideLibrary = created.workspacePath().resolve("analysis/template.slide_library.json");
        Files.createDirectories(slideLibrary.getParent());
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"), slideLibrary);
        String draft = Files.readString(Path.of(
                getClass().getResource("/template-fill/fill-plan-draft.json").toURI()));
        created.completeNode(PptJobNode.PROJECT_READY, Map.of());
        created.completeNode(PptJobNode.TEMPLATE_ANALYZED, Map.of());
        created.completeNode(PptJobNode.FILL_PLAN_DRAFTED, Map.of());
        TemplateFillPlanMetadata meta = planStore.storeDraftPlan(created, draft, slideLibrary);
        created.requireConfirmation("tf-accept-1", confirmationPayload(meta));
        repository.save(created);

        workflow.submitConfirmation(
                created.id(), "tf-accept-1", true, Map.of(), null,
                PptConfirmationAction.APPROVE, null, List.of(), null, List.of(), null);

        PptMasterCommandExecutor commands = mock(PptMasterCommandExecutor.class);
        when(commands.runPythonScript(any(), anyList(), any(Duration.class))).thenAnswer(invocation -> {
            String script = invocation.getArgument(0);
            @SuppressWarnings("unchecked") List<String> args = invocation.getArgument(1);
            if (script.endsWith("project_manager.py") && args.get(0).equals("init")) {
                Path project = created.workspacePath().resolve("projects/demo_ppt169_20260720");
                Files.createDirectories(project.resolve("analysis"));
                Files.createDirectories(project.resolve("exports"));
                Files.createDirectories(project.resolve("validation"));
                return new PptMasterCommandExecutor.CommandResult(0,
                        "[OK] Project initialized: " + project, false);
            }
            if (script.endsWith("template_fill_pptx.py") && args.get(0).equals("analyze")) {
                Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"),
                        Path.of(args.get(args.indexOf("-o") + 1)),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (script.endsWith("template_fill_pptx.py") && args.get(0).equals("check-plan")) {
                Files.copy(getClass().getResourceAsStream("/template-fill/check_report.json"),
                        Path.of(args.get(args.indexOf("-o") + 1)));
            }
            if (script.endsWith("template_fill_pptx.py") && args.get(0).equals("apply")) {
                Path requestedOutput = Path.of(args.get(args.indexOf("-o") + 1));
                Files.copy(getClass().getResourceAsStream("/template-fill/minimal-valid-export.pptx"),
                        requestedOutput.resolveSibling("template-fill_20260720_000000.pptx"));
            }
            return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
        });

        PptTemplateFillCommandExecutor executor = new PptTemplateFillCommandExecutor(
                properties,
                repository,
                events,
                commands,
                new PptTemplateFillAnalysisReader(),
                new PptTemplateFillConcurrencyLimiter(properties),
                new TemplateFillCapabilityIndexLoader(),
                new TemplateFillConstraintResolver(),
                planStore,
                new TemplateFillOutputVerifier(),
                lifecycleStore,
                telemetry);
        executor.execute(created.id(), created.workspacePath().resolve("analysis/fill_plan.json"));

        PptJob completed = repository.findById(created.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(PptJobStatus.COMPLETED);

        Path export = workflow.exportPath(completed.id());
        assertThat(Files.isRegularFile(export)).isTrue();
        assertThat(lifecycleStore.read(completed).orElseThrow().lastDownloadedAt()).isNotNull();

        TemplateFillDiagnosticService diagnostics = new TemplateFillDiagnosticService(
                properties,
                repository,
                authorizer,
                access,
                new TemplateFillDiagnosticBundleBuilder(lifecycleStore, new PptTemplateFillAnalysisReader()),
                lifecycleStore,
                auditSink);
        var bundle = diagnostics.exportDiagnostics(completed.id());
        assertThat(bundle.path()).exists();

        Instant expired = Instant.now().minus(Duration.ofDays(1));
        Path manifestPath = TemplateFillLifecycleStore.manifestPath(completed.workspacePath());
        String json = Files.readString(manifestPath)
                .replaceFirst("\"retentionDeadline\" : \"[^\"]+\"",
                        "\"retentionDeadline\" : \"" + expired + "\"");
        Files.writeString(manifestPath, json);

        TemplateFillCleanupService cleanup = new TemplateFillCleanupService(
                properties, lifecycleStore, auditSink, telemetry);
        List<UUID> candidates = cleanup.scanCandidates(true);
        assertThat(candidates).contains(completed.id());
        assertThat(Files.exists(completed.workspacePath())).isTrue();
    }

    private static Map<String, Object> confirmationPayload(TemplateFillPlanMetadata draft) {
        return Map.of(
                "stage", "template_fill_plan",
                "contextData", Map.of(
                        "type", "template_fill_plan",
                        "version", draft.version(),
                        "digest", draft.digest(),
                        "pages", List.of(Map.of(
                                "sourceSlide", 1,
                                "purpose", "cover",
                                "notesMappingCount", 0,
                                "tableMappingCount", 0,
                                "chartMappingCount", 0,
                                "transition", "fade",
                                "acceptedWarnings", List.of()))));
    }

    private static PptMasterProperties diagnosticsEnabledProperties(Path root) {
        TemplateFillProductionProperties production = TemplateFillProductionProperties.forTestEnabled("test-tenant");
        return new PptMasterProperties(
                root, root, "python3", Duration.ofSeconds(30), null, 1_048_576L,
                0, 0, 0, 0, null, true, List.of("test-tenant"), null, production);
    }
}
