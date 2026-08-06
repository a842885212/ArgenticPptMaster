package domi.argenticpptmaster.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptNodeExecution;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.service.NativeEnhanceConfirmationSummary;
import domi.argenticpptmaster.service.NativeEnhancePlanStore;
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
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Restricted tools for NATIVE_ENHANCE_PPTX: read sources/index/plan; write notes and draft plan. */
@Component
public class NativeEnhanceAgentTools {

    private static final int DEFAULT_READ_CHARS = 8_000;
    private static final int MAX_READ_CHARS = 16_000;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(name = "list_native_enhance_sources", description = "List slide markdown sources under the project sources/ directory.", readOnly = true)
    public Map<String, Object> listSources(PptAgentToolRuntime runtime) {
        requireMode(runtime);
        Path sources = project(runtime).resolve("sources");
        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.isDirectory(sources)) {
            try (Stream<Path> stream = Files.list(sources)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                        .sorted()
                        .forEach(path -> files.add(Map.of(
                                "name", path.getFileName().toString(),
                                "size", fileSize(path))));
            } catch (IOException ex) {
                throw new IllegalStateException("failed to list sources");
            }
        }
        return Map.of("count", files.size(), "sources", files);
    }

    @Tool(name = "read_native_enhance_source_markdown", description = "Read bounded text from a sources/*.md slide markdown file.", readOnly = true)
    public Map<String, Object> readSourceMarkdown(PptAgentToolRuntime runtime, String fileName, Integer maxChars) {
        requireMode(runtime);
        Path path = resolveUnder(project(runtime).resolve("sources"), fileName, ".md");
        String content = readBounded(path, boundedReadLimit(maxChars));
        return Map.of(
                "fileName", path.getFileName().toString(),
                "content", content,
                "truncated", content.endsWith("\n...[truncated]"));
    }

    @Tool(name = "read_native_enhance_slide_index", description = "Read the analysis/slide_index.json artifact.", readOnly = true)
    public Map<String, Object> readSlideIndex(PptAgentToolRuntime runtime) {
        requireMode(runtime);
        Path path = project(runtime).resolve("analysis/slide_index.json");
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("slide_index is missing");
        }
        try {
            JsonNode node = objectMapper.readTree(path.toFile());
            return objectMapper.convertValue(node, Map.class);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read slide_index");
        }
    }

    @Tool(name = "read_native_enhance_plan_draft", description = "Read the draft enhancement plan with version and digest.", readOnly = true)
    public Map<String, Object> readPlanDraft(PptAgentToolRuntime runtime) {
        requireMode(runtime);
        NativeEnhancePlanStore.PlanMetadata meta = NativeEnhancePlanStore.ensureDraftMetadata(runtime.job());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", meta.status());
        result.put("version", meta.version());
        result.put("digest", meta.digest());
        result.put("confirmationContext", NativeEnhanceConfirmationSummary.fromWorkspace(runtime.job(), Map.of()));
        return result;
    }

    @Tool(name = "write_native_enhance_plan_draft", description = "Revise the draft enhancement plan JSON (status remains draft).")
    public Map<String, Object> writePlanDraft(PptAgentToolRuntime runtime, String planJson) {
        requireMode(runtime);
        if (planJson != null && planJson.contains("--force")) {
            throw new IllegalStateException("force execution is not allowed");
        }
        Path planPath = project(runtime).resolve("analysis/enhancement_plan.json");
        try {
            JsonNode parsed = objectMapper.readTree(planJson);
            if (!(parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode plan)) {
                throw new IllegalStateException("plan must be a JSON object");
            }
            plan.put("status", "draft");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(planPath.toFile(), plan);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write enhancement plan draft");
        }
        NativeEnhancePlanStore.PlanMetadata meta = NativeEnhancePlanStore.bumpDraftVersion(runtime.job());
        runtime.completeNode(PptJobNode.ENHANCEMENT_PLAN_DRAFTED, Map.of(
                "enhancementPlanStatus", "draft",
                "version", meta.version(),
                "digest", meta.digest()));
        return Map.of(
                "status", "draft",
                "version", meta.version(),
                "digest", meta.digest(),
                "confirmationContext", NativeEnhanceConfirmationSummary.fromWorkspace(runtime.job(), Map.of()));
    }

    @Tool(name = "write_native_enhance_note", description = "Write a single notes/NNN.md narration file.")
    public Map<String, Object> writeNote(PptAgentToolRuntime runtime, String fileName, String content) {
        requireMode(runtime);
        if (!nodeCompleted(runtime, PptJobNode.ENHANCEMENT_PLAN_CONFIRMED)) {
            throw new IllegalStateException("notes require a confirmed enhancement plan");
        }
        Path notePath = resolveUnder(project(runtime).resolve("notes"), fileName, ".md");
        try {
            Files.createDirectories(notePath.getParent());
            Files.writeString(notePath, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write note");
        }
        return Map.of("fileName", notePath.getFileName().toString(), "stored", true);
    }

    @Tool(name = "inspect_native_enhance_checkpoint_status", description = "Inspect native-enhance checkpoint progress without absolute paths.", readOnly = true)
    public Map<String, Object> inspectCheckpoints(PptAgentToolRuntime runtime) {
        requireMode(runtime);
        Map<String, Object> nodes = new LinkedHashMap<>();
        for (PptJobNode node : PptJobNode.values()) {
            if (!node.applicableTo(runtime.job().workflowMode())) {
                continue;
            }
            PptNodeExecution execution = runtime.job().nodeExecution(node);
            nodes.put(node.name(), execution == null ? "NOT_APPLICABLE" : execution.status().name());
        }
        return Map.of(
                "lastCompletedNode", runtime.job().lastCompletedNode().map(Enum::name).orElse(null),
                "nodeStates", nodes);
    }

    private void requireMode(PptAgentToolRuntime runtime) {
        if (runtime.job().workflowMode() != PptWorkflowMode.NATIVE_ENHANCE_PPTX) {
            throw new IllegalStateException("native-enhance tools require NATIVE_ENHANCE_PPTX");
        }
    }

    private Path project(PptAgentToolRuntime runtime) {
        return runtime.job().projectPath()
                .orElseThrow(() -> new IllegalStateException("project path is not prepared"));
    }

    private Path resolveUnder(Path dir, String fileName, String requiredSuffix) {
        if (fileName == null || fileName.isBlank() || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalStateException("invalid file name");
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(requiredSuffix)) {
            throw new IllegalStateException("file must end with " + requiredSuffix);
        }
        Path path = dir.resolve(fileName).normalize();
        if (!path.startsWith(dir.normalize())) {
            throw new IllegalStateException("path escapes allowed directory");
        }
        return path;
    }

    private int boundedReadLimit(Integer maxChars) {
        if (maxChars == null || maxChars <= 0) {
            return DEFAULT_READ_CHARS;
        }
        return Math.min(maxChars, MAX_READ_CHARS);
    }

    private String readBounded(Path path, int limit) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("file is missing");
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (text.length() <= limit) {
                return text;
            }
            return text.substring(0, limit) + "\n...[truncated]";
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read file");
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return 0L;
        }
    }

    private boolean nodeCompleted(PptAgentToolRuntime runtime, PptJobNode node) {
        PptNodeExecution execution = runtime.job().nodeExecution(node);
        return execution != null && execution.status() == PptJobNodeStatus.COMPLETED;
    }
}
