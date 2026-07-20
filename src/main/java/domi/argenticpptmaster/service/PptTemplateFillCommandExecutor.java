package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptTemplateFile;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 原生 PPTX 模板填充的固定命令编排器。
 */
@Component
public class PptTemplateFillCommandExecutor {

    private static final String PROJECT_MANAGER = "skills/ppt-master/scripts/project_manager.py";
    private static final String TEMPLATE_FILL = "skills/ppt-master/scripts/template_fill_pptx.py";
    private static final Pattern PROJECT_OUTPUT = Pattern.compile("(?m)^\\[OK\\] Project initialized: (.+)$");

    private final PptMasterProperties properties;
    private final PptJobRepository repository;
    private final PptWorkflowEvents events;
    private final PptMasterCommandExecutor commands;

    public PptTemplateFillCommandExecutor(
            PptMasterProperties properties,
            PptJobRepository repository,
            PptWorkflowEvents events,
            PptMasterCommandExecutor commands) {
        this.properties = properties;
        this.repository = repository;
        this.events = events;
        this.commands = commands;
    }

    public void execute(UUID jobId, Path planPath) {
        PptJob job = repository.findById(jobId)
                .orElseThrow(() -> new PptTemplateFillExecutionException("LOAD", "template-fill job not found"));
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            throw new PptTemplateFillExecutionException("LOAD", "job is not a template-fill workflow");
        }
        Path workspace = job.workspacePath().toAbsolutePath().normalize();
        Path confirmedPlan = requireInside(workspace, planPath, "plan");
        PptTemplateFile template = job.template().orElseThrow(
                () -> new PptTemplateFillExecutionException("LOAD", "template file is missing"));
        Path templatePath = requireInside(workspace, template.storedPath(), "template");
        if (!Files.isRegularFile(confirmedPlan) || !Files.isRegularFile(templatePath)) {
            throw new PptTemplateFillExecutionException("LOAD", "template-fill input file is missing");
        }

