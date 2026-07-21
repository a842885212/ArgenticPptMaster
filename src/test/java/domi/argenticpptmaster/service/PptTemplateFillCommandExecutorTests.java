package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.domain.PptTemplateFile;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class PptTemplateFillCommandExecutorTests {

    @TempDir
    Path tempDir;

    private PptJobRepository repository;
    private PptMasterCommandExecutor commands;
    private PptWorkflowEvents events;
    private PptTemplateFillAnalysisReader analysisReader;
    private PptTemplateFillConcurrencyLimiter concurrencyLimiter;
    private PptTemplateFillCommandExecutor executor;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(PptJobRepository.class);
        commands = org.mockito.Mockito.mock(PptMasterCommandExecutor.class);
        events = org.mockito.Mockito.mock(PptWorkflowEvents.class);
        analysisReader = new PptTemplateFillAnalysisReader();
        concurrencyLimiter = new PptTemplateFillConcurrencyLimiter(
                PptMasterProperties.forTest(tempDir, tempDir));
        executor = new PptTemplateFillCommandExecutor(
                PptMasterProperties.forTest(tempDir, tempDir),
                repository, events, commands, analysisReader, concurrencyLimiter,
                new TemplateFillCapabilityIndexLoader(),
                new TemplateFillConstraintResolver(),
                new PptTemplateFillPlanStore(PptMasterProperties.forTest(tempDir, tempDir), new TemplateFillPlanValidator()),
                new TemplateFillOutputVerifier(),
                new TemplateFillLifecycleStore(PptMasterProperties.forTest(tempDir, tempDir)));
    }

    @Test
    void runsFixedNativePptxSequenceAndCompletesJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("job");
        Path templatePath = workspace.resolve("uploads/template/0-brand.pptx");
        Path sourcePath = workspace.resolve("uploads/content/0-source.md");
        Files.createDirectories(templatePath.getParent());
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(templatePath, "template");
        Files.writeString(sourcePath, "# source");
        Path slideLibrary = workspace.resolve("analysis/template.slide_library.json");
        Files.createDirectories(slideLibrary.getParent());
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"), slideLibrary);
        String draft = Files.readString(Path.of(
                getClass().getResource("/template-fill/fill-plan-draft.json").toURI()));
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L, templatePath));
        job.addSource(new PptSourceFile("source.md", "text/markdown", 8L, sourcePath));
        PptTemplateFillPlanStore planStore = new PptTemplateFillPlanStore(
                PptMasterProperties.forTest(tempDir, tempDir), new TemplateFillPlanValidator());
        var metadata = planStore.storeDraftPlan(job, draft, slideLibrary);
        planStore.confirmCurrentDraft(job, "confirm-1", metadata.version(), metadata.digest());
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(commands.runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    String script = invocation.getArgument(0);
                    @SuppressWarnings("unchecked") List<String> args = invocation.getArgument(1);
                    if (script.endsWith("project_manager.py") && args.get(0).equals("init")) {
                        Path project = workspace.resolve("projects/demo_ppt169_20260720");
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

        executor.execute(jobId, workspace.resolve("analysis/fill_plan.json"));

        assertThat(job.status()).isEqualTo(domi.argenticpptmaster.domain.PptJobStatus.COMPLETED);
        assertThat(job.exportPath()).isPresent();
        assertThat(job.lastCompletedNode()).contains(PptJobNode.OUTPUT_VALIDATED);
        assertThat(job.templateAnalysisReady()).isTrue();
        assertThat(job.readbackValidationStatus()).isIn("PASSED", "PASSED_WITH_WARNINGS");
        assertThat(job.nodeExecution(PptJobNode.TEMPLATE_ANALYZED).status()).isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(job.nodeExecution(PptJobNode.FILL_PLAN_VALIDATED).status()).isEqualTo(PptJobNodeStatus.COMPLETED);
        verify(commands, org.mockito.Mockito.times(6))
                .runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList(), any(Duration.class));
        ArgumentCaptor<PptJobEvent> eventsCaptor = ArgumentCaptor.forClass(PptJobEvent.class);
        verify(events, atLeast(4)).record(eq(job), eventsCaptor.capture());
        assertThat(eventsCaptor.getAllValues()).extracting(PptJobEvent::type)
                .contains(domi.argenticpptmaster.domain.PptJobEventType.TEMPLATE_FILL_STAGE_STARTED,
                        domi.argenticpptmaster.domain.PptJobEventType.TEMPLATE_FILL_STAGE_COMPLETED,
                        domi.argenticpptmaster.domain.PptJobEventType.EXPORT_READY);
    }

    @Test
    void stopsBeforeApplyWhenCheckPlanFails() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("job-fail");
        Path templatePath = workspace.resolve("uploads/template/0-brand.pptx");
        Files.createDirectories(templatePath.getParent());
        Files.writeString(templatePath, "template");
        Path slideLibrary = workspace.resolve("analysis/template.slide_library.json");
        Files.createDirectories(slideLibrary.getParent());
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"), slideLibrary);
        String draft = Files.readString(Path.of(
                getClass().getResource("/template-fill/fill-plan-draft.json").toURI()));
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L, templatePath));
        job.addSource(new PptSourceFile("source.md", "text/markdown", 8L,
                workspace.resolve("uploads/content/0-source.md")));
        Files.createDirectories(job.sourceFiles().get(0).storedPath().getParent());
        Files.writeString(job.sourceFiles().get(0).storedPath(), "# source");
        PptTemplateFillPlanStore planStore = new PptTemplateFillPlanStore(
                PptMasterProperties.forTest(tempDir, tempDir), new TemplateFillPlanValidator());
        var metadata = planStore.storeDraftPlan(job, draft, slideLibrary);
        planStore.confirmCurrentDraft(job, "confirm-1", metadata.version(), metadata.digest());
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(commands.runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    String script = invocation.getArgument(0);
                    @SuppressWarnings("unchecked") List<String> args = invocation.getArgument(1);
                    if (script.endsWith("project_manager.py") && args.get(0).equals("init")) {
                        Path project = workspace.resolve("projects/demo_ppt169_20260720");
                        Files.createDirectories(project.resolve("analysis"));
                        return new PptMasterCommandExecutor.CommandResult(0,
                                "[OK] Project initialized: " + project, false);
                    }
                    if (script.endsWith("project_manager.py") && args.get(0).equals("import-sources")) {
                        return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
                    }
                    if (script.endsWith("template_fill_pptx.py") && args.get(0).equals("analyze")) {
                        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"),
                                Path.of(args.get(args.indexOf("-o") + 1)),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
                    }
                    if (script.endsWith("template_fill_pptx.py") && args.get(0).equals("check-plan")) {
                        return new PptMasterCommandExecutor.CommandResult(1, "check failed", false);
                    }
                    return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
                });

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executor.execute(jobId, workspace.resolve("analysis/fill_plan.json")))
                .isInstanceOf(PptTemplateFillExecutionException.class);
        assertThat(job.status()).isEqualTo(domi.argenticpptmaster.domain.PptJobStatus.FAILED);
        assertThat(job.lastCompletedNode()).contains(PptJobNode.FILL_PLAN_CONFIRMED);
        assertThat(job.lastFailureNode()).contains(PptJobNode.FILL_PLAN_VALIDATED);
        verify(commands, never()).runPythonScript(
                org.mockito.ArgumentMatchers.eq("skills/ppt-master/scripts/template_fill_pptx.py"),
                org.mockito.ArgumentMatchers.argThat(args -> args.contains("apply")),
                any(Duration.class));
    }

    @Test
    void rejectsTemplatePathOutsideJobWorkspaceBeforeStartingProcess() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("job");
        Path outside = tempDir.resolve("outside.pptx");
        Files.writeString(outside, "template");
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L, outside));
        when(repository.findById(jobId)).thenReturn(Optional.of(job));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executor.execute(jobId, workspace.resolve("analysis/fill_plan.json")))
                .isInstanceOf(PptTemplateFillExecutionException.class)
                .hasMessageContaining("escapes job workspace");
        verify(commands, never()).runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList(), any(Duration.class));
    }

    @Test
    void rejectsDraftOrForcedPlanBeforeRunningNativeCommands() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("draft-job");
        Path templatePath = workspace.resolve("uploads/template/0-brand.pptx");
        Files.createDirectories(templatePath.getParent());
        Files.writeString(templatePath, "template");
        Files.createDirectories(workspace.resolve("analysis"));
        Files.writeString(workspace.resolve("analysis/fill_plan.json"), "{\"status\":\"draft\",\"slides\":[]}");
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L, templatePath));
        when(repository.findById(jobId)).thenReturn(Optional.of(job));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> executor.execute(jobId, workspace.resolve("analysis/fill_plan.json")))
                .isInstanceOf(PptTemplateFillExecutionException.class)
                .hasMessageContaining("confirmed");
        verify(commands, never()).runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList(), any(Duration.class));

        Files.writeString(workspace.resolve("analysis/fill_plan.json"),
                "{\"status\":\"confirmed\",\"slides\":[],\"note\":\"--force\"}");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> executor.execute(jobId, workspace.resolve("analysis/fill_plan.json")))
                .isInstanceOf(PptTemplateFillExecutionException.class)
                .hasMessageContaining("force");
        verify(commands, never()).runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList(), any(Duration.class));
    }

    @Test
    void nativeCommandsNeverIncludeForceSvgOrImageTooling() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("args-job");
        Path templatePath = workspace.resolve("uploads/template/0-brand.pptx");
        Path sourcePath = workspace.resolve("uploads/content/0-source.md");
        Files.createDirectories(templatePath.getParent());
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(templatePath, "template");
        Files.writeString(sourcePath, "# source");
        Path slideLibrary = workspace.resolve("analysis/template.slide_library.json");
        Files.createDirectories(slideLibrary.getParent());
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"), slideLibrary);
        String draft = Files.readString(Path.of(
                getClass().getResource("/template-fill/fill-plan-draft.json").toURI()));
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L, templatePath));
        job.addSource(new PptSourceFile("source.md", "text/markdown", 8L, sourcePath));
        PptTemplateFillPlanStore planStore = new PptTemplateFillPlanStore(
                PptMasterProperties.forTest(tempDir, tempDir), new TemplateFillPlanValidator());
        var metadata = planStore.storeDraftPlan(job, draft, slideLibrary);
        planStore.confirmCurrentDraft(job, "confirm-1", metadata.version(), metadata.digest());
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        when(commands.runPythonScript(scriptCaptor.capture(), argsCaptor.capture(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    String script = invocation.getArgument(0);
                    @SuppressWarnings("unchecked") List<String> args = invocation.getArgument(1);
                    if (script.endsWith("project_manager.py") && args.get(0).equals("init")) {
                        Path project = workspace.resolve("projects/demo_ppt169_20260720");
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

        executor.execute(jobId, workspace.resolve("analysis/fill_plan.json"));

        assertThat(scriptCaptor.getAllValues())
                .allSatisfy(script -> assertThat(script).doesNotContain("svg").doesNotContain("image_gen"));
        assertThat(argsCaptor.getAllValues())
                .allSatisfy(args -> assertThat(args).noneMatch(arg -> arg.contains("--force")));
        assertThat(argsCaptor.getAllValues().stream().map(args -> args.get(0)).toList())
                .contains("init", "import-sources", "analyze", "check-plan", "apply", "validate");
    }
}
