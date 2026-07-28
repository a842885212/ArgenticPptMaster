package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.config.BeautifyPptxProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.BeautifyPptxErrorCode;
import domi.argenticpptmaster.domain.BeautifyPptxFailureKind;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 服务端拥有的 beautify intake/inventory 编排。
 *
 * <p>仅把相对路径及计数写入 checkpoint summary；原始分析 JSON 始终留在项目工作区。</p>
 */
@Component
public class BeautifyPptxAnalysisExecutor {

    private static final String IDENTITY_SCRIPT = "skills/ppt-master/scripts/beautify_identity.py";
    private static final String PROJECT_MANAGER_SCRIPT = "skills/ppt-master/scripts/project_manager.py";
    private static final String INTAKE_SCRIPT = "skills/ppt-master/scripts/pptx_intake.py";
    private static final String INVENTORY_SCRIPT = "skills/ppt-master/scripts/beautify_inventory.py";
    private static final Pattern SAFE_STEM = Pattern.compile("[A-Za-z0-9._-]+");

    private final PptMasterProperties properties;
    private final PptMasterCommandExecutor commands;
    private final PptWorkflowEvents events;
    private final ObjectMapper objectMapper;

    public BeautifyPptxAnalysisExecutor(
            PptMasterProperties properties,
            PptMasterCommandExecutor commands,
            PptWorkflowEvents events) {
        this.properties = properties;
        this.commands = commands;
        this.events = events;
        this.objectMapper = new ObjectMapper();
    }

    /** 执行或从 {@code PROJECT_READY} 恢复 beautify 分析。 */
    public void execute(PptJob job) {
        if (job.workflowMode() != PptWorkflowMode.BEAUTIFY_PPTX) {
            throw new IllegalArgumentException("beautify analysis is only available for BEAUTIFY_PPTX");
        }
        Path workspace = properties.workspacePath().toAbsolutePath().normalize();
        Path source = source(job, workspace);
        if (isCompleted(job, PptJobNode.BEAUTIFY_ANALYZED)) {
            return;
        }
        AnalysisPaths paths;
        if (isCompleted(job, PptJobNode.PROJECT_READY)) {
            paths = existingPaths(job, workspace, source);
        } else {
            paths = initializeAndImport(job, workspace, source);
        }
        ensureIntakeAndInventory(job, paths, source);
    }

    private AnalysisPaths initializeAndImport(PptJob job, Path workspace, Path source) {
        BeautifyPptxProperties limits = properties.beautifyPptx();
        events.record(job, stageEvent(PptJobEventType.NODE_STARTED, "identity", PptJobNode.PROJECT_READY, Map.of()));
        PptMasterCommandExecutor.CommandResult identity = run(
                "identity", IDENTITY_SCRIPT, List.of(source.toString()), limits.identityTimeout(),
                BeautifyPptxErrorCode.BEAUTIFY_IDENTITY_FAILED);
        String resolvedFormat = resolveFormat(identity.output());
        Path project = projectPath(job, workspace, resolvedFormat);
        job.prepareProject(project);
        events.record(job, stageEvent(PptJobEventType.NODE_STARTED, "init", PptJobNode.PROJECT_READY, Map.of()));
        run("init", PROJECT_MANAGER_SCRIPT, List.of(
                "init", job.projectName() + "_" + job.id().toString().substring(0, 8),
                "--format", resolvedFormat, "--dir", project.getParent().toString()), limits.projectTimeout(),
                BeautifyPptxErrorCode.BEAUTIFY_INTAKE_FAILED);
        events.record(job, stageEvent(PptJobEventType.NODE_STARTED, "import", PptJobNode.PROJECT_READY, Map.of()));
        run("import", PROJECT_MANAGER_SCRIPT, List.of("import-sources", project.toString(), source.toString(), "--copy"),
                limits.projectTimeout(), BeautifyPptxErrorCode.BEAUTIFY_INTAKE_FAILED);
        Path analysis = inside(project, project.resolve("analysis"), "analysis");
        requireImportedSource(project);
        String stem = stem(source);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("resolvedFormat", resolvedFormat);
        summary.put("canvas", Map.of("format", resolvedFormat));
        summary.put("sourcesDir", "sources");
        job.completeNode(PptJobNode.PROJECT_READY, summary);
        events.record(job, stageEvent(PptJobEventType.NODE_COMPLETED, "project-preparation", PptJobNode.PROJECT_READY, summary));
        return new AnalysisPaths(project, analysis, stem, resolvedFormat);
    }

