package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.config.NativeEnhancePptxProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.NativeEnhanceErrorCode;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptNodeExecution;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

/**
 * Native-enhance 受限命令编排：托管 init/draft plan、checkpoint 恢复与 notes-only MVP。
 * 永不传递 {@code --force}。
 */
@Component
public class PptNativeEnhanceCommandExecutor {

    static final String NATIVE_ENHANCE_SCRIPT = "skills/ppt-master/scripts/native_enhance_pptx.py";
    static final String PPT_TO_MD_SCRIPT = "skills/ppt-master/scripts/source_to_md/ppt_to_md.py";

    private final PptMasterProperties properties;
    private final PptJobRepository repository;
    private final PptWorkflowEvents events;
    private final PptMasterCommandExecutor commands;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PptNativeEnhanceCommandExecutor(
            PptMasterProperties properties,
            PptJobRepository repository,
            PptWorkflowEvents events,
            PptMasterCommandExecutor commands) {
        this.properties = properties;
        this.repository = repository;
        this.events = events;
        this.commands = commands;
    }

    /** 阶段 1/2 notes-only：从托管 init 起完整执行。 */
    public void runNotesOnlyMvp(UUID jobId) {
        resumeFromCheckpoint(jobId, null);
    }

    /** 按 checkpoint 跳过已完成阶段继续 notes-only 路径。 */
    public void resumeFromCheckpoint(UUID jobId, PptJobNode checkpoint) {
        PptJob job = repository.findById(jobId).orElseThrow();
        if (job.workflowMode() != PptWorkflowMode.NATIVE_ENHANCE_PPTX) {
            throw new IllegalArgumentException("native-enhance executor requires NATIVE_ENHANCE_PPTX");
        }
        Path workspace = job.workspacePath().toAbsolutePath().normalize();
        try {
            Path source = requireSourcePptx(job, workspace);
            Path project = resolveOrInitProject(job, workspace, source, checkpoint);
            if (!nodeCompleted(job, PptJobNode.ENHANCEMENT_PLAN_CONFIRMED)) {
                confirmNotesOnlyPlan(job, project);
            }
            int slideCount;
            if (nodeCompleted(job, PptJobNode.NOTES_GENERATED)) {
                slideCount = readSlideCount(project);
            } else {
                slideCount = injectMinimalNotes(job, project);
            }
            if (!nodeCompleted(job, PptJobNode.NATIVE_ENHANCE_VALIDATED)) {
                validateProject(job, project);
            }
            Path export;
            if (nodeCompleted(job, PptJobNode.PPT_EXPORTED)) {
                export = requireUniqueEnhancedExport(project.resolve("exports"));
            } else {
                export = applyProject(job, project);
            }
            if (!nodeCompleted(job, PptJobNode.NATIVE_ENHANCE_OUTPUT_VALIDATED)) {
                lightOutputValidate(job, workspace, project, export, slideCount);
            }
        } catch (NativeEnhanceExecutionException ex) {
            fail(job, ex);
            throw ex;
        } catch (IOException | RuntimeException ex) {
            NativeEnhanceExecutionException wrapped = new NativeEnhanceExecutionException(
                    "EXECUTION",
                    NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED,
                    safeMessage(ex.getMessage()),
                    ex);
            fail(job, wrapped);
            throw wrapped;
        }
    }

    private Path resolveOrInitProject(PptJob job, Path workspace, Path source, PptJobNode checkpoint) {
        boolean skipInit = canSkipInit(job, checkpoint);
        if (skipInit) {
            Path existing = job.projectPath().orElse(null);
            if (existing != null && initArtifactsReusable(existing)) {
                return existing.toAbsolutePath().normalize();
            }
        }
        return initProject(job, workspace, source);
    }

    private boolean canSkipInit(PptJob job, PptJobNode checkpoint) {
        if (checkpoint == null) {
            return nodeCompleted(job, PptJobNode.NATIVE_ENHANCE_INITIALIZED)
                    || nodeCompleted(job, PptJobNode.ENHANCEMENT_PLAN_DRAFTED);
        }
        return switch (checkpoint) {
            case NATIVE_ENHANCE_INITIALIZED, ENHANCEMENT_PLAN_DRAFTED, ENHANCEMENT_PLAN_CONFIRMED,
                    NOTES_GENERATED, NATIVE_ENHANCE_VALIDATED, PPT_EXPORTED,
                    NATIVE_ENHANCE_OUTPUT_VALIDATED -> true;
            default -> false;
        };
    }

