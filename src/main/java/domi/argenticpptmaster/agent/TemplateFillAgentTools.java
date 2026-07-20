package domi.argenticpptmaster.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptNodeExecution;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.domain.TemplateFillAnalysisSummary;
import domi.argenticpptmaster.domain.TemplateFillPlanMetadata;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.service.PptTemplateFillAnalysisReader;
import domi.argenticpptmaster.service.PptTemplateFillPlanStore;
import domi.argenticpptmaster.service.TemplateFillPlanValidator;
import io.agentscope.core.tool.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 模板填充专用 Agent 语义工具集。 */
@Component
public class TemplateFillAgentTools {

    private static final Set<String> CONTENT_EXTENSIONS = Set.of(
            ".md", ".markdown", ".txt", ".csv", ".json", ".docx", ".pptx", ".xlsx");
    private static final int DEFAULT_READ_CHARS = 8_000;
    private static final int MAX_READ_CHARS = 16_000;
    private static final int MAX_TOTAL_READ_CHARS = 64_000;
    private static final int DEFAULT_PAGE_SIZE = 5;

    private final PptTemplateFillPlanStore planStore;
    private final PptTemplateFillAnalysisReader analysisReader;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadLocal<Integer> totalReadChars = ThreadLocal.withInitial(() -> 0);

    public TemplateFillAgentTools(
            PptTemplateFillPlanStore planStore,
            PptTemplateFillAnalysisReader analysisReader) {
        this.planStore = planStore;
        this.analysisReader = analysisReader;
    }

    @Tool(name = "list_template_fill_content_sources", description = "List allowed content source files for the current template-fill job.", readOnly = true)
    public Map<String, Object> listContentSources(PptAgentToolRuntime runtime) {
        List<Map<String, Object>> sources = runtime.job().sourceFiles().stream()
                .filter(this::isAllowedContent)
                .map(this::sourceSummary)
                .toList();
        return Map.of("count", sources.size(), "sources", sources);
    }

    @Tool(name = "read_template_fill_content_source", description = "Read bounded text from an allowed content source using a stable sourceRef.", readOnly = true)
    public Map<String, Object> readContentSource(PptAgentToolRuntime runtime, String sourceRef, Integer maxChars) {
        int limit = boundedReadLimit(maxChars);
        PptSourceFile source = resolveSource(runtime, sourceRef);
        Path path = source.storedPath().toAbsolutePath().normalize();
        rejectEscape(runtime.job().workspacePath(), path);
        rejectSymlink(path);
        String content = readBounded(path, limit);
        totalReadChars.set(totalReadChars.get() + content.length());
        if (totalReadChars.get() > MAX_TOTAL_READ_CHARS) {
            throw new IllegalStateException("content read budget exceeded");
        }
        return Map.of(
                "sourceRef", sourceRef,
                "originalName", source.originalName(),
                "content", content,
                "truncated", content.endsWith("\n...[truncated]"));
    }