    private AnalysisPaths existingPaths(PptJob job, Path workspace, Path source) {
        Path project = job.projectPath().orElseThrow(() -> validation("project path is missing"));
        project = inside(workspace, project, "project");
        String resolvedFormat = stringSummary(job, PptJobNode.PROJECT_READY, "resolvedFormat", job.format());
        return new AnalysisPaths(project, inside(project, project.resolve("analysis"), "analysis"), stem(source), resolvedFormat);
    }

    private void ensureIntakeAndInventory(PptJob job, AnalysisPaths paths, Path source) {
        BeautifyPptxProperties limits = properties.beautifyPptx();
        Path identityPath = paths.analysis().resolve(paths.stem() + ".identity.json");
        Path libraryPath = paths.analysis().resolve(paths.stem() + ".slide_library.json");
        Path profilePath = paths.analysis().resolve("source_profile.json");
        if (!intakeBundleReusable(identityPath, libraryPath, profilePath)) {
            events.record(job, stageEvent(PptJobEventType.NODE_STARTED, "intake", PptJobNode.BEAUTIFY_ANALYZED, Map.of()));
            run("intake", INTAKE_SCRIPT, List.of(source.toString(), "-o", paths.analysis().toString()),
                    limits.intakeTimeout(), BeautifyPptxErrorCode.BEAUTIFY_INTAKE_FAILED);
        }
        requireFile(identityPath, "identity artifact");
        requireFile(libraryPath, "slide library artifact");
        requireFile(profilePath, "source profile artifact");

        Path inventoryPath = paths.analysis().resolve("beautify_inventory.json");
        if (!inventoryReusable(inventoryPath, identityPath, libraryPath)) {
            List<String> args = new ArrayList<>(List.of(libraryPath.toString()));
            Path imageManifest = paths.project().resolve("images/image_manifest.json");
            if (Files.isRegularFile(imageManifest)) {
                args.addAll(List.of("--images", imageManifest.toString()));
            }
            args.addAll(List.of("-o", inventoryPath.toString()));
            events.record(job, stageEvent(PptJobEventType.NODE_STARTED, "inventory", PptJobNode.BEAUTIFY_ANALYZED, Map.of()));
            run("inventory", INVENTORY_SCRIPT, List.copyOf(args), limits.inventoryTimeout(),
                    BeautifyPptxErrorCode.BEAUTIFY_INVENTORY_FAILED);
        }
        requireFile(inventoryPath, "inventory artifact");
        Map<String, Object> summary = validatePureTextArtifacts(identityPath, libraryPath, profilePath, inventoryPath, paths);
        job.completeNode(PptJobNode.BEAUTIFY_ANALYZED, summary);
        events.record(job, stageEvent(PptJobEventType.NODE_COMPLETED, "inventory", PptJobNode.BEAUTIFY_ANALYZED, summary));
    }

    /** 仅在 schema/页数交叉校验通过时复用 intake；损坏产物触发重建，不覆盖只读上传源。 */
    private boolean intakeBundleReusable(Path identityPath, Path libraryPath, Path profilePath) {
        if (!Files.isRegularFile(identityPath) || !Files.isRegularFile(libraryPath) || !Files.isRegularFile(profilePath)) {
            return false;
        }
        try {
            JsonNode identity = objectMapper.readTree(identityPath.toFile());
            JsonNode library = objectMapper.readTree(libraryPath.toFile());
            JsonNode profile = objectMapper.readTree(profilePath.toFile());
            int slideCount = identity.path("slide_count").asInt(0);
            return identity.path("canvas").isObject()
                    && library.path("slides").isArray()
                    && profile.path("decks").isArray()
                    && slideCount > 0
                    && library.path("slide_count").asInt(0) == slideCount
                    && library.path("slides").size() == slideCount;
        } catch (IOException ex) {
            return false;
        }
    }

