package domi.argenticpptmaster.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptNodeExecution;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.domain.TemplateFillCapabilityIndex;
import domi.argenticpptmaster.domain.TemplateFillPlanMetadata;
import domi.argenticpptmaster.service.PptTemplateFillAnalysisReader;
import domi.argenticpptmaster.service.PptTemplateFillPlanStore;
import domi.argenticpptmaster.service.TemplateFillCapabilityIndexLoader;
import domi.argenticpptmaster.service.TemplateFillConfirmationSummary;
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

    @Tool(name = "read_template_slide_library_page", description = "Read a page of template slide library entries with slot/table/chart/notes/transition capability summaries.", readOnly = true)
    public Map<String, Object> readSlideLibraryPage(PptAgentToolRuntime runtime, Integer page, Integer pageSize) {
        Path slideLibrary = slideLibraryPath(runtime);
        TemplateFillCapabilityIndex index = new TemplateFillCapabilityIndexLoader().load(slideLibrary);
        int effectivePage = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, DEFAULT_PAGE_SIZE);
        List<Integer> slideIndexes = new ArrayList<>(index.slidesByIndex().keySet());
        slideIndexes.sort(Integer::compareTo);
        int start = (effectivePage - 1) * size;
        int end = Math.min(start + size, slideIndexes.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = start; i < end; i++) {
            TemplateFillCapabilityIndex.SlideCapability slide = index.slidesByIndex().get(slideIndexes.get(i));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("slideIndex", slide.slideIndex());
            item.put("pageType", slide.pageType());
            item.put("textSlots", slide.textSlots().values().stream().map(slot -> {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("id", slot.slotId());
                summary.put("role", slot.role());
                summary.put("fontSizePx", slot.fontSizePx());
                summary.put("fontAdjustable", slot.fontAdjustable());
                return summary;
            }).toList());
            item.put("tables", slide.tables().values().stream().map(table -> Map.of(
                    "id", table.tableId(),
                    "rowCount", table.rowCount(),
                    "columnCount", table.columnCount())).toList());
            item.put("charts", slide.charts().values().stream().map(chart -> {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("id", chart.chartId());
                summary.put("chartType", chart.chartType() == null ? "" : chart.chartType());
                summary.put("categoryCount", chart.categoryCount());
                summary.put("seriesCount", chart.seriesCount());
                return summary;
            }).toList());
            item.put("notesSupported", true);
            item.put("defaultTransition", "fade");
            item.put("supportedTransitions", List.of(
                    "fade", "push", "wipe", "split", "strips", "cover", "random", "none", "keep"));
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", effectivePage);
        result.put("pageSize", size);
        result.put("totalSlides", index.slideCount());
        result.put("schemaVersion", index.schemaVersion());
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("allowedTemplateSlides", runtime.job().templateConstraints().allowedTemplateSlides());
        constraints.put("excludedTemplateSlides", runtime.job().templateConstraints().excludedTemplateSlides());
        constraints.put("preserveCover", runtime.job().templateConstraints().preserveCover());
        constraints.put("preserveEnding", runtime.job().templateConstraints().preserveEnding());
        constraints.put("maxSlides", runtime.job().templateConstraints().maxSlides());
        result.put("constraints", constraints);
        result.put("slides", items);
        return result;
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

    /** Convenience overload for callers that only provide the upstream plan JSON. */
    public Map<String, Object> writePlanDraft(PptAgentToolRuntime runtime, String planJson) {
        return writePlanDraft(runtime, planJson, null);
    }

    @Tool(name = "write_template_fill_plan_draft", description = "Validate and atomically store a draft fill plan (template_fill_pptx_plan.v1) plus optional service meta for operator confirmation.")
    public Map<String, Object> writePlanDraft(PptAgentToolRuntime runtime, String planJson, String serviceMetaJson) {
        if (planJson != null && planJson.contains("--force")) {
            throw new IllegalStateException("force execution is not allowed");
        }
        if (serviceMetaJson != null && serviceMetaJson.contains("--force")) {
            throw new IllegalStateException("force execution is not allowed");
        }
        Path slideLibrary = slideLibraryPath(runtime);
        TemplateFillPlanMetadata metadata = planStore.storeDraftPlan(
                runtime.job(), planJson, slideLibrary, serviceMetaJson);
        runtime.completeNode(PptJobNode.FILL_PLAN_DRAFTED, Map.of(
                "planVersion", metadata.version(),
                "planDigest", metadata.digest(),
                "planSlideCount", runtime.job().planSlideCount()));
        Map<String, Object> confirmationContext = TemplateFillConfirmationSummary.fromWorkspace(
                runtime.job(), Map.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "draft");
        result.put("version", metadata.version());
        result.put("digest", metadata.digest());
        result.put("planSlideCount", runtime.job().planSlideCount());
        result.put("notesMappingCount", runtime.job().notesMappingCount());
        result.put("tableMappingCount", runtime.job().tableMappingCount());
        result.put("chartMappingCount", runtime.job().chartMappingCount());
        result.put("confirmationContext", confirmationContext);
        return result;
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