    @Tool(name = "read_template_slide_library_page", description = "Read a page of template slide library entries with slot/table/chart summaries.", readOnly = true)
    public Map<String, Object> readSlideLibraryPage(PptAgentToolRuntime runtime, Integer page, Integer pageSize) {
        Path slideLibrary = slideLibraryPath(runtime);
        JsonNode root = readJson(slideLibrary);
        JsonNode slides = root.get("slides");
        int effectivePage = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, DEFAULT_PAGE_SIZE);
        List<Map<String, Object>> items = new ArrayList<>();
        if (slides != null && slides.isArray()) {
            int start = (effectivePage - 1) * size;
            int end = Math.min(start + size, slides.size());
            for (int index = start; index < end; index++) {
                JsonNode slide = slides.get(index);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("slideIndex", slide.path("slide_index").asInt());
                item.put("textSlots", summarizeArray(slide.get("slots"), "slot_id", "text"));
                item.put("tables", summarizeArray(slide.get("tables"), "table_id", null));
                item.put("charts", summarizeArray(slide.get("charts"), "chart_id", null));
                items.add(item);
            }
        }
        int total = slides == null ? 0 : slides.size();
        return Map.of(
                "page", effectivePage,
                "pageSize", size,
                "totalSlides", total,
                "slides", items);
    }

    @Tool(name = "inspect_template_fill_checkpoint_status", description = "Inspect template-fill checkpoint progress without exposing absolute paths.", readOnly = true)
    public Map<String, Object> inspectCheckpointStatus(PptAgentToolRuntime runtime) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        for (PptJobNode node : PptJobNode.values()) {
            if (!node.applicableTo(runtime.job().workflowMode())) {
                continue;
            }
            PptNodeExecution execution = runtime.job().nodeExecution(node);
            nodes.put(node.name(), execution == null ? "NOT_APPLICABLE" : execution.status().name());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lastCompletedNode", runtime.job().lastCompletedNode().map(Enum::name).orElse(null));
        result.put("fillPlanStatus", runtime.job().fillPlanStatus().value());
        result.put("templateAnalysisReady", runtime.job().templateAnalysisReady());
        runtime.job().templateAnalysisSummary().ifPresent(summary -> {
            result.put("templateSlideCount", summary.templateSlideCount());
            result.put("textSlotCount", summary.textSlotCount());
            result.put("tableCount", summary.tableCount());
            result.put("chartCount", summary.chartCount());
        });
        result.put("nodeStates", nodes);
        return result;
    }

    @Tool(name = "write_template_fill_plan_draft", description = "Validate and atomically store a draft fill plan for operator confirmation.")
    public Map<String, Object> writePlanDraft(PptAgentToolRuntime runtime, String planJson) {
        if (planJson != null && planJson.contains("--force")) {
            throw new IllegalStateException("force execution is not allowed");
        }
        Path slideLibrary = slideLibraryPath(runtime);
        TemplateFillPlanMetadata metadata = planStore.storeDraftPlan(runtime.job(), planJson, slideLibrary);
        runtime.completeNode(PptJobNode.FILL_PLAN_DRAFTED, Map.of(
                "planVersion", metadata.version(),
                "planDigest", metadata.digest(),
                "planSlideCount", runtime.job().planSlideCount()));
        return Map.of(
                "status", "draft",
                "version", metadata.version(),
                "digest", metadata.digest(),
                "planSlideCount", runtime.job().planSlideCount());
    }

    @Tool(name = "write_template_fill_plan_rationale", description = "Store a bounded markdown rationale for the current fill plan draft.")
    public Map<String, Object> writePlanRationale(PptAgentToolRuntime runtime, String rationaleMarkdown) {
        planStore.storeRationale(runtime.job(), rationaleMarkdown);
        return Map.of("stored", true);
    }

    private Path slideLibraryPath(PptAgentToolRuntime runtime) {
        Path project = runtime.job().projectPath()
                .orElseThrow(() -> new IllegalStateException("project path is not prepared"));
        Path slideLibrary = project.resolve("analysis/template.slide_library.json").normalize();
        rejectEscape(runtime.job().workspacePath(), slideLibrary);
        if (!Files.isRegularFile(slideLibrary)) {
            throw new IllegalStateException("slide library is missing; template analysis must complete first");
        }
        return slideLibrary;
    }

    private JsonNode readJson(Path path) {
        try {
            return objectMapper.readTree(path.toFile());
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read JSON artifact");
        }
    }

    private List<Map<String, Object>> summarizeArray(JsonNode array, String idField, String previewField) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return items;
        }
        for (JsonNode node : array) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", node.path(idField).asText());
            if (previewField != null && node.has(previewField)) {
                String preview = node.get(previewField).asText("");
                item.put("preview", truncate(preview, TemplateFillPlanValidator.MAX_PREVIEW_CHARS));
            }
            items.add(item);
        }
        return items;
    }

    private Map<String, Object> sourceSummary(PptSourceFile source) {
        return Map.of(
                "sourceRef", sourceRef(source),
                "originalName", source.originalName(),
                "size", source.size());
    }

    private String sourceRef(PptSourceFile source) {
        return "content:" + source.originalName();
    }

    private PptSourceFile resolveSource(PptAgentToolRuntime runtime, String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            throw new IllegalStateException("sourceRef is required");
        }
        PptSourceFile source = runtime.job().sourceFiles().stream()
                .filter(item -> sourceRef.equals(sourceRef(item)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown content sourceRef"));
        if (!isAllowedContent(source)) {
            throw new IllegalStateException("content source type is not allowed");
        }
        return source;
    }

    private boolean isAllowedContent(PptSourceFile source) {
        String name = source.originalName().toLowerCase(Locale.ROOT);
        return CONTENT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private int boundedReadLimit(Integer maxChars) {
        if (maxChars == null || maxChars <= 0) {
            return DEFAULT_READ_CHARS;
        }
        return Math.min(maxChars, MAX_READ_CHARS);
    }

    private String readBounded(Path path, int limit) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return truncate(content, limit);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read content source");
        }
    }

    private static void rejectEscape(Path workspace, Path target) {
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(normalizedWorkspace)) {
            throw new IllegalStateException("path escapes job workspace");
        }
    }

    private static void rejectSymlink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalStateException("symbolic links are not allowed");
        }
    }

    private static String truncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "\n...[truncated]";
    }
}
