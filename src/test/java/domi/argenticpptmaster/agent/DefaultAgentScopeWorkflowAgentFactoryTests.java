package domi.argenticpptmaster.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.service.PptJobEventPublisher;
import domi.argenticpptmaster.service.PptWorkflowEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DefaultAgentScopeWorkflowAgentFactory} 及其内部工具类的单元测试。
 * <p>
 * 验证 Markdown 源文件收集、项目文本文件写入权限控制，
 * 以及后处理脚本调用的正确性。
 */
class DefaultAgentScopeWorkflowAgentFactoryTests {

    @TempDir
    Path tempDir;

    /**
     * 验证 {@code collectSourceMarkdown} 工具方法仅收集并返回
     * {@code .md} 和 {@code .markdown} 后缀的 Markdown 文件，
     * 过滤掉非 Markdown 文件（如 PDF），并返回文件相对路径与正文内容。
     */
    @Test
    void collectSourceMarkdownReadsNormalizedMarkdownFilesOnly() throws IOException {
        TestContext context = createContext();
        Files.writeString(context.projectPath.resolve("sources/alpha.md"), "# Alpha\nA");
        Files.writeString(context.projectPath.resolve("sources/beta.markdown"), "# Beta\nB");
        Files.writeString(context.projectPath.resolve("sources/raw.pdf"), "binary");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = context.tools.collectSourceMarkdown(null, 100, context.runtime);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> markdownFiles = (List<Map<String, Object>>) result.get("markdownFiles");

        assertThat(result).containsEntry("count", 2);
        assertThat(markdownFiles).extracting(file -> file.get("relativePath"))
                .containsExactly("sources/alpha.md", "sources/beta.markdown");
        assertThat(markdownFiles).extracting(file -> file.get("content"))
                .allSatisfy(content -> assertThat((String) content).startsWith("# "));
    }