    /** 仅在 inventory schema 与 intake 页数一致时复用；无效派生产物重建。 */
    private boolean inventoryReusable(Path inventoryPath, Path identityPath, Path libraryPath) {
        if (!Files.isRegularFile(inventoryPath)) {
            return false;
        }
        try {
            JsonNode inventory = objectMapper.readTree(inventoryPath.toFile());
            JsonNode identity = objectMapper.readTree(identityPath.toFile());
            JsonNode library = objectMapper.readTree(libraryPath.toFile());
            int slideCount = identity.path("slide_count").asInt(0);
            return "beautify_inventory.v1".equals(inventory.path("schema").asText())
                    && slideCount > 0
                    && inventory.path("slide_count").asInt(0) == slideCount
                    && library.path("slide_count").asInt(0) == slideCount
                    && inventory.path("slides").isArray()
                    && inventory.path("slides").size() == slideCount;
        } catch (IOException ex) {
            return false;
        }
    }

    private Map<String, Object> validatePureTextArtifacts(
            Path identityPath, Path libraryPath, Path profilePath, Path inventoryPath, AnalysisPaths paths) {
        try {
            JsonNode identity = objectMapper.readTree(identityPath.toFile());
            JsonNode library = objectMapper.readTree(libraryPath.toFile());
            JsonNode profile = objectMapper.readTree(profilePath.toFile());
            JsonNode inventory = objectMapper.readTree(inventoryPath.toFile());
            int slideCount = inventory.path("slide_count").asInt(0);
            if (!identity.path("canvas").isObject() || !library.path("slides").isArray()
                    || !profile.path("decks").isArray() || !"beautify_inventory.v1".equals(inventory.path("schema").asText())
                    || slideCount <= 0 || identity.path("slide_count").asInt(0) != slideCount
                    || library.path("slide_count").asInt(0) != slideCount || library.path("slides").size() != slideCount) {
                throw validation("analysis schema or page-count contract is invalid");
            }
            int alerts = 0;
            for (int index = 0; index < inventory.path("slides").size(); index++) {
                JsonNode slide = inventory.path("slides").get(index);
                if (slide.path("slide_index").asInt(-1) != index + 1 || !slide.path("text_blocks").isArray()) {
                    throw validation("slide index or text ledger is invalid");
                }
                if (hasEntries(slide, "images") || hasEntries(slide, "tables") || hasEntries(slide, "charts")
                        || hasEntries(slide, "unknown_objects") || hasEntries(slide, "unsupported_objects")
                        || hasEntries(slide, "shapes")) {
                    throw unsupported("slide " + (index + 1) + " contains unsupported content");
                }
                for (JsonNode text : slide.path("text_blocks")) {
                    if (!text.isObject() || !text.path("text").isTextual()) {
                        throw validation("text ledger is invalid");
                    }
                }
            }
            if (hasEntries(inventory, "ignored") || hasEntries(inventory, "needs_confirmation")
                    || hasEntries(inventory, "needsConfirmation")) {
                alerts++;
                throw unsupported("inventory requires confirmation");
            }
            Map<String, Object> canvas = new LinkedHashMap<>();
            canvas.put("widthPx", identity.path("canvas").path("width_px").asInt());
            canvas.put("heightPx", identity.path("canvas").path("height_px").asInt());
            canvas.put("aspect", identity.path("canvas").path("aspect").asDouble());
            return Map.of(
                    "sourcePageCount", slideCount,
                    "canvas", Map.copyOf(canvas),
                    "resolvedFormat", paths.resolvedFormat(),
                    "identity", "analysis/" + identityPath.getFileName(),
                    "slideLibrary", "analysis/" + libraryPath.getFileName(),
                    "inventory", "analysis/beautify_inventory.json",
                    "complexObjectAlertCount", alerts);
        } catch (IOException ex) {
            throw new BeautifyPptxAnalysisException("validation", BeautifyPptxErrorCode.BEAUTIFY_VALIDATION_FAILED,
                    BeautifyPptxFailureKind.INTERNAL, "analysis artifacts cannot be read", ex);
        }
    }

    private PptMasterCommandExecutor.CommandResult run(
            String stage, String script, List<String> args, java.time.Duration timeout, BeautifyPptxErrorCode errorCode) {
        PptMasterCommandExecutor.CommandResult result = commands.runPythonScript(script, List.copyOf(args), timeout);
        if (result.successful()) {
            return result;
        }
        BeautifyPptxFailureKind kind = result.timedOut()
                ? BeautifyPptxFailureKind.TIMEOUT : BeautifyPptxFailureKind.PROCESS_EXIT;
        throw new BeautifyPptxAnalysisException(stage, errorCode, kind, stage + " command failed");
    }