    private boolean initArtifactsReusable(Path projectDir) {
        return Files.isRegularFile(projectDir.resolve("project.json"))
                && Files.isRegularFile(projectDir.resolve("analysis").resolve("slide_index.json"))
                && Files.isRegularFile(projectDir.resolve("analysis").resolve("enhancement_plan.json"))
                && !Files.exists(projectDir.resolve("svg_output"));
    }

    private Path initProject(PptJob job, Path workspace, Path source) {
        NativeEnhancePptxProperties limits = properties.nativeEnhancePptx();
        Path projectsRoot = requireInside(workspace, workspace.resolve("projects"), "projects root");
        Path projectDir = requireInside(workspace, projectsRoot.resolve(sanitizeSlug(job.projectName())), "project");
        try {
            Files.createDirectories(projectsRoot);
        } catch (IOException ex) {
            throw new NativeEnhanceExecutionException(
                    "INIT", NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, "failed to create projects root", ex);
        }
        List<String> args = new ArrayList<>();
        args.add("init");
        args.add(source.toString());
        args.add("--name");
        args.add(sanitizeSlug(job.projectName()));
        args.add("--project-dir");
        args.add(projectDir.toString());
        args.add("--projects-root");
        args.add(projectsRoot.toString());
        run(job, "INIT", NATIVE_ENHANCE_SCRIPT, args, limits.initTimeout(),
                NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED);
        requireFile(projectDir.resolve("project.json"), "INIT");
        requireFile(projectDir.resolve("analysis").resolve("slide_index.json"), "INIT");
        requireFile(projectDir.resolve("analysis").resolve("enhancement_plan.json"), "INIT");
        if (Files.exists(projectDir.resolve("svg_output"))) {
            throw new NativeEnhanceExecutionException(
                    "INIT", NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, "svg_output must not be created");
        }
        job.prepareProject(projectDir);
        int slideCount = readSlideCount(projectDir);
        List<String> draftModules = readEnabledModules(projectDir.resolve("analysis").resolve("enhancement_plan.json"));
        if (draftModules.isEmpty()) {
            draftModules = List.of("notes");
        }
        recordCheckpoint(job, PptJobNode.PROJECT_READY, Map.of(
                "projectReady", true,
                "sourcePageCount", slideCount));
        recordCheckpoint(job, PptJobNode.NATIVE_ENHANCE_INITIALIZED, Map.of(
                "sourcePageCount", slideCount,
                "schema", "native_pptx_enhancement_project.v1",
                "slideIndexReady", true,
                "sourcesArchived", true));
        recordCheckpoint(job, PptJobNode.ENHANCEMENT_PLAN_DRAFTED, Map.of(
                "enhancementPlanStatus", "draft",
                "enabledModules", draftModules,
                "sourcePageCount", slideCount));
        return projectDir;
    }

    private void confirmNotesOnlyPlan(PptJob job, Path project) {
        Path planPath = requireInside(project, project.resolve("analysis").resolve("enhancement_plan.json"), "plan");
        // Ensure draft checkpoint exists before MVP server-write confirm (Phase 3 hook).
        if (!nodeCompleted(job, PptJobNode.ENHANCEMENT_PLAN_DRAFTED)) {
            int slideCount = readSlideCount(project);
            recordCheckpoint(job, PptJobNode.ENHANCEMENT_PLAN_DRAFTED, Map.of(
                    "enhancementPlanStatus", "draft",
                    "enabledModules", List.of("notes"),
                    "sourcePageCount", slideCount));
        }
        try {
            JsonNode root = objectMapper.readTree(planPath.toFile());
            if (!(root instanceof ObjectNode plan)) {
                throw new NativeEnhanceExecutionException(
                        "PLAN", NativeEnhanceErrorCode.NATIVE_ENHANCE_PLAN_FAILED, "enhancement plan must be an object");
            }
            plan.put("status", "confirmed");
            ObjectNode modules = plan.with("modules");
            enableModule(modules, "notes", true);
            enableModule(modules, "audio", false);
            enableModule(modules, "timings", false);
            enableModule(modules, "transitions", false);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(planPath.toFile(), plan);
        } catch (NativeEnhanceExecutionException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new NativeEnhanceExecutionException(
                    "PLAN", NativeEnhanceErrorCode.NATIVE_ENHANCE_PLAN_FAILED, "failed to confirm notes-only plan", ex);
        }
        recordCheckpoint(job, PptJobNode.ENHANCEMENT_PLAN_CONFIRMED, Map.of(
                "enhancementPlanStatus", "confirmed",
                "enabledModules", List.of("notes"),
                "confirmationMode", "mvp-server-write",
                "audioVoiceStatus", "skipped"));
    }