    /**
     * 验证 {@code writeProjectTextFile} 工具方法允许向允许路径（如 notes/）
     * 写入文本文件，拒绝向不允许路径（如 exports/ 下的 pptx 文件）写入，
     * 并抛出 {@link IllegalStateException} 异常。
     */
    @Test
    void writeProjectTextFileWritesAllowedPathsAndRejectsOthers() throws IOException {
        TestContext context = createContext();

        Map<String, Object> writeResult = context.tools.writeProjectTextFile(
                "notes/total.md",
                "# 01_cover\nhello",
                context.runtime);

        assertThat(writeResult).containsEntry("relativePath", "notes/total.md");
        assertThat(Files.readString(context.projectPath.resolve("notes/total.md"))).isEqualTo("# 01_cover\nhello");
        assertThatThrownBy(() -> context.tools.writeProjectTextFile("exports/filled.pptx", "x", context.runtime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not allowed");
    }

    /**
     * 验证 {@code writeProjectTextFile} 允许写入 images/image_prompts.json。
     */
    @Test
    void writeProjectTextFileAllowsImagePromptsManifest() throws IOException {
        TestContext context = createContext();
        String manifest = """
                {"items": [{"filename": "cover.png", "prompt": "a cover", "aspect_ratio": "16:9", "status": "Pending"}]}
                """;

        Map<String, Object> writeResult = context.tools.writeProjectTextFile(
                "images/image_prompts.json",
                manifest,
                context.runtime);

        assertThat(writeResult).containsEntry("relativePath", "images/image_prompts.json");
        assertThat(Files.readString(context.projectPath.resolve("images/image_prompts.json"))).isEqualTo(manifest);
    }

    /**
     * 验证 {@code generateProjectImages} 在 manifest 存在时调用 image_gen.py --manifest。
     */
    @Test
    void generateProjectImagesDelegatesToImageGenScript() throws IOException {
        TestContext context = createContext();
        Files.createDirectories(context.projectPath.resolve("images"));
        Files.writeString(context.projectPath.resolve("images/image_prompts.json"), """
                {"items": [{"filename": "cover.png", "prompt": "a cover", "aspect_ratio": "16:9", "status": "Pending"}]}
                """);

        context.tools.generateProjectImages(context.runtime);

        assertThat(context.executor.calledScripts()).containsExactly("skills/ppt-master/scripts/image_gen.py");
        assertThat(context.executor.argumentsFor("skills/ppt-master/scripts/image_gen.py"))
                .containsExactly(
                        "--manifest", context.projectPath.resolve("images/image_prompts.json").toString(),
                        "--output", context.projectPath.resolve("images").toString());
    }

    /**
     * 验证 {@code inspectImageManifestStatus} 返回 manifest 中各 item 的状态汇总。
     */
    @Test
    void inspectImageManifestStatusSummarizesStatuses() throws IOException {
        TestContext context = createContext();
        Files.createDirectories(context.projectPath.resolve("images"));
        Files.writeString(context.projectPath.resolve("images/image_prompts.json"), """
                {
                  "items": [
                    {"filename": "cover.png", "prompt": "a cover", "aspect_ratio": "16:9", "status": "Generated"},
                    {"filename": "diagram.png", "prompt": "a diagram", "aspect_ratio": "4:3", "status": "Failed", "last_error": "rate limit"}
                  ]
                }
                """);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = context.tools.inspectImageManifestStatus(context.runtime);

        assertThat(result).containsEntry("exists", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertThat(summary).containsEntry("total", 2);
        assertThat(summary).containsEntry("generated", 1);
        assertThat(summary).containsEntry("failed", 1);
    }

    /**
     * 验证后处理工具方法（validateSvgOutput、splitSpeakerNotes、finalizeProjectSvg）
     * 正确委托到对应的 Python 脚本，且传入正确的项目路径和格式参数。
     */
    @Test
    void postProcessingToolsDelegateToExpectedScripts() throws IOException {
        TestContext context = createContext();
        Files.writeString(context.projectPath.resolve("notes/total.md"), "# 01_cover\nhello");

        context.tools.validateSvgOutput(context.runtime);
        context.tools.splitSpeakerNotes(context.runtime);
        context.tools.finalizeProjectSvg(context.runtime);

        assertThat(context.executor.calledScripts()).containsExactly(
                "skills/ppt-master/scripts/svg_quality_checker.py",
                "skills/ppt-master/scripts/total_md_split.py",
                "skills/ppt-master/scripts/finalize_svg.py");
        assertThat(context.executor.argumentsFor("skills/ppt-master/scripts/svg_quality_checker.py"))
                .containsExactly(context.projectPath.toString(), "--format", "ppt169");
    }

    private TestContext createContext() throws IOException {
        Path repoPath = tempDir.resolve("repo");
        Path workspacePath = tempDir.resolve("workspace");
        Path projectPath = workspacePath.resolve("projects/demo_ppt169_20260702");
        Files.createDirectories(projectPath.resolve("sources"));
        Files.createDirectories(projectPath.resolve("analysis"));
        Files.createDirectories(projectPath.resolve("notes"));
        Files.createDirectories(projectPath.resolve("svg_output"));
        Files.createDirectories(projectPath.resolve("svg_final"));
        Files.createDirectories(projectPath.resolve("exports"));

        PptMasterProperties properties = new PptMasterProperties(repoPath, workspacePath, "python3", Duration.ofMinutes(1));
        AgentScopeProperties agentProperties = new AgentScopeProperties(
                "openai",
                "dummy-model",
                null,
                null,
                8,
                tempDir.resolve("sessions"),
                "ppt-master-service");
        RecordingCommandExecutor executor = new RecordingCommandExecutor(properties);
        PptWorkflowEvents events = new PptWorkflowEvents(new PptJobEventPublisher());
        DefaultAgentScopeWorkflowAgentFactory factory = new DefaultAgentScopeWorkflowAgentFactory(
                agentProperties,
                properties,
                executor,
                events);
        DefaultAgentScopeWorkflowAgentFactory.PptAgentTools tools = factory.new PptAgentTools();

        PptJob job = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                workspacePath.resolve("jobs/demo"));
        job.prepareProject(projectPath);
        DefaultAgentScopeWorkflowAgentFactory.PptAgentToolRuntime runtime =
                new DefaultAgentScopeWorkflowAgentFactory.PptAgentToolRuntime(job, properties, executor, events);
        return new TestContext(projectPath, tools, runtime, executor);
    }

    private record TestContext(
            Path projectPath,
            DefaultAgentScopeWorkflowAgentFactory.PptAgentTools tools,
            DefaultAgentScopeWorkflowAgentFactory.PptAgentToolRuntime runtime,
            RecordingCommandExecutor executor) {
    }

    /**
     * 测试用 {@link PptMasterCommandExecutor} 实现。
     * <p>
     * 重写 {@link #runPythonScript(String, List)} 方法，不实际执行系统命令，
     * 而是记录每次调用时的脚本路径和参数列表，供后续断言验证调用委托是否正确。
     */
    private static final class RecordingCommandExecutor extends PptMasterCommandExecutor {

        private final List<String> calledScripts = new ArrayList<>();
        private final java.util.LinkedHashMap<String, List<String>> argumentsByScript = new java.util.LinkedHashMap<>();

        RecordingCommandExecutor(PptMasterProperties properties) {
            super(properties);
        }

        @Override
        public CommandResult runPythonScript(String scriptRelativePath, List<String> arguments) {
            calledScripts.add(scriptRelativePath);
            argumentsByScript.put(scriptRelativePath, List.copyOf(arguments));
            return new CommandResult(0, "ok", false);
        }

        List<String> calledScripts() {
            return calledScripts;
        }

        List<String> argumentsFor(String scriptRelativePath) {
            return argumentsByScript.get(scriptRelativePath);
        }
    }
}