    private Path source(PptJob job, Path workspace) {
        if (job.sourceFiles().size() != 1) {
            throw validation("exactly one source is required");
        }
        Path source = inside(workspace, job.sourceFiles().get(0).storedPath(), "source");
        if (!Files.isRegularFile(source)) {
            throw validation("source file is missing");
        }
        return source;
    }

    private Path projectPath(PptJob job, Path workspace, String format) {
        Path projects = workspace.resolve("projects").normalize();
        String slug = job.projectName() + "_" + job.id().toString().substring(0, 8);
        Path project = projects.resolve(slug + "_" + format + "_"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)).toAbsolutePath().normalize();
        return inside(workspace, project, "project");
    }

    private String resolveFormat(String identityOutput) {
        try {
            JsonNode identity = objectMapper.readTree(identityOutput);
            double aspect = identity.path("canvas").path("aspect").asDouble(0);
            if (aspect > 0) {
                return Math.abs(aspect - 1.778D) <= Math.abs(aspect - 1.333D) ? "ppt169" : "ppt43";
            }
        } catch (IOException ignored) {
            // import-sources identity is validated before the analysis checkpoint is completed.
        }
        throw new BeautifyPptxAnalysisException("identity", BeautifyPptxErrorCode.BEAUTIFY_IDENTITY_FAILED,
                BeautifyPptxFailureKind.SCHEMA_INVALID, "identity canvas aspect is missing");
    }

    private String stem(Path source) {
        String fileName = source.getFileName().toString();
        String value = fileName.replaceFirst("(?i)\\.pptx$", "");
        if (!SAFE_STEM.matcher(value).matches()) {
            throw validation("source file stem is unsafe");
        }
        return value;
    }

    private static boolean hasEntries(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isArray() ? !value.isEmpty() : !value.isMissingNode() && !value.isNull() && value.asBoolean(false);
    }

    private static void requireFile(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw validation(label + " is missing");
        }
    }

    private static void requireImportedSource(Path project) {
        Path sources = inside(project, project.resolve("sources"), "sources");
        if (!Files.isDirectory(sources)) {
            throw validation("imported sources directory is missing");
        }
        try (var entries = Files.list(sources)) {
            if (entries.noneMatch(Files::isRegularFile)) {
                throw validation("imported source is missing");
            }
        } catch (IOException ex) {
            throw new BeautifyPptxAnalysisException("import", BeautifyPptxErrorCode.BEAUTIFY_INTAKE_FAILED,
                    BeautifyPptxFailureKind.INTERNAL, "imported source cannot be inspected", ex);
        }
    }

    private static boolean isCompleted(PptJob job, PptJobNode node) {
        return job.nodeExecution(node) != null
                && job.nodeExecution(node).status() == domi.argenticpptmaster.domain.PptJobNodeStatus.COMPLETED;
    }

    private static Path inside(Path root, Path path, String label) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root.toAbsolutePath().normalize())) {
            throw validation(label + " path escapes workspace");
        }
        return normalized;
    }

    private static String stringSummary(PptJob job, PptJobNode node, String key, String fallback) {
        Object value = job.nodeExecution(node).summary().get(key);
        return value instanceof String string && !string.isBlank() ? string : fallback;
    }

    private static BeautifyPptxAnalysisException validation(String message) {
        return new BeautifyPptxAnalysisException("validation", BeautifyPptxErrorCode.BEAUTIFY_VALIDATION_FAILED,
                BeautifyPptxFailureKind.SCHEMA_INVALID, message);
    }

    private static BeautifyPptxAnalysisException unsupported(String message) {
        return new BeautifyPptxAnalysisException("validation", BeautifyPptxErrorCode.BEAUTIFY_UNSUPPORTED_CONTENT,
                BeautifyPptxFailureKind.UNSUPPORTED_CONTENT, message);
    }

    private static PptJobEvent stageEvent(
            PptJobEventType type, String stage, PptJobNode node, Map<String, Object> summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        payload.put("node", node.name());
        payload.put("summary", summary);
        return PptJobEvent.of(type, "beautify analysis " + stage, payload);
    }

    private record AnalysisPaths(Path project, Path analysis, String stem, String resolvedFormat) {
    }
}
