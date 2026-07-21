package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.FillPlanStatus;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptTemplateFile;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.domain.TemplateFillAnalysisSummary;
import domi.argenticpptmaster.domain.TemplateFillErrorCode;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 原生 PPTX 模板填充的 checkpoint 感知命令编排器。
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
    private final PptTemplateFillAnalysisReader analysisReader;
    private final PptTemplateFillConcurrencyLimiter concurrencyLimiter;
    private final TemplateFillCapabilityIndexLoader capabilityIndexLoader;
    private final TemplateFillConstraintResolver constraintResolver;
    private final PptTemplateFillPlanStore planStore;
    private final TemplateFillOutputVerifier outputVerifier;
    private final TemplateFillLifecycleStore lifecycleStore;
    private final TemplateFillTelemetry telemetry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PptTemplateFillCommandExecutor(
            PptMasterProperties properties,
            PptJobRepository repository,
            PptWorkflowEvents events,
            PptMasterCommandExecutor commands,
            PptTemplateFillAnalysisReader analysisReader,
            PptTemplateFillConcurrencyLimiter concurrencyLimiter,
            TemplateFillCapabilityIndexLoader capabilityIndexLoader,
            TemplateFillConstraintResolver constraintResolver,
            PptTemplateFillPlanStore planStore,
            TemplateFillOutputVerifier outputVerifier,
            TemplateFillLifecycleStore lifecycleStore) {
        this(properties, repository, events, commands, analysisReader, concurrencyLimiter,
                capabilityIndexLoader, constraintResolver, planStore, outputVerifier, lifecycleStore, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PptTemplateFillCommandExecutor(
            PptMasterProperties properties,
            PptJobRepository repository,
            PptWorkflowEvents events,
            PptMasterCommandExecutor commands,
            PptTemplateFillAnalysisReader analysisReader,
            PptTemplateFillConcurrencyLimiter concurrencyLimiter,
            TemplateFillCapabilityIndexLoader capabilityIndexLoader,
            TemplateFillConstraintResolver constraintResolver,
            PptTemplateFillPlanStore planStore,
            TemplateFillOutputVerifier outputVerifier,
            TemplateFillLifecycleStore lifecycleStore,
            TemplateFillTelemetry telemetry) {
        this.properties = properties;
        this.repository = repository;
        this.events = events;
        this.commands = commands;
        this.analysisReader = analysisReader;
        this.concurrencyLimiter = concurrencyLimiter;
        this.capabilityIndexLoader = capabilityIndexLoader;
        this.constraintResolver = constraintResolver;
        this.planStore = planStore;
        this.outputVerifier = outputVerifier;
        this.lifecycleStore = lifecycleStore == null ? new TemplateFillLifecycleStore(properties) : lifecycleStore;
        this.telemetry = telemetry;
    }

    /** 执行 confirmed plan 全流程（支持 checkpoint 跳过）。 */
    public void execute(UUID jobId, Path planPath) {
        executeInternal(jobId, planPath, ExecutionMode.FULL_WITH_PLAN);
    }

    /** 仅运行工作区准备与分析至 {@link PptJobNode#TEMPLATE_ANALYZED}。 */
    public void prepareAndAnalyze(UUID jobId) {
        executeInternal(jobId, null, ExecutionMode.PREPARE_ONLY);
    }

    /** 从指定 checkpoint 恢复模板填充执行。 */
    public void resumeFromCheckpoint(UUID jobId, PptJobNode checkpoint, Path planPath) {
        executeInternal(jobId, planPath, mapCheckpointToMode(checkpoint));
    }

    private enum ExecutionMode {
        FULL_WITH_PLAN,
        PREPARE_ONLY,
        RESUME_FROM_PROJECT_READY,
        RESUME_FROM_TEMPLATE_ANALYZED,
        RESUME_FROM_FILL_PLAN_VALIDATED
    }

    private static ExecutionMode mapCheckpointToMode(PptJobNode checkpoint) {
        return switch (checkpoint) {
            case PROJECT_READY -> ExecutionMode.RESUME_FROM_PROJECT_READY;
            case TEMPLATE_ANALYZED -> ExecutionMode.RESUME_FROM_TEMPLATE_ANALYZED;
            case FILL_PLAN_VALIDATED -> ExecutionMode.RESUME_FROM_FILL_PLAN_VALIDATED;
            default -> throw new PptTemplateFillExecutionException(
                    "RESUME", "unsupported template-fill checkpoint: " + checkpoint);
        };
    }

    private void executeInternal(UUID jobId, Path planPath, ExecutionMode mode) {
        concurrencyLimiter.acquire();
        PptJob job = repository.findById(jobId)
                .orElseThrow(() -> new PptTemplateFillExecutionException("LOAD", "template-fill job not found"));
        try {
            runExecution(job, planPath, mode);
        } finally {
            concurrencyLimiter.release();
        }
    }

    private void runExecution(PptJob job, Path planPath, ExecutionMode mode) {
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            throw new PptTemplateFillExecutionException("LOAD", "job is not a template-fill workflow");
        }
        Path workspace = job.workspacePath().toAbsolutePath().normalize();
        PptTemplateFile template = job.template().orElseThrow(
                () -> new PptTemplateFillExecutionException("LOAD", "template file is missing"));
        Path templatePath = requireInside(workspace, template.storedPath(), "template");
        if (!Files.isRegularFile(templatePath)) {
            throw new PptTemplateFillExecutionException("LOAD", "template-fill input file is missing");
        }
        Path confirmedPlan = planPath == null ? null : requireInside(workspace, planPath, "plan");
        if (mode == ExecutionMode.FULL_WITH_PLAN || mode == ExecutionMode.RESUME_FROM_TEMPLATE_ANALYZED) {
            if (confirmedPlan == null || !Files.isRegularFile(confirmedPlan)) {
                throw new PptTemplateFillExecutionException("LOAD", "confirmed fill plan is missing");
            }
            requireConfirmedPlanContent(confirmedPlan);
        }

        try {
            Path project = resolveProject(job, workspace, mode);
            Path analysisDir = requireInside(workspace, project.resolve("analysis"), "analysis");
            Files.createDirectories(analysisDir);
            Path slideLibrary = analysisDir.resolve("template.slide_library.json").normalize();
            Path projectPlan = analysisDir.resolve("fill_plan.json").normalize();
            Path checkReport = analysisDir.resolve("check_report.json").normalize();

            if (shouldRunPrepare(mode)) {
                importSources(job, workspace, project, templatePath);
                recordCheckpoint(job, PptJobNode.PROJECT_READY, Map.of("projectReady", true));
            }

            if (shouldRunAnalyze(mode)) {
                analyzeTemplate(job, templatePath, slideLibrary);
                TemplateFillAnalysisSummary summary = analysisReader.readSummary(slideLibrary);
                var capabilityIndex = capabilityIndexLoader.load(slideLibrary);
                constraintResolver.validateAgainstLibrary(job.templateConstraints(), capabilityIndex);
                job.updateTemplateAnalysis(summary);
                job.updateFillPlanStatus(FillPlanStatus.NONE, 0, 0, 0);
                job.updateNativePlanAggregates(0, 0, 0, 0, 0, "VALID");
                repository.save(job);
                recordCheckpoint(job, PptJobNode.TEMPLATE_ANALYZED, summaryPayload(summary));
            }

            if (mode == ExecutionMode.PREPARE_ONLY) {
                return;
            }

            if (shouldRunValidatePlan(mode)) {
                planStore.assertPlanIntegrity(job);
                Files.copy(confirmedPlan, projectPlan, StandardCopyOption.REPLACE_EXISTING);
                recordCheckpoint(job, PptJobNode.FILL_PLAN_CONFIRMED, Map.of("plan", "confirmed"));
                validatePlan(job, slideLibrary, projectPlan, checkReport);
                int planSlides = analysisReader.readPlanSlideCount(projectPlan);
                int[] counts = analysisReader.readValidationCounts(checkReport);
                job.updateFillPlanStatus(FillPlanStatus.VALIDATED, planSlides, counts[0], counts[1]);
                repository.save(job);
                recordCheckpoint(job, PptJobNode.FILL_PLAN_VALIDATED, Map.of("validated", true));
            }

            if (shouldRunApply(mode)) {
                planStore.assertPlanIntegrity(job);
                Path exportsDir = requireInside(workspace, project.resolve("exports"), "exports");
                Files.createDirectories(exportsDir);
                if (!findPptxExports(exportsDir).isEmpty()) {
                    throw applyFailed("exports directory already contains a PPTX");
                }
                Path deliveryDir = requireInside(workspace, workspace.resolve("exports"), "delivery exports");
                Files.createDirectories(deliveryDir);
                if (!findPptxExports(deliveryDir).isEmpty()) {
                    throw applyFailed("delivery exports directory already contains a PPTX");
                }
                Path exportPath = exportsDir.resolve("template-fill.pptx").normalize();
                job.startExport();
                repository.save(job);
                applyExport(job, templatePath, projectPlan, exportPath);
                recordCheckpoint(job, PptJobNode.PPT_EXPORTED, Map.of("exported", true));
            }

            if (shouldRunValidateOutput(mode)) {
                Path exportsDir = requireInside(workspace, project.resolve("exports"), "exports");
                Path generatedExport = requireUniqueExport(exportsDir);
                validateOutput(job, project);
                requireFile(generatedExport, "VALIDATE");
                Path validationDir = requireInside(workspace, project.resolve("validation"), "validation");
                var metadata = planStore.readMetadata(job).orElse(null);
                TemplateFillOutputVerifier.ReadbackResult readback = outputVerifier.verify(
                        job,
                        generatedExport,
                        projectPlan,
                        validationDir,
                        metadata == null ? "" : metadata.digest(),
                        metadata == null ? 0 : metadata.version());
                repository.save(job);
                if (!readback.passed()) {
                    recordStageTelemetry("READBACK", TemplateFillTelemetry.Outcome.FAILURE, Duration.ZERO);
                    throw new PptTemplateFillExecutionException(
                            "READBACK",
                            "template-fill readback failed",
                            TemplateFillErrorCode.TEMPLATE_READBACK_FAILED);
                }
                recordStageTelemetry("READBACK", TemplateFillTelemetry.Outcome.SUCCESS, Duration.ZERO);
                Path deliveryDir = requireInside(workspace, workspace.resolve("exports"), "delivery exports");
                Path deliveryExport = deliveryDir.resolve(generatedExport.getFileName()).normalize();
                requireInside(workspace, deliveryExport, "delivery export");
                Files.copy(generatedExport, deliveryExport, StandardCopyOption.REPLACE_EXISTING);
                recordCheckpoint(job, PptJobNode.OUTPUT_VALIDATED, Map.of(
                        "exportFileName", deliveryExport.getFileName().toString(),
                        "readbackStatus", readback.status()));
                job.complete(deliveryExport);
                markTerminalLifecycle(job);
                repository.save(job);
                events.record(job, PptJobEvent.of(PptJobEventType.EXPORT_READY, "template-fill export ready",
                        progressPayload(job, Map.of("fileName", deliveryExport.getFileName().toString()))));
            }
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

    private Path resolveProject(PptJob job, Path workspace, ExecutionMode mode) {
        if (job.projectPath().isPresent() && isResumeMode(mode)) {
            return requireInside(workspace, job.projectPath().get(), "project");
        }
        if (job.projectPath().isPresent()) {
            return requireInside(workspace, job.projectPath().get(), "project");
        }
        Path projectsDir = workspace.resolve("projects").normalize();
        CommandResult init = run(job, "INIT", PROJECT_MANAGER, List.of(
                "init", job.projectName(), "--format", job.format(), "--dir", projectsDir.toString()),
                properties.commandTimeout());
        Path project = parseProjectPath(init.output(), workspace);
        job.prepareProject(project);
        repository.save(job);
        stageCompleted("INIT", job);
        return project;
    }

    private static boolean isResumeMode(ExecutionMode mode) {
        return mode == ExecutionMode.RESUME_FROM_PROJECT_READY
                || mode == ExecutionMode.RESUME_FROM_TEMPLATE_ANALYZED
                || mode == ExecutionMode.RESUME_FROM_FILL_PLAN_VALIDATED;
    }

    private void importSources(PptJob job, Path workspace, Path project, Path templatePath) {
        List<String> importArgs = new ArrayList<>();
        importArgs.add("import-sources");
        importArgs.add(project.toString());
        for (var source : job.sourceFiles()) {
            importArgs.add(requireInside(workspace, source.storedPath(), "content").toString());
        }
        importArgs.add(templatePath.toString());
        importArgs.add("--copy");
        run(job, "IMPORT", PROJECT_MANAGER, importArgs, properties.commandTimeout());
        stageCompleted("IMPORT", job);
    }

    private void analyzeTemplate(PptJob job, Path templatePath, Path slideLibrary) {
        run(job, "ANALYZE", TEMPLATE_FILL,
                List.of("analyze", templatePath.toString(), "-o", slideLibrary.toString()),
                properties.templateFillAnalyzeTimeout());
        requireFile(slideLibrary, "ANALYZE");
        stageCompleted("ANALYZE", job);
    }

    private void validatePlan(PptJob job, Path slideLibrary, Path projectPlan, Path checkReport) {
        run(job, "CHECK_PLAN", TEMPLATE_FILL, List.of(
                "check-plan", slideLibrary.toString(), projectPlan.toString(), "-o", checkReport.toString()),
                properties.commandTimeout());
        stageCompleted("CHECK_PLAN", job);
    }

    private void applyExport(PptJob job, Path templatePath, Path projectPlan, Path exportPath) {
        run(job, "APPLY", TEMPLATE_FILL, List.of(
                "apply", templatePath.toString(), projectPlan.toString(), "-o", exportPath.toString()),
                properties.commandTimeout());
        stageCompleted("APPLY", job);
    }

    private void validateOutput(PptJob job, Path project) {
        run(job, "VALIDATE", TEMPLATE_FILL, List.of("validate", project.toString()), properties.commandTimeout());
        stageCompleted("VALIDATE", job);
    }

    private static boolean shouldRunPrepare(ExecutionMode mode) {
        return mode == ExecutionMode.FULL_WITH_PLAN
                || mode == ExecutionMode.PREPARE_ONLY
                || mode == ExecutionMode.RESUME_FROM_PROJECT_READY;
    }

    private static boolean shouldRunAnalyze(ExecutionMode mode) {
        return mode == ExecutionMode.FULL_WITH_PLAN
                || mode == ExecutionMode.PREPARE_ONLY
                || mode == ExecutionMode.RESUME_FROM_PROJECT_READY;
    }

    private static boolean shouldRunValidatePlan(ExecutionMode mode) {
        return mode == ExecutionMode.FULL_WITH_PLAN || mode == ExecutionMode.RESUME_FROM_TEMPLATE_ANALYZED;
    }

    private static boolean shouldRunApply(ExecutionMode mode) {
        return mode == ExecutionMode.FULL_WITH_PLAN || mode == ExecutionMode.RESUME_FROM_FILL_PLAN_VALIDATED;
    }

    private static boolean shouldRunValidateOutput(ExecutionMode mode) {
        return mode == ExecutionMode.FULL_WITH_PLAN || mode == ExecutionMode.RESUME_FROM_FILL_PLAN_VALIDATED;
    }

    private CommandResult run(PptJob job, String stage, String script, List<String> arguments, Duration timeout) {
        rejectUnsafeCommand(stage, script, arguments);
        events.record(job, PptJobEvent.of(PptJobEventType.TEMPLATE_FILL_STAGE_STARTED,
                "template-fill stage started", progressPayload(job, Map.of("stage", stage))));
        Instant started = Instant.now();
        PptMasterCommandExecutor.CommandResult result = commands.runPythonScript(script, List.copyOf(arguments), timeout);
        Duration elapsed = Duration.between(started, Instant.now());
        if (!result.successful()) {
            recordStageTelemetry(stage, TemplateFillTelemetry.Outcome.FAILURE, elapsed);
            throw stageFailure(stage, result);
        }
        recordStageTelemetry(stage, TemplateFillTelemetry.Outcome.SUCCESS, elapsed);
        return new CommandResult(result.output());
    }

    private void requireConfirmedPlanContent(Path planPath) {
        try {
            byte[] bytes = Files.readAllBytes(planPath);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.contains("--force")) {
                throw new PptTemplateFillExecutionException("LOAD", "force execution is not allowed");
            }
            JsonNode root = objectMapper.readTree(bytes);
            if (!"confirmed".equalsIgnoreCase(root.path("status").asText(null))) {
                throw new PptTemplateFillExecutionException("LOAD", "fill plan status must be confirmed");
            }
        } catch (PptTemplateFillExecutionException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new PptTemplateFillExecutionException("LOAD", "confirmed fill plan is invalid");
        }
    }

    private static void rejectUnsafeCommand(String stage, String script, List<String> arguments) {
        if (script.contains("svg") || script.contains("image_gen") || script.contains("finalize_svg")) {
            throw new PptTemplateFillExecutionException(stage, "svg/image tooling is not allowed in template-fill");
        }
        for (String argument : arguments) {
            if (argument != null && argument.contains("--force")) {
                throw new PptTemplateFillExecutionException(stage, "force execution is not allowed");
            }
        }
    }

    private PptTemplateFillExecutionException stageFailure(String stage, PptMasterCommandExecutor.CommandResult result) {
        String message = "template-fill " + stage.toLowerCase() + " failed (exitCode=" + result.exitCode()
                + ", timedOut=" + result.timedOut() + "): " + safeMessage(result.output());
        TemplateFillErrorCode code = switch (stage) {
            case "ANALYZE" -> TemplateFillErrorCode.TEMPLATE_ANALYSIS_FAILED;
            case "CHECK_PLAN" -> TemplateFillErrorCode.FILL_PLAN_INVALID;
            case "APPLY" -> TemplateFillErrorCode.TEMPLATE_APPLY_FAILED;
            case "VALIDATE" -> TemplateFillErrorCode.TEMPLATE_VALIDATE_FAILED;
            case "READBACK" -> TemplateFillErrorCode.TEMPLATE_READBACK_FAILED;
            default -> null;
        };
        return new PptTemplateFillExecutionException(stage, message, code);
    }

    private void stageCompleted(String stage, PptJob job) {
        events.record(job, PptJobEvent.of(PptJobEventType.TEMPLATE_FILL_STAGE_COMPLETED,
                "template-fill stage completed", progressPayload(job, Map.of("stage", stage))));
    }

    private void recordCheckpoint(PptJob job, PptJobNode node, Map<String, Object> summary) {
        job.startNode(node);
        job.completeNode(node, summary);
        repository.save(job);
        events.record(job, PptJobEvent.of(
                PptJobEventType.NODE_COMPLETED,
                "node completed: " + node.name(),
                progressPayload(job, Map.of("node", node.name(), "summary", summary))));
    }

    private Map<String, Object> progressPayload(PptJob job, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("templateAnalysisReady", job.templateAnalysisReady());
        payload.put("fillPlanStatus", job.fillPlanStatus().value());
        job.templateAnalysisSummary().ifPresent(summary -> {
            payload.put("templateSlideCount", summary.templateSlideCount());
            payload.put("textSlotCount", summary.textSlotCount());
        });
        if (job.planSlideCount() > 0) {
            payload.put("planSlideCount", job.planSlideCount());
        }
        if (job.validationWarningCount() > 0) {
            payload.put("validationWarningCount", job.validationWarningCount());
        }
        if (job.validationErrorCount() > 0) {
            payload.put("validationErrorCount", job.validationErrorCount());
        }
        if (job.notesMappingCount() > 0) {
            payload.put("notesMappingCount", job.notesMappingCount());
        }
        if (job.tableMappingCount() > 0) {
            payload.put("tableMappingCount", job.tableMappingCount());
        }
        if (job.chartMappingCount() > 0) {
            payload.put("chartMappingCount", job.chartMappingCount());
        }
        if (job.capacityRiskCount() > 0) {
            payload.put("capacityRiskCount", job.capacityRiskCount());
        }
        if (job.fontAdjustmentCount() > 0) {
            payload.put("fontAdjustmentCount", job.fontAdjustmentCount());
        }
        if (job.constraintValidationStatus() != null) {
            payload.put("constraintValidationStatus", job.constraintValidationStatus());
        }
        if (job.readbackValidationStatus() != null) {
            payload.put("readbackValidationStatus", job.readbackValidationStatus());
            payload.put("readbackWarningCount", job.readbackWarningCount());
            payload.put("readbackErrorCount", job.readbackErrorCount());
        }
        job.exportPath().ifPresent(path -> payload.put("exportFileName", path.getFileName().toString()));
        payload.putAll(extra);
        return payload;
    }

    private static Map<String, Object> summaryPayload(TemplateFillAnalysisSummary summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("templateSlideCount", summary.templateSlideCount());
        payload.put("analysisVersion", summary.analysisVersion());
        payload.put("textSlotCount", summary.textSlotCount());
        payload.put("tableCount", summary.tableCount());
        payload.put("chartCount", summary.chartCount());
        if (summary.formatLabel() != null) {
            payload.put("formatLabel", summary.formatLabel());
        }
        return payload;
    }

    private void fail(PptJob job, PptTemplateFillExecutionException ex) {
        PptJobNode failureNode = mapStageToNode(ex.stage());
        if (failureNode != null) {
            job.startNode(failureNode);
            job.failNode(failureNode, ex.getMessage());
        } else if (job.status() != PptJobStatus.FAILED && job.status() != PptJobStatus.COMPLETED) {
            job.fail(ex.getMessage());
        }
        markTerminalLifecycle(job);
        repository.save(job);
        if (ex.errorCode() != null && telemetry != null) {
            telemetry.recordError(ex.errorCode());
        }
        Map<String, Object> payload = progressPayload(job, Map.of(
                "stage", ex.stage(),
                "error", safeMessage(ex.getMessage())));
        if (ex.errorCode() != null) {
            payload.put("errorCode", ex.errorCode().code());
        }
        events.record(job, PptJobEvent.of(PptJobEventType.JOB_FAILED, "template-fill failed", payload));
    }

    private void recordStageTelemetry(String stage, TemplateFillTelemetry.Outcome outcome, Duration duration) {
        if (telemetry == null) {
            return;
        }
        TemplateFillTelemetry.Stage mapped = switch (stage) {
            case "ANALYZE" -> TemplateFillTelemetry.Stage.ANALYZE;
            case "CHECK_PLAN" -> TemplateFillTelemetry.Stage.CHECK;
            case "APPLY" -> TemplateFillTelemetry.Stage.APPLY;
            case "READBACK" -> TemplateFillTelemetry.Stage.READBACK;
            case "VALIDATE" -> TemplateFillTelemetry.Stage.APPLY; // validate is post-apply integrity; reuse APPLY bucket
            default -> TemplateFillTelemetry.Stage.UNKNOWN;
        };
        telemetry.recordStage(mapped, outcome, duration);
    }

    private void markTerminalLifecycle(PptJob job) {
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL || job.terminalAt().isEmpty()) {
            return;
        }
        if (lifecycleStore.read(job).isEmpty()) {
            return;
        }
        lifecycleStore.markTerminal(job, lifecycleStore.retentionForTerminalStatus(job.status()));
    }

    private static PptTemplateFillExecutionException applyFailed(String message) {
        return new PptTemplateFillExecutionException("APPLY", message, TemplateFillErrorCode.TEMPLATE_APPLY_FAILED);
    }

    private static PptJobNode mapStageToNode(String stage) {
        return switch (stage) {
            case "INIT", "IMPORT" -> PptJobNode.PROJECT_READY;
            case "ANALYZE" -> PptJobNode.TEMPLATE_ANALYZED;
            case "CHECK_PLAN" -> PptJobNode.FILL_PLAN_VALIDATED;
            case "APPLY" -> PptJobNode.PPT_EXPORTED;
            case "VALIDATE" -> PptJobNode.OUTPUT_VALIDATED;
            default -> null;
        };
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
            throw new PptTemplateFillExecutionException(stage, "expected output is missing", mapStageError(stage));
        }
    }

    private static TemplateFillErrorCode mapStageError(String stage) {
        return switch (stage) {
            case "ANALYZE" -> TemplateFillErrorCode.TEMPLATE_ANALYSIS_FAILED;
            case "VALIDATE" -> TemplateFillErrorCode.TEMPLATE_VALIDATE_FAILED;
            default -> null;
        };
    }

    private Path requireUniqueExport(Path exportsDir) throws IOException {
        List<Path> outputs = findPptxExports(exportsDir);
        if (outputs.size() != 1) {
            throw new PptTemplateFillExecutionException(
                    "VALIDATE", "expected exactly one PPTX export", TemplateFillErrorCode.TEMPLATE_VALIDATE_FAILED);
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
