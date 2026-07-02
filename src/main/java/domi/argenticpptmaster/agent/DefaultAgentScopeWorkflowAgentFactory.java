package domi.argenticpptmaster.agent;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.service.PptWorkflowEvents;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class DefaultAgentScopeWorkflowAgentFactory implements AgentScopeWorkflowAgentFactory {

    private static final String PROJECT_MANAGER_SCRIPT = "skills/ppt-master/scripts/project_manager.py";
    private static final String FINALIZE_SVG_SCRIPT = "skills/ppt-master/scripts/finalize_svg.py";
    private static final String TOTAL_MD_SPLIT_SCRIPT = "skills/ppt-master/scripts/total_md_split.py";
    private static final String SVG_QUALITY_CHECKER_SCRIPT = "skills/ppt-master/scripts/svg_quality_checker.py";
    private static final String EXPORT_SCRIPT = "skills/ppt-master/scripts/svg_to_pptx.py";
    private static final String APPROVAL_TOOL_NAME = "request_plan_confirmation";
    private static final int DEFAULT_FILE_PREVIEW_CHARS = 8000;

    private final AgentScopeProperties agentScopeProperties;
    private final PptMasterProperties pptMasterProperties;
    private final PptMasterCommandExecutor commandExecutor;
    private final PptWorkflowEvents events;

    public DefaultAgentScopeWorkflowAgentFactory(
            AgentScopeProperties agentScopeProperties,
            PptMasterProperties pptMasterProperties,
            PptMasterCommandExecutor commandExecutor,
            PptWorkflowEvents events) {
        this.agentScopeProperties = agentScopeProperties;
        this.pptMasterProperties = pptMasterProperties;
        this.commandExecutor = commandExecutor;
        this.events = events;
    }

    @Override
    public AgentScopeWorkflowAgent create(PptJob job) {
        ensureAgentPrerequisites();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PptAgentTools());
        toolkit.registerTool(new UserPlanApprovalTool());

        ReActAgent agent = ReActAgent.builder()
                .name("ppt_master_orchestrator")
                .description("Orchestrates the markdown-first ppt-master workflow: import, planning, authoring, validation, and export.")
                .sysPrompt(buildSystemPrompt())
                .model(buildModel())
                .toolkit(toolkit)
                .stateStore(new JsonFileAgentStateStore(agentScopeProperties.sessionStorePath()))
                .defaultSessionId(job.id().toString())
                .maxIters(agentScopeProperties.maxIters())
                .build();

        PptAgentToolRuntime toolRuntime = new PptAgentToolRuntime(job, pptMasterProperties, commandExecutor, events);
        return (messages, runtimeContext) -> {
            RuntimeContext effectiveContext = runtimeContext == null ? RuntimeContext.empty() : runtimeContext;
            effectiveContext.put(PptAgentToolRuntime.class, toolRuntime);
            return agent.streamEvents(messages, effectiveContext);
        };
    }

    private void ensureAgentPrerequisites() {
        if (!Files.exists(pptMasterProperties.repoPath().resolve(PROJECT_MANAGER_SCRIPT))) {
            throw new IllegalStateException("ppt-master project manager not found under " + pptMasterProperties.repoPath());
        }
        if (!Files.exists(pptMasterProperties.repoPath().resolve(FINALIZE_SVG_SCRIPT))) {
            throw new IllegalStateException("ppt-master finalize_svg not found under " + pptMasterProperties.repoPath());
        }
        if (!Files.exists(pptMasterProperties.repoPath().resolve(TOTAL_MD_SPLIT_SCRIPT))) {
            throw new IllegalStateException("ppt-master total_md_split not found under " + pptMasterProperties.repoPath());
        }
        if (!Files.exists(pptMasterProperties.repoPath().resolve(SVG_QUALITY_CHECKER_SCRIPT))) {
            throw new IllegalStateException("ppt-master svg_quality_checker not found under " + pptMasterProperties.repoPath());
        }
        if (agentScopeProperties.modelName() == null || agentScopeProperties.modelName().isBlank()) {
            throw new IllegalStateException("agentscope.model-name must be configured");
        }
    }

    private Model buildModel() {
        String provider = agentScopeProperties.provider().trim().toLowerCase();
        return switch (provider) {
            case "dashscope" -> buildDashScopeModel();
            case "ollama" -> buildOllamaModel();
            case "openai" -> buildOpenAiModel();
            default -> throw new IllegalStateException("unsupported agentscope.provider: " + provider);
        };
    }

    private Model buildOpenAiModel() {
        OpenAIChatModel.Builder builder = new OpenAIChatModel.Builder()
                .modelName(agentScopeProperties.modelName())
                .stream(true);
        if (agentScopeProperties.apiKey() != null && !agentScopeProperties.apiKey().isBlank()) {
            builder.apiKey(agentScopeProperties.apiKey());
        }
        if (agentScopeProperties.baseUrl() != null && !agentScopeProperties.baseUrl().isBlank()) {
            builder.baseUrl(agentScopeProperties.baseUrl());
        }
        return builder.build();
    }

    private Model buildDashScopeModel() {
        DashScopeChatModel.Builder builder = new DashScopeChatModel.Builder()
                .modelName(agentScopeProperties.modelName())
                .stream(true);
        if (agentScopeProperties.apiKey() != null && !agentScopeProperties.apiKey().isBlank()) {
            builder.apiKey(agentScopeProperties.apiKey());
        }
        if (agentScopeProperties.baseUrl() != null && !agentScopeProperties.baseUrl().isBlank()) {
            builder.baseUrl(agentScopeProperties.baseUrl());
        }
        return builder.build();
    }

    private Model buildOllamaModel() {
        OllamaChatModel.Builder builder = new OllamaChatModel.Builder()
                .modelName(agentScopeProperties.modelName());
        if (agentScopeProperties.baseUrl() != null && !agentScopeProperties.baseUrl().isBlank()) {
            builder.baseUrl(agentScopeProperties.baseUrl());
        }
        return builder.build();
    }

    private String buildSystemPrompt() {
        return """
                你是 ppt-master 的编排代理，当前只负责 markdown 路线：把上传文件转成 markdown 上下文，再产出 design_spec/spec_lock/notes/svg_output，最后完成后处理和导出。
                
                严格遵守：
                1. 先调用 describe_job 了解任务，再调用 init_ppt_project，然后调用 import_job_sources。
                2. 导入完成后，优先调用 collect_source_markdown、inspect_project_info、list_project_files、read_project_text_file 了解 sources/ 与 analysis/ 的真实内容。
                3. 在写 design_spec.md、spec_lock.md、notes/total.md、svg_output/*.svg 之前，必须调用 request_plan_confirmation，请求用户确认页数、结构、风险和关键假设。
                4. 用户确认恢复后，按 markdown 路线产出这些文件：
                   - design_spec.md
                   - spec_lock.md
                   - notes/total.md
                   - svg_output/*.svg
                5. 生成 svg_output 后，先调用 validate_svg_output，再调用 split_speaker_notes、finalize_project_svg，最后调用 export_project_pptx。
                6. 如果缺少导出前置条件，要清楚说明缺了什么，不要伪造“已完成”。
                7. 只基于工具返回的信息作答，不要假设本地文件内容。
                8. 后续可能支持 pptx 模板路线，但当前轮次不要走 template_fill_pptx。
                """;
    }

    record PptAgentToolRuntime(
            PptJob job,
            PptMasterProperties properties,
            PptMasterCommandExecutor executor,
            PptWorkflowEvents events) {
    }

    final class PptAgentTools {

        @Tool(name = "describe_job", description = "Return the current PPT job metadata and uploaded source files.", readOnly = true)
        public Map<String, Object> describeJob(PptAgentToolRuntime runtime) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("jobId", runtime.job().id().toString());
            data.put("projectName", runtime.job().projectName());
            data.put("format", runtime.job().format());
            data.put("instruction", runtime.job().instruction() == null ? "" : runtime.job().instruction());
            data.put("workspacePath", runtime.job().workspacePath().toString());
            data.put("projectPath", runtime.job().projectPath().map(Path::toString).orElse(""));
            data.put("sourceFiles", runtime.job().sourceFiles().stream()
                    .map(file -> Map.of(
                            "name", file.originalName(),
                            "contentType", Objects.toString(file.contentType(), ""),
                            "size", file.size(),
                            "path", file.storedPath().toString()))
                    .toList());
            return data;
        }

        @Tool(name = "init_ppt_project", description = "Initialize the local ppt-master project directory for the current job.")
        public String initPptProject(PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            if (Files.exists(projectPath.resolve("README.md"))) {
                return "project already initialized at " + projectPath;
            }
            runtime.events().record(runtime.job(), PptJobEvent.of(
                    PptJobEventType.AGENT_MESSAGE,
                    "initializing ppt-master project",
                    Map.of("projectName", projectPath.getFileName().toString())));
            PptMasterCommandExecutor.CommandResult result = runtime.executor().runPythonScript(
                    PROJECT_MANAGER_SCRIPT,
                    List.of(
                            "init",
                            runtime.job().projectName() + "_" + runtime.job().id().toString().substring(0, 8),
                            "--format",
                            runtime.job().format(),
                            "--dir",
                            projectPath.getParent().toString()));
            if (!result.successful()) {
                throw new IllegalStateException("project init failed: " + result.output());
            }
            return result.output().isBlank() ? "project initialized: " + projectPath : result.output();
        }

        @Tool(name = "import_job_sources", description = "Import uploaded source files into the prepared ppt-master project.")
        public String importJobSources(PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            Path sourcesDir = projectPath.resolve("sources");
            try (Stream<Path> sourceStream = Files.isDirectory(sourcesDir) ? Files.list(sourcesDir) : Stream.empty()) {
                if (sourceStream.findAny().isPresent()) {
                    return "sources already imported under " + sourcesDir;
                }
            } catch (IOException ex) {
                throw new IllegalStateException("failed to inspect sources dir", ex);
            }
            List<String> arguments = new ArrayList<>();
            arguments.add("import-sources");
            arguments.add(projectPath.toString());
            runtime.job().sourceFiles().forEach(source -> arguments.add(source.storedPath().toString()));
            arguments.add("--copy");
            runtime.events().record(runtime.job(), PptJobEvent.of(
                    PptJobEventType.AGENT_MESSAGE,
                    "importing uploaded files into ppt-master project",
                    Map.of("sourceCount", runtime.job().sourceFiles().size())));
            PptMasterCommandExecutor.CommandResult result = runtime.executor().runPythonScript(PROJECT_MANAGER_SCRIPT, arguments);
            if (!result.successful()) {
                throw new IllegalStateException("source import failed: " + result.output());
            }
            return result.output().isBlank() ? "sources imported into " + sourcesDir : result.output();
        }

        @Tool(name = "inspect_project_info", description = "Run ppt-master project info to inspect the current workspace state.", readOnly = true)
        public String inspectProjectInfo(PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            PptMasterCommandExecutor.CommandResult result = runtime.executor().runPythonScript(
                    PROJECT_MANAGER_SCRIPT,
                    List.of("info", projectPath.toString()));
            if (!result.successful()) {
                throw new IllegalStateException("project info failed: " + result.output());
            }
            return result.output();
        }

        @Tool(name = "validate_project", description = "Validate the ppt-master project structure and exported assets.", readOnly = true)
        public String validateProject(PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            PptMasterCommandExecutor.CommandResult result = runtime.executor().runPythonScript(
                    PROJECT_MANAGER_SCRIPT,
                    List.of("validate", projectPath.toString()));
            if (!result.successful()) {
                throw new IllegalStateException("project validation failed: " + result.output());
            }
            return result.output();
        }

        @Tool(name = "list_project_files", description = "List key files inside the prepared project, grouped by important directories.", readOnly = true)
        public Map<String, Object> listProjectFiles(PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("projectRoot", projectPath.toString());
            result.put("sources", listRelativeFiles(projectPath, "sources"));
            result.put("analysis", listRelativeFiles(projectPath, "analysis"));
            result.put("notes", listRelativeFiles(projectPath, "notes"));
            result.put("svgOutput", listRelativeFiles(projectPath, "svg_output"));
            result.put("svgFinal", listRelativeFiles(projectPath, "svg_final"));
            result.put("exports", listRelativeFiles(projectPath, "exports"));
            return result;
        }

        @Tool(name = "collect_source_markdown", description = "Read normalized markdown source files from sources/ for the markdown generation route.", readOnly = true)
        public Map<String, Object> collectSourceMarkdown(
                @ToolParam(name = "maxFiles", description = "Maximum number of markdown files to include.", required = false)
                Integer maxFiles,
                @ToolParam(name = "maxCharsPerFile", description = "Maximum characters returned per markdown file.", required = false)
                Integer maxCharsPerFile,
                PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            Path sourcesDir = projectPath.resolve("sources");
            int fileLimit = maxFiles == null || maxFiles < 1 ? 20 : maxFiles;
            int charLimit = maxCharsPerFile == null || maxCharsPerFile < 1 ? DEFAULT_FILE_PREVIEW_CHARS : maxCharsPerFile;
            List<Map<String, Object>> files = new ArrayList<>();
            try (Stream<Path> stream = Files.list(sourcesDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase();
                            return name.endsWith(".md") || name.endsWith(".markdown");
                        })
                        .sorted()
                        .limit(fileLimit)
                        .forEach(path -> {
                            String text = readText(path);
                            files.add(Map.of(
                                    "relativePath", projectPath.relativize(path).toString(),
                                    "charCount", text.length(),
                                    "content", truncate(text, charLimit)));
                        });
            } catch (IOException ex) {
                throw new IllegalStateException("failed to collect markdown sources", ex);
            }
            return Map.of(
                    "projectRoot", projectPath.toString(),
                    "markdownFiles", files,
                    "count", files.size());
        }

        @Tool(name = "read_project_text_file", description = "Read a text file from the prepared project for source inspection.", readOnly = true)
        public String readProjectTextFile(
                @ToolParam(name = "relativePath", description = "Project-relative file path to read.", required = true)
                String relativePath,
                @ToolParam(name = "maxChars", description = "Maximum number of UTF-8 characters to return.", required = false)
                Integer maxChars,
                PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            Path target = projectPath.resolve(relativePath).normalize();
            if (!target.startsWith(projectPath)) {
                throw new IllegalStateException("relativePath escapes project: " + relativePath);
            }
            if (!Files.isRegularFile(target)) {
                throw new IllegalStateException("project file not found: " + relativePath);
            }
            try {
                String text = Files.readString(target, StandardCharsets.UTF_8);
                int limit = maxChars == null || maxChars < 1 ? DEFAULT_FILE_PREVIEW_CHARS : maxChars;
                if (text.length() <= limit) {
                    return text;
                }
                return text.substring(0, limit) + "\n...[truncated]";
            } catch (IOException ex) {
                throw new IllegalStateException("failed to read project file: " + relativePath, ex);
            }
        }

        @Tool(name = "write_project_text_file", description = "Write or overwrite a project text file such as design_spec.md, spec_lock.md, notes/total.md, or svg_output/*.svg.")
        public Map<String, Object> writeProjectTextFile(
                @ToolParam(name = "relativePath", description = "Project-relative path to write.", required = true)
                String relativePath,
                @ToolParam(name = "content", description = "UTF-8 text content to write.", required = true)
                String content,
                PptAgentToolRuntime runtime) {
            Path target = resolveWritableProjectFile(runtime, relativePath);
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, content, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("failed to write project file: " + relativePath, ex);
            }
            return Map.of(
                    "relativePath", runtime.job().projectPath().orElseThrow().relativize(target).toString(),
                    "charCount", content.length(),
                    "bytes", content.getBytes(StandardCharsets.UTF_8).length);
        }

        @Tool(name = "split_speaker_notes", description = "Split notes/total.md into per-slide notes files under notes/ using ppt-master total_md_split.py.")
        public String splitSpeakerNotes(PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            PptMasterCommandExecutor.CommandResult result = runtime.executor().runPythonScript(
                    TOTAL_MD_SPLIT_SCRIPT,
                    List.of(projectPath.toString()));
            if (!result.successful()) {
                throw new IllegalStateException("speaker notes split failed: " + result.output());
            }
            return result.output().isBlank() ? "speaker notes split completed" : result.output();
        }

        @Tool(name = "validate_svg_output", description = "Run ppt-master svg_quality_checker.py against the current project before finalize/export.", readOnly = true)
        public String validateSvgOutput(PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            PptMasterCommandExecutor.CommandResult result = runtime.executor().runPythonScript(
                    SVG_QUALITY_CHECKER_SCRIPT,
                    List.of(projectPath.toString(), "--format", runtime.job().format()));
            if (!result.successful()) {
                throw new IllegalStateException("svg quality validation failed: " + result.output());
            }
            return result.output().isBlank() ? "svg quality validation passed" : result.output();
        }

        @Tool(name = "finalize_project_svg", description = "Run ppt-master finalize_svg.py to build svg_final from svg_output.")
        public String finalizeProjectSvg(PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            PptMasterCommandExecutor.CommandResult result = runtime.executor().runPythonScript(
                    FINALIZE_SVG_SCRIPT,
                    List.of(projectPath.toString()));
            if (!result.successful()) {
                throw new IllegalStateException("svg finalize failed: " + result.output());
            }
            return result.output().isBlank() ? "svg finalization completed" : result.output();
        }

        @Tool(name = "export_project_pptx", description = "Export the prepared svg_final project into a PPTX artifact.")
        public String exportProjectPptx(PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            Path svgFinal = projectPath.resolve("svg_final");
            if (!Files.isDirectory(svgFinal) || listRelativeFiles(projectPath, "svg_final").isEmpty()) {
                throw new IllegalStateException("svg_final is empty; cannot export PPTX");
            }
            runtime.job().startExport();
            runtime.events().record(runtime.job(), PptJobEvent.of(
                    PptJobEventType.AGENT_MESSAGE,
                    "exporting pptx from svg_final",
                    Map.of("projectName", projectPath.getFileName().toString())));
            PptMasterCommandExecutor.CommandResult result = runtime.executor().runPythonScript(
                    EXPORT_SCRIPT,
                    List.of(projectPath.toString()));
            if (!result.successful()) {
                throw new IllegalStateException("ppt export failed: " + result.output());
            }
            Path artifact = detectLatestExport(projectPath);
            runtime.job().complete(artifact);
            runtime.events().record(runtime.job(), PptJobEvent.of(
                    PptJobEventType.EXPORT_READY,
                    "ppt artifact exported",
                    Map.of("fileName", artifact.getFileName().toString())));
            return result.output().isBlank() ? "ppt exported: " + artifact.getFileName() : result.output();
        }

        private List<String> listRelativeFiles(Path projectPath, String directoryName) {
            Path directory = projectPath.resolve(directoryName);
            if (!Files.isDirectory(directory)) {
                return List.of();
            }
            try (Stream<Path> stream = Files.walk(directory)) {
                return stream
                        .filter(Files::isRegularFile)
                        .sorted()
                        .map(path -> projectPath.relativize(path).toString())
                        .toList();
            } catch (IOException ex) {
                throw new IllegalStateException("failed to list " + directoryName, ex);
            }
        }

        private Path resolveWritableProjectFile(PptAgentToolRuntime runtime, String relativePath) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            Path target = projectPath.resolve(relativePath).normalize();
            if (!target.startsWith(projectPath)) {
                throw new IllegalStateException("relativePath escapes project: " + relativePath);
            }
            String normalized = projectPath.relativize(target).toString().replace('\\', '/');
            boolean allowed = normalized.equals("design_spec.md")
                    || normalized.equals("spec_lock.md")
                    || normalized.startsWith("notes/")
                    || normalized.startsWith("svg_output/")
                    || normalized.startsWith("analysis/");
            if (!allowed) {
                throw new IllegalStateException("writing this project path is not allowed: " + relativePath);
            }
            return target;
        }

        private String readText(Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("failed to read text file: " + path, ex);
            }
        }

        private String truncate(String text, int limit) {
            if (text.length() <= limit) {
                return text;
            }
            return text.substring(0, limit) + "\n...[truncated]";
        }

        private Path detectLatestExport(Path projectPath) {
            Path exportDir = projectPath.resolve("exports");
            if (!Files.isDirectory(exportDir)) {
                throw new IllegalStateException("exports directory not found after export");
            }
            try (Stream<Path> stream = Files.list(exportDir)) {
                return stream
                        .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".pptx"))
                        .max(Comparator.comparing(path -> {
                            try {
                                return Files.getLastModifiedTime(path).toInstant();
                            } catch (IOException ex) {
                                return Instant.EPOCH;
                            }
                        }))
                        .orElseThrow(() -> new IllegalStateException("no pptx artifact found in exports"));
            } catch (IOException ex) {
                throw new IllegalStateException("failed to detect exported pptx", ex);
            }
        }
    }

    static final class UserPlanApprovalTool extends ToolBase {

        UserPlanApprovalTool() {
            super(ToolBase.builder()
                    .name(APPROVAL_TOOL_NAME)
                    .description("Request operator confirmation for the proposed PPT execution plan.")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "planSummary", Map.of("type", "string"),
                                    "sourceFindings", Map.of("type", "string"),
                                    "pendingSteps", Map.of("type", "string"),
                                    "risks", Map.of("type", "string")),
                            "required", List.of("planSummary", "pendingSteps")))
                    .readOnly(false)
                    .concurrencySafe(true)
                    .externalTool(true));
        }

    }
}