    private static void enableModule(ObjectNode modules, String name, boolean enabled) {
        ObjectNode module = modules.with(name);
        module.put("enabled", enabled);
    }

    private int injectMinimalNotes(PptJob job, Path project) throws IOException {
        Path notesDir = requireInside(project, project.resolve("notes"), "notes");
        Files.createDirectories(notesDir);
        Path slideIndex = requireInside(project, project.resolve("analysis").resolve("slide_index.json"), "slide index");
        JsonNode index = objectMapper.readTree(slideIndex.toFile());
        JsonNode slides = index.path("slides");
        if (!slides.isArray() || slides.isEmpty()) {
            throw new NativeEnhanceExecutionException(
                    "NOTES", NativeEnhanceErrorCode.NATIVE_ENHANCE_PLAN_FAILED, "slide_index slides are missing");
        }
        int count = 0;
        for (JsonNode slide : slides) {
            int slideNo = slide.path("index").asInt(slide.path("slide_number").asInt(0));
            if (slideNo <= 0) {
                continue;
            }
            String noteFile = slide.path("note_file").asText(String.format("%03d.md", slideNo));
            Path notePath = requireInside(notesDir, notesDir.resolve(Path.of(noteFile).getFileName().toString()), "note");
            String titleHint = slide.path("title").asText("slide " + slideNo);
            String body = "This is slide " + slideNo + ". " + titleHint
                    + ". Please review the visible content on this page.";
            Files.writeString(notePath, body + System.lineSeparator(), StandardCharsets.UTF_8);
            count++;
        }
        recordCheckpoint(job, PptJobNode.NOTES_GENERATED, Map.of(
                "notesCoverage", Map.of("ready", count, "total", count),
                "audioCoverage", Map.of("ready", 0, "total", 0),
                "audioVoiceStatus", "skipped"));
        return count;
    }

    private void validateProject(PptJob job, Path project) {
        NativeEnhancePptxProperties limits = properties.nativeEnhancePptx();
        run(job, "VALIDATE", NATIVE_ENHANCE_SCRIPT, List.of("validate", project.toString()),
                limits.validateTimeout(), NativeEnhanceErrorCode.NATIVE_ENHANCE_VALIDATE_FAILED);
        Path report = project.resolve("validation").resolve("report.json");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("validated", true);
        Map<String, Object> reportSummary = new LinkedHashMap<>();
        if (Files.isRegularFile(report)) {
            try {
                JsonNode node = objectMapper.readTree(report.toFile());
                String planStatus = node.path("plan_status").asText("");
                int notesCount = node.path("notes_count").asInt(0);
                int missingNotes = node.path("missing_notes").size();
                int missingAudio = node.path("missing_audio").size();
                summary.put("planStatus", planStatus);
                summary.put("notesCount", notesCount);
                summary.put("missingNotes", missingNotes);
                reportSummary.put("plan_status", planStatus);
                reportSummary.put("missingNotes", missingNotes);
                reportSummary.put("missingAudio", missingAudio);
            } catch (IOException ignored) {
                // keep minimal summary
            }
        }
        if (!reportSummary.isEmpty()) {
            summary.put("validateReportSummary", reportSummary);
        }
        recordCheckpoint(job, PptJobNode.NATIVE_ENHANCE_VALIDATED, summary);
    }

