package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.NativeEnhanceErrorCode;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
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
 * 原生增强受限命令编排器：仅允许 {@code native_enhance_pptx.py} 与只读 {@code ppt_to_md.py}，
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

    /** 阶段 1 notes-only MVP：init → confirmed plan → notes → validate → apply → 轻量输出校验。 */
    public void runNotesOnlyMvp(UUID jobId) {
        PptJob job = repository.findById(jobId).orElseThrow();
        if (job.workflowMode() != PptWorkflowMode.NATIVE_ENHANCE_PPTX) {
            throw new IllegalArgumentException("native-enhance executor requires NATIVE_ENHANCE_PPTX");
        }
        Path workspace = job.workspacePath().toAbsolutePath().normalize();
        try {
            Path source = requireSourcePptx(job, workspace);
            Path project = initProject(job, workspace, source);
            confirmNotesOnlyPlan(job, project);
            int slideCount = injectMinimalNotes(job, project);
            validateProject(job, project);
            Path export = applyProject(job, project);
            lightOutputValidate(job, workspace, project, export, slideCount);
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

    private Path initProject(PptJob job, Path workspace, Path source) {
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
        run(job, "INIT", NATIVE_ENHANCE_SCRIPT, args, properties.commandTimeout(),
                NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED);
        requireFile(projectDir.resolve("project.json"), "INIT");
        requireFile(projectDir.resolve("analysis").resolve("slide_index.json"), "INIT");
        requireFile(projectDir.resolve("analysis").resolve("enhancement_plan.json"), "INIT");
        if (Files.exists(projectDir.resolve("svg_output"))) {
            throw new NativeEnhanceExecutionException(
                    "INIT", NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, "svg_output must not be created");
        }
        job.prepareProject(projectDir);
        recordCheckpoint(job, PptJobNode.PROJECT_READY, Map.of("projectReady", true));
        int slideCount = readSlideCount(projectDir);
        recordCheckpoint(job, PptJobNode.NATIVE_ENHANCE_INITIALIZED, Map.of(
                "sourcePageCount", slideCount,
                "schema", "native_pptx_enhancement_project.v1"));
        recordCheckpoint(job, PptJobNode.ENHANCEMENT_PLAN_DRAFTED, Map.of(
                "enhancementPlanStatus", "draft",
                "enabledModules", List.of("notes")));
        return projectDir;
    }

    private void confirmNotesOnlyPlan(PptJob job, Path project) {
        Path planPath = requireInside(project, project.resolve("analysis").resolve("enhancement_plan.json"), "plan");
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
                "confirmationMode", "mvp-server-write"));
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
                "notesCoverage", Map.of("ready", count, "total", count)));
        return count;
    }

    private void validateProject(PptJob job, Path project) {
        run(job, "VALIDATE", NATIVE_ENHANCE_SCRIPT, List.of("validate", project.toString()),
                properties.commandTimeout(), NativeEnhanceErrorCode.NATIVE_ENHANCE_VALIDATE_FAILED);
        Path report = project.resolve("validation").resolve("report.json");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("validated", true);
        if (Files.isRegularFile(report)) {
            try {
                JsonNode node = objectMapper.readTree(report.toFile());
                summary.put("planStatus", node.path("plan_status").asText(""));
                summary.put("notesCount", node.path("notes_count").asInt(0));
                summary.put("missingNotes", node.path("missing_notes").size());
            } catch (IOException ignored) {
                // keep minimal summary
            }
        }
        recordCheckpoint(job, PptJobNode.NATIVE_ENHANCE_VALIDATED, summary);
    }

    private Path applyProject(PptJob job, Path project) throws IOException {
        Path exportsDir = requireInside(project, project.resolve("exports"), "exports");
        Files.createDirectories(exportsDir);
        job.startExport();
        repository.save(job);
        List<String> args = new ArrayList<>();
        args.add("apply");
        args.add(project.toString());
        args.add("--overwrite");
        run(job, "APPLY", NATIVE_ENHANCE_SCRIPT, args, properties.commandTimeout(),
                NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED);
        Path export = requireUniqueEnhancedExport(exportsDir);
        recordCheckpoint(job, PptJobNode.PPT_EXPORTED, Map.of(
                "exportFileName", export.getFileName().toString()));
        return export;
    }

    private void lightOutputValidate(PptJob job, Path workspace, Path project, Path export, int expectedSlides)
            throws IOException {
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
                properties.commandTimeout(),
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
                Map.of("node", node.name(), "summary", summary)));
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
