package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.domain.PptTemplateFile;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;

class PptTemplateFillCommandExecutorTests {

    @TempDir
    Path tempDir;

    @Test
    void runsFixedNativePptxSequenceAndCompletesJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("job");
        Path templatePath = workspace.resolve("uploads/template/0-brand.pptx");
        Path sourcePath = workspace.resolve("uploads/content/0-$(touch marker).md");
        Files.createDirectories(templatePath.getParent());
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(templatePath, "template");
        Files.writeString(sourcePath, "# source");
        Files.createDirectories(workspace.resolve("analysis"));
        Files.writeString(workspace.resolve("analysis/fill_plan.json"), "{\"status\":\"confirmed\"}");
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L, templatePath));
        job.addSource(new PptSourceFile("source.md", "text/markdown", 8L, sourcePath));
        PptJobRepository repository = org.mockito.Mockito.mock(PptJobRepository.class);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        PptMasterCommandExecutor commands = org.mockito.Mockito.mock(PptMasterCommandExecutor.class);
        when(commands.runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList()))
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
                        Files.writeString(Path.of(args.get(args.indexOf("-o") + 1)), "{} ");
                    }
                    if (script.endsWith("template_fill_pptx.py") && args.get(0).equals("apply")) {
                        Path requestedOutput = Path.of(args.get(args.indexOf("-o") + 1));
                        Files.writeString(requestedOutput.resolveSibling("template-fill_20260720_000000.pptx"), "pptx");
                    }
                    return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
                });
        PptWorkflowEvents events = org.mockito.Mockito.mock(PptWorkflowEvents.class);
        PptTemplateFillCommandExecutor executor = new PptTemplateFillCommandExecutor(
                new PptMasterProperties(tempDir, tempDir, "python3", Duration.ofSeconds(1), null, 1024),
                repository, events, commands);

        executor.execute(jobId, workspace.resolve("analysis/fill_plan.json"));

        assertThat(job.status()).isEqualTo(domi.argenticpptmaster.domain.PptJobStatus.COMPLETED);
        assertThat(job.exportPath()).isPresent();
        assertThat(job.exportPath().orElseThrow().getParent())
                .isEqualTo(workspace.resolve("exports").toAbsolutePath().normalize());
        ArgumentCaptor<String> scripts = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> arguments = ArgumentCaptor.forClass(List.class);
        verify(commands, org.mockito.Mockito.times(6)).runPythonScript(scripts.capture(), arguments.capture());
        assertThat(scripts.getAllValues()).containsExactly(
                "skills/ppt-master/scripts/project_manager.py",
                "skills/ppt-master/scripts/project_manager.py",
                "skills/ppt-master/scripts/template_fill_pptx.py",
                "skills/ppt-master/scripts/template_fill_pptx.py",
                "skills/ppt-master/scripts/template_fill_pptx.py",
                "skills/ppt-master/scripts/template_fill_pptx.py");
        assertThat(arguments.getAllValues().stream().flatMap(List::stream))
                .anyMatch(argument -> argument.contains("$(touch marker)"));
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
        Path workspace = tempDir.resolve("job");
        Path templatePath = workspace.resolve("uploads/template/0-brand.pptx");
        Files.createDirectories(templatePath.getParent());
        Files.writeString(templatePath, "template");
        Files.createDirectories(workspace.resolve("analysis"));
        Files.writeString(workspace.resolve("analysis/fill_plan.json"), "{\"status\":\"confirmed\"}");
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L, templatePath));
        job.addSource(new PptSourceFile("source.md", "text/markdown", 8L,
                workspace.resolve("uploads/content/0-source.md")));
        Files.createDirectories(job.sourceFiles().get(0).storedPath().getParent());
        Files.writeString(job.sourceFiles().get(0).storedPath(), "# source");
        PptJobRepository repository = org.mockito.Mockito.mock(PptJobRepository.class);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        PptMasterCommandExecutor commands = org.mockito.Mockito.mock(PptMasterCommandExecutor.class);
        when(commands.runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList()))
                .thenReturn(new PptMasterCommandExecutor.CommandResult(1, "check failed", false));
        PptTemplateFillCommandExecutor executor = new PptTemplateFillCommandExecutor(
                new PptMasterProperties(tempDir, tempDir, "python3", Duration.ofSeconds(1), null, 1024),
                repository, org.mockito.Mockito.mock(PptWorkflowEvents.class), commands);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executor.execute(jobId, workspace.resolve("analysis/fill_plan.json")))
                .isInstanceOf(PptTemplateFillExecutionException.class);
        assertThat(job.status()).isEqualTo(domi.argenticpptmaster.domain.PptJobStatus.FAILED);
        verify(commands, never()).runPythonScript(
                org.mockito.ArgumentMatchers.eq("skills/ppt-master/scripts/template_fill_pptx.py"),
                org.mockito.ArgumentMatchers.argThat(args -> args.contains("apply")));
    }

    @Test
    void rejectsTemplatePathOutsideJobWorkspaceBeforeStartingProcess() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("job");
        Path outside = tempDir.resolve("outside.pptx");
        Files.writeString(outside, "template");
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L, outside));
        PptJobRepository repository = org.mockito.Mockito.mock(PptJobRepository.class);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        PptMasterCommandExecutor commands = org.mockito.Mockito.mock(PptMasterCommandExecutor.class);
        PptTemplateFillCommandExecutor executor = new PptTemplateFillCommandExecutor(
                new PptMasterProperties(tempDir, tempDir, "python3", Duration.ofSeconds(1), null, 1024),
                repository, org.mockito.Mockito.mock(PptWorkflowEvents.class), commands);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executor.execute(jobId, workspace.resolve("analysis/fill_plan.json")))
                .isInstanceOf(PptTemplateFillExecutionException.class)
                .hasMessageContaining("escapes job workspace");
        verify(commands, never()).runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList());
    }
}