        try {
            Path projectsDir = workspace.resolve("projects").normalize();
            CommandResult init = run(job, "INIT", PROJECT_MANAGER, List.of(
                    "init", job.projectName(), "--format", job.format(), "--dir", projectsDir.toString()));
            Path project = parseProjectPath(init.output(), workspace);
            job.prepareProject(project);
            repository.save(job);
            stageCompleted("INIT", job);

            List<String> importArgs = new ArrayList<>();
            importArgs.add("import-sources");
            importArgs.add(project.toString());
            for (var source : job.sourceFiles()) {
                importArgs.add(requireInside(workspace, source.storedPath(), "content").toString());
            }
            importArgs.add("--copy");
            run(job, "IMPORT", PROJECT_MANAGER, importArgs);

            Path analysisDir = requireInside(workspace, project.resolve("analysis"), "analysis");
            Files.createDirectories(analysisDir);
            Path slideLibrary = analysisDir.resolve("template.slide_library.json").normalize();
            run(job, "ANALYZE", TEMPLATE_FILL, List.of(
                    "analyze", templatePath.toString(), "-o", slideLibrary.toString()));
            requireFile(slideLibrary, "ANALYZE");
            stageCompleted("ANALYZE", job);

            Path projectPlan = analysisDir.resolve("fill_plan.json").normalize();
            requireInside(workspace, projectPlan, "fill plan");
            Files.copy(confirmedPlan, projectPlan, StandardCopyOption.REPLACE_EXISTING);
            Path checkReport = analysisDir.resolve("check_report.json").normalize();
            run(job, "CHECK_PLAN", TEMPLATE_FILL, List.of(
                    "check-plan", slideLibrary.toString(), projectPlan.toString(), "-o", checkReport.toString()));
            stageCompleted("CHECK_PLAN", job);

            Path exportsDir = requireInside(workspace, project.resolve("exports"), "exports");
            Files.createDirectories(exportsDir);
            if (!findPptxExports(exportsDir).isEmpty()) {
                throw new PptTemplateFillExecutionException("APPLY", "exports directory already contains a PPTX");
            }
            Path deliveryDir = requireInside(workspace, workspace.resolve("exports"), "delivery exports");
            Files.createDirectories(deliveryDir);
            if (!findPptxExports(deliveryDir).isEmpty()) {
                throw new PptTemplateFillExecutionException("APPLY", "delivery exports directory already contains a PPTX");
            }
            Path exportPath = exportsDir.resolve("template-fill.pptx").normalize();
            job.startExport();
            repository.save(job);
            run(job, "APPLY", TEMPLATE_FILL, List.of(
                    "apply", templatePath.toString(), projectPlan.toString(), "-o", exportPath.toString()));
            Path generatedExport = requireUniqueExport(exportsDir);
            stageCompleted("APPLY", job);

            run(job, "VALIDATE", TEMPLATE_FILL, List.of("validate", project.toString()));
            requireFile(generatedExport, "VALIDATE");
            Path deliveryExport = deliveryDir.resolve(generatedExport.getFileName()).normalize();
            requireInside(workspace, deliveryExport, "delivery export");
            Files.copy(generatedExport, deliveryExport);
            stageCompleted("VALIDATE", job);
            job.complete(deliveryExport);
            repository.save(job);
            events.record(job, PptJobEvent.of(PptJobEventType.EXPORT_READY, "template-fill export ready",
                    Map.of("fileName", deliveryExport.getFileName().toString())));
        } catch (PptTemplateFillExecutionException ex) {
            fail(job, ex);
            throw ex;
        } catch (IOException | RuntimeException ex) {
            PptTemplateFillExecutionException wrapped = new PptTemplateFillExecutionException(
                    "EXECUTION", safeMessage(ex.getMessage()), ex);
            fail(job, wrapped);
            throw wrapped;
        }
    }

    private CommandResult run(PptJob job, String stage, String script, List<String> arguments) {
        events.record(job, PptJobEvent.of(PptJobEventType.TEMPLATE_FILL_STAGE_STARTED,
                "template-fill stage started", Map.of("stage", stage)));
        PptMasterCommandExecutor.CommandResult result = commands.runPythonScript(script, List.copyOf(arguments));
        if (!result.successful()) {
            throw new PptTemplateFillExecutionException(stage,
                    "template-fill " + stage.toLowerCase() + " failed (exitCode=" + result.exitCode()
                            + ", timedOut=" + result.timedOut() + "): " + safeMessage(result.output()));
        }
        return new CommandResult(result.output());
    }

    private void stageCompleted(String stage, PptJob job) {
        events.record(job, PptJobEvent.of(PptJobEventType.TEMPLATE_FILL_STAGE_COMPLETED,
                "template-fill stage completed", Map.of("stage", stage)));
    }

    private void fail(PptJob job, PptTemplateFillExecutionException ex) {
        if (job.status() != PptJobStatus.FAILED && job.status() != PptJobStatus.COMPLETED) {
            job.fail(ex.getMessage());
        }
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.JOB_FAILED, "template-fill failed",
                Map.of("stage", ex.stage(), "error", safeMessage(ex.getMessage()))));
    }

    private Path parseProjectPath(String output, Path workspace) {
        Matcher matcher = PROJECT_OUTPUT.matcher(output == null ? "" : output);
        if (!matcher.find()) {
            throw new PptTemplateFillExecutionException("INIT", "project initialization did not return a project path");
        }
        return requireInside(workspace, Path.of(matcher.group(1).trim()), "project");
    }

    private Path requireInside(Path workspace, Path path, String label) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspace)) {
            throw new PptTemplateFillExecutionException("SECURITY", label + " path escapes job workspace");
        }
        return normalized;
    }

    private void requireFile(Path path, String stage) {
        if (!Files.isRegularFile(path)) {
            throw new PptTemplateFillExecutionException(stage, "expected output is missing");
        }
    }

    private Path requireUniqueExport(Path exportsDir) throws IOException {
        List<Path> outputs = findPptxExports(exportsDir);
        if (outputs.size() != 1) {
            throw new PptTemplateFillExecutionException("VALIDATE", "expected exactly one PPTX export");
        }
        return outputs.get(0);
    }

    private List<Path> findPptxExports(Path exportsDir) throws IOException {
        List<Path> outputs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(exportsDir, "*.pptx")) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    outputs.add(path.toAbsolutePath().normalize());
                }
            }
        }
        return outputs;
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "no diagnostic output";
        }
        String sanitized = message.replace(properties.repoPath().toString(), "<repo>")
                .replace(properties.workspacePath().toString(), "<workspace>")
                .replaceAll("\\s+", " ").trim();
        return sanitized.length() <= 400 ? sanitized : sanitized.substring(0, 400);
    }

    private record CommandResult(String output) {
    }
}