    private Path applyProject(PptJob job, Path project) throws IOException {
        NativeEnhancePptxProperties limits = properties.nativeEnhancePptx();
        Path exportsDir = requireInside(project, project.resolve("exports"), "exports");
        Files.createDirectories(exportsDir);
        job.startExport();
        repository.save(job);
        List<String> args = new ArrayList<>();
        args.add("apply");
        args.add(project.toString());
        args.add("--overwrite");
        run(job, "APPLY", NATIVE_ENHANCE_SCRIPT, args, limits.applyTimeout(),
                NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED);
        Path export = requireUniqueEnhancedExport(exportsDir);
        recordCheckpoint(job, PptJobNode.PPT_EXPORTED, Map.of(
                "exportFileName", export.getFileName().toString()));
        return export;
    }

    private void lightOutputValidate(PptJob job, Path workspace, Path project, Path export, int expectedSlides)
            throws IOException {
        NativeEnhancePptxProperties limits = properties.nativeEnhancePptx();
        int actualSlides = countSlides(export);
        if (expectedSlides > 0 && actualSlides != expectedSlides) {
            throw new NativeEnhanceExecutionException(
                    "OUTPUT",
                    NativeEnhanceErrorCode.NATIVE_ENHANCE_OUTPUT_INVALID,
                    "slide count mismatch: expected=" + expectedSlides + ", actual=" + actualSlides);
        }
        Path validationDir = requireInside(project, project.resolve("validation"), "validation");
        Files.createDirectories(validationDir);
        Path readback = validationDir.resolve("readback.md");
        run(job, "READBACK", PPT_TO_MD_SCRIPT,
                List.of(export.toString(), "-o", readback.toString()),
                limits.applyTimeout(),
                NativeEnhanceErrorCode.NATIVE_ENHANCE_OUTPUT_INVALID);
        Path deliveryDir = requireInside(workspace, workspace.resolve("exports"), "delivery exports");
        Files.createDirectories(deliveryDir);
        Path delivery = requireInside(workspace, deliveryDir.resolve(export.getFileName().toString()), "delivery");
        Files.copy(export, delivery, StandardCopyOption.REPLACE_EXISTING);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("exportFileName", delivery.getFileName().toString());
        summary.put("sourcePageCount", expectedSlides);
        summary.put("actualPageCount", actualSlides);
        summary.put("readbackPresent", Files.isRegularFile(readback));
        summary.put("pageCountMatch", expectedSlides <= 0 || expectedSlides == actualSlides);
        recordCheckpoint(job, PptJobNode.NATIVE_ENHANCE_OUTPUT_VALIDATED, summary);
        job.complete(delivery);
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.EXPORT_READY, "native-enhance export ready",
                Map.of("fileName", delivery.getFileName().toString(), "sourcePageCount", expectedSlides)));
    }

    private Path requireSourcePptx(PptJob job, Path workspace) {
        if (job.sourceFiles().size() != 1) {
            throw new NativeEnhanceExecutionException(
                    "INIT", NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, "exactly one source pptx is required");
        }
        return requireInside(workspace, job.sourceFiles().get(0).storedPath(), "source");
    }

    private int readSlideCount(Path project) {
        Path slideIndex = project.resolve("analysis").resolve("slide_index.json");
        if (!Files.isRegularFile(slideIndex)) {
            return 0;
        }
        try {
            return objectMapper.readTree(slideIndex.toFile()).path("slide_count").asInt(0);
        } catch (IOException ex) {
            return 0;
        }
    }

    private List<String> readEnabledModules(Path planPath) {
        if (!Files.isRegularFile(planPath)) {
            return List.of();
        }
        try {
            JsonNode modules = objectMapper.readTree(planPath.toFile()).path("modules");
            if (!modules.isObject()) {
                return List.of();
            }
            List<String> enabled = new ArrayList<>();
            modules.fields().forEachRemaining(entry -> {
                if (entry.getValue().path("enabled").asBoolean(false)) {
                    enabled.add(entry.getKey());
                }
            });
            return List.copyOf(enabled);
        } catch (IOException ex) {
            return List.of();
        }
    }

    private Path requireUniqueEnhancedExport(Path exportsDir) {
        List<Path> exports;
        try (Stream<Path> stream = Files.list(exportsDir)) {
            exports = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pptx"))
                    .filter(path -> path.getFileName().toString().contains("_enhanced"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new NativeEnhanceExecutionException(
                    "APPLY", NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED, "failed to list exports", ex);
        }
        if (exports.size() != 1) {
            throw new NativeEnhanceExecutionException(
                    "APPLY",
                    NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED,
                    "expected exactly one *_enhanced.pptx export, found=" + exports.size());
        }
        return exports.get(0);
    }

    private static int countSlides(Path exportPptx) throws IOException {
        int count = 0;
        try (ZipFile zip = new ZipFile(exportPptx.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("ppt/slides/slide") && name.endsWith(".xml") && !name.contains("_rels")) {
                    count++;
                }
            }
        }
        return count;
    }

    private void run(
            PptJob job,
            String stage,
            String script,
            List<String> arguments,
            Duration timeout,
            NativeEnhanceErrorCode errorCode) {
        rejectUnsafeCommand(stage, script, arguments);
        events.record(job, PptJobEvent.of(PptJobEventType.NODE_STARTED,
                "native-enhance stage started", Map.of("stage", stage)));
        PptMasterCommandExecutor.CommandResult result =
                commands.runPythonScript(script, List.copyOf(arguments), timeout);
        if (!result.successful()) {
            throw new NativeEnhanceExecutionException(
                    stage,
                    errorCode,
                    "native-enhance " + stage.toLowerCase() + " failed (exitCode=" + result.exitCode()
                            + ", timedOut=" + result.timedOut() + "): " + safeMessage(result.output()));
        }
    }

    static void rejectUnsafeCommand(String stage, String script, List<String> arguments) {
        if (!NATIVE_ENHANCE_SCRIPT.equals(script) && !PPT_TO_MD_SCRIPT.equals(script)) {
            throw new NativeEnhanceExecutionException(
                    stage, NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, "script is not allowed: " + script);
        }
        if (script.contains("svg") || script.contains("template_fill") || script.contains("beautify")) {
            throw new NativeEnhanceExecutionException(
                    stage, NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, "svg/template/beautify tooling is not allowed");
        }
        for (String argument : arguments) {
            if (argument != null && argument.contains("--force")) {
                throw new NativeEnhanceExecutionException(
                        stage, NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED, "force execution is not allowed");
            }
        }
    }

    private void recordCheckpoint(PptJob job, PptJobNode node, Map<String, Object> summary) {
        job.startNode(node);
        job.completeNode(node, summary);
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.NODE_COMPLETED,
                "node completed: " + node.name(),
                Map.of("node", node.name(), "summary", sanitizeSummary(summary))));
    }

    private void fail(PptJob job, NativeEnhanceExecutionException ex) {
        String message = ex.errorCode().code() + ": " + safeMessage(ex.getMessage());
        if (job.currentNode().isPresent()) {
            job.failNode(job.currentNode().get(), message);
        } else {
            job.fail(message);
        }
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.JOB_FAILED, "native-enhance failed",
                Map.of("stage", ex.stage(), "errorCode", ex.errorCode().code())));
    }

    private static Map<String, Object> sanitizeSummary(Map<String, Object> summary) {
        Map<String, Object> safe = new LinkedHashMap<>();
        summary.forEach((key, value) -> {
            if (value instanceof Path path) {
                safe.put(key, path.getFileName().toString());
            } else if (value instanceof String text && looksLikeAbsolutePath(text)) {
                safe.put(key, Path.of(text).getFileName().toString());
            } else {
                safe.put(key, value);
            }
        });
        return Map.copyOf(safe);
    }

    private static boolean looksLikeAbsolutePath(String text) {
        return text.startsWith("/") || text.matches("^[A-Za-z]:\\\\.*");
    }

    private static boolean nodeCompleted(PptJob job, PptJobNode node) {
        PptNodeExecution execution = job.nodeExecution(node);
        return execution != null && execution.status() == PptJobNodeStatus.COMPLETED;
    }

    private static Path requireInside(Path root, Path candidate, String label) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw new NativeEnhanceExecutionException(
                    "PATH", NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, label + " escapes workspace");
        }
        return normalized;
    }

    private static void requireFile(Path path, String stage) {
        if (!Files.isRegularFile(path)) {
            throw new NativeEnhanceExecutionException(
                    stage, NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, "missing required file: " + path.getFileName());
        }
    }

    static String sanitizeSlug(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            return "native_enhance";
        }
        String slug = projectName.trim().replaceAll("[^0-9A-Za-z_-]", "_");
        return slug.isBlank() ? "native_enhance" : slug;
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
