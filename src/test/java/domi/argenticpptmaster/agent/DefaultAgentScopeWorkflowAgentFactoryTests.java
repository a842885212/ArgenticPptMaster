package domi.argenticpptmaster.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.AgentScopeProperties.Execution;
import domi.argenticpptmaster.config.AgentScopeProperties.ModelGroup;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.service.PptJobEventPublisher;
import domi.argenticpptmaster.service.PptOutlineStore;
import domi.argenticpptmaster.service.PptWorkflowEvents;
import io.agentscope.core.model.Model;
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
        context.job.confirmNode(PptJobNode.PLAN_CONFIRMED);

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

    @Test
    void writeProjectTextFileRejectsInvalidOutlineBeforePersistingIt() throws IOException {
        TestContext context = createContext();
        String invalidOutline = """
                {"version": 1, "locked": false, "slides": [{
                  "slideNo": 1, "title": "封面", "keyMessage": "欢迎", "visualSuggestion": "插图"
                }]}
                """;

        assertThatThrownBy(() -> context.tools.writeProjectTextFile(
                "outline.json", invalidOutline, context.runtime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outline.json is invalid");
        assertThat(Files.exists(context.projectPath.resolve("outline.json"))).isFalse();
    }

    @Test
    void writeProjectTextFileReplacesInvalidUnlockedOutlineDuringRecovery() throws IOException {
        TestContext context = createContext();
        Files.writeString(context.projectPath.resolve("outline.json"), "{\"slides\": [{\"slideNo\": 1}]}" );
        String validOutline = """
                {"version": 1, "locked": false, "slides": [{
                  "slideNo": 1, "title": "封面", "keyMessage": "欢迎",
                  "bullets": ["说明"], "visualSuggestion": "插图"
                }]}
                """;

        context.tools.writeProjectTextFile("outline.json", validOutline, context.runtime);

        assertThat(new PptOutlineStore().read(context.projectPath).slides())
                .extracting(slide -> slide.title())
                .containsExactly("封面");
    }

    @Test
    void writeProjectTextFilePreservesExplicitlyLockedInvalidOutline() throws IOException {
        TestContext context = createContext();
        String lockedInvalidOutline = """
                {"version": 1, "locked": true, "slides": [{"slideNo": 1}]}
                """;
        Files.writeString(context.projectPath.resolve("outline.json"), lockedInvalidOutline);
        String validOutline = """
                {"version": 1, "locked": false, "slides": [{
                  "slideNo": 1, "title": "封面", "keyMessage": "欢迎",
                  "bullets": ["说明"], "visualSuggestion": "插图"
                }]}
                """;

        assertThatThrownBy(() -> context.tools.writeProjectTextFile(
                "outline.json", validOutline, context.runtime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("locked outline cannot be overwritten");
        assertThat(Files.readString(context.projectPath.resolve("outline.json"))).isEqualTo(lockedInvalidOutline);
    }

    /** 验证图片清单只能由锁定大纲派生工具写入，不能通过通用写入工具伪造。 */
    @Test
    void writeProjectTextFileRejectsImagePromptsManifest() throws IOException {
        TestContext context = createContext();
        context.job.confirmNode(PptJobNode.PLAN_CONFIRMED);
        String manifest = """
                {"items": [{"filename": "cover.png", "prompt": "a cover", "aspect_ratio": "16:9", "status": "Pending"}]}
                """;

        assertThatThrownBy(() -> context.tools.writeProjectTextFile(
                "images/image_prompts.json", manifest, context.runtime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be derived from the locked outline");
    }

    @Test
    void downstreamWritesRequireApprovedOutline() throws IOException {
        TestContext context = createContext();
        PptJob pendingJob = new PptJob(
                UUID.randomUUID(), "pending", "ppt169", "make a deck", PptWorkflowMode.BASIC,
                tempDir.resolve("workspace/jobs/pending"));
        pendingJob.prepareProject(context.projectPath);
        DefaultAgentScopeWorkflowAgentFactory.PptAgentToolRuntime pendingRuntime =
                new DefaultAgentScopeWorkflowAgentFactory.PptAgentToolRuntime(
                        pendingJob, context.properties, context.executor, context.events);

        assertThatThrownBy(() -> context.tools.writeProjectTextFile("design_spec.md", "draft", pendingRuntime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("逐页大纲尚未批准");
        assertThatThrownBy(() -> context.tools.splitSpeakerNotes(pendingRuntime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("逐页大纲尚未批准");
        assertThatThrownBy(() -> context.tools.finalizeProjectSvg(pendingRuntime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("逐页大纲尚未批准");
        assertThatThrownBy(() -> context.tools.exportProjectPptx(pendingRuntime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("逐页大纲尚未批准");
        assertThatThrownBy(() -> context.tools.generateProjectImages(pendingRuntime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("逐页大纲尚未批准");
    }

    /**
     * 验证 {@code generateProjectImages} 在 manifest 存在时调用 image_gen.py --manifest。
     */
    @Test
    void generateProjectImagesDelegatesToImageGenScript() throws IOException {
        TestContext context = createContext();
        context.job.confirmNode(PptJobNode.PLAN_CONFIRMED);
        context.job.confirmNode(PptJobNode.IMAGE_MANIFEST_CONFIRMED);
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
     * 验证只有所有图片 item 均为 Generated 时，才会推进 IMAGES_GENERATED 节点。
     */
    @Test
    void inspectImageManifestStatusAdvancesNodeOnlyWhenAllGenerated() throws IOException {
        TestContext context = createContextWithImageEnhancedMode();
        Files.createDirectories(context.projectPath.resolve("images"));
        Files.writeString(context.projectPath.resolve("images/image_prompts.json"), """
                {
                  "items": [
                    {"filename": "cover.png", "prompt": "a cover", "aspect_ratio": "16:9", "status": "Generated"},
                    {"filename": "diagram.png", "prompt": "a diagram", "aspect_ratio": "4:3", "status": "Needs-Manual"}
                  ]
                }
                """);

        context.tools.inspectImageManifestStatus(context.runtime);

        assertThat(context.job.lastCompletedNode()).isEmpty();
    }

    /**
     * 验证 manifest 中存在未知状态时不会推进 IMAGES_GENERATED 节点。
     */
    @Test
    void inspectImageManifestStatusDoesNotAdvanceNodeOnUnknownStatus() throws IOException {
        TestContext context = createContextWithImageEnhancedMode();
        Files.createDirectories(context.projectPath.resolve("images"));
        Files.writeString(context.projectPath.resolve("images/image_prompts.json"), """
                {
                  "items": [
                    {"filename": "cover.png", "prompt": "a cover", "aspect_ratio": "16:9", "status": "Generated"},
                    {"filename": "diagram.png", "prompt": "a diagram", "aspect_ratio": "4:3", "status": "Processing"}
                  ]
                }
                """);

        context.tools.inspectImageManifestStatus(context.runtime);

        assertThat(context.job.lastCompletedNode()).isEmpty();
    }

    @Test
    void inspectImageManifestStatusRejectsGeneratedCurrentVersionItemWhenFileIsMissing() throws IOException {
        TestContext context = createContextWithImageEnhancedMode();
        context.job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        new PptOutlineStore().write(context.projectPath, new domi.argenticpptmaster.domain.PptOutline(1, true, List.of(
                new domi.argenticpptmaster.domain.SlideOutline(1, "封面", "结论", List.of("要点"), "插图",
                        Map.of("purpose", "封面", "prompt", "蓝色城市")))));
        context.tools.deriveImageManifestFromLockedOutline(context.runtime);
        context.job.confirmNode(PptJobNode.IMAGE_MANIFEST_CONFIRMED);
        Files.writeString(context.projectPath.resolve("images/image_prompts.json"), """
                {"outlineVersion": 1, "items": [{"outlineVersion": 1, "slideNo": 1,
                  "requirementFingerprint": "fingerprint", "filename": "slide-01-image.png",
                  "purpose": "封面", "prompt": "蓝色城市", "aspect_ratio": "16:9", "status": "Generated"}]}
                """);

        assertThatThrownBy(() -> context.tools.inspectImageManifestStatus(context.runtime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generated image file is missing");
    }

    /**
     * 验证后处理工具方法（validateSvgOutput、splitSpeakerNotes、finalizeProjectSvg）
     * 正确委托到对应的 Python 脚本，且传入正确的项目路径和格式参数。
     */
    @Test
    void postProcessingToolsDelegateToExpectedScripts() throws IOException {
        TestContext context = createContext();
        context.job.confirmNode(PptJobNode.PLAN_CONFIRMED);
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

    /**
     * 验证 IMAGE_ENHANCED 的系统提示词中包含图片失败后的重试确认环规则。
     */
    @Test
    void imageEnhancedSystemPromptContainsImageRetryLoop() {
        TestContext context = createContextWithImageEnhancedMode();
        String prompt = context.factory.buildSystemPrompt(PptWorkflowMode.IMAGE_ENHANCED);

        assertThat(prompt).contains("image_retry_decision");
        assertThat(prompt).contains("image_ready_continue_confirmation");
        assertThat(prompt).contains("image_manifest_confirmation");
        assertThat(prompt).contains("derive_image_manifest_from_locked_outline");
        assertThat(prompt).contains("首次调用 request_plan_confirmation 只能使用 stage=outline_confirmation");
        assertThat(prompt).contains("不得以任何图片阶段作为首次确认");
        assertThat(prompt).contains("bullets 必须是至少包含一条非空字符串的数组");
        assertThat(prompt).contains("outline.json 示例：{\"version\":1");
        assertThat(prompt).contains("contextData 示例：{\"type\":\"ppt_outline\"");
        assertThat(prompt).contains("绝不要直接结束任务");
        assertThat(prompt).contains("不要输出 FINAL 总结");
        assertThat(prompt).contains("只有用户确认继续后，才进入步骤 9");
    }

    /**
     * 验证 BASIC 模式的系统提示词中不包含 IMAGE_ENHANCED 专属的图片重试规则。
     */
    @Test
    void basicSystemPromptDoesNotContainImageRetryRules() throws IOException {
        TestContext context = createContext();
        String prompt = context.factory.buildSystemPrompt(PptWorkflowMode.BASIC);

        assertThat(prompt).doesNotContain("image_retry_decision");
        assertThat(prompt).doesNotContain("image_ready_continue_confirmation");
        assertThat(prompt).doesNotContain("绝不要直接结束任务");
    }

    private TestContext createContextWithImageEnhancedMode() {
        try {
            TestContext base = createContext();
            PptJob job = new PptJob(
                    base.job.id(),
                    base.job.projectName(),
                    base.job.format(),
                    base.job.instruction(),
                    PptWorkflowMode.IMAGE_ENHANCED,
                    base.job.workspacePath());
            job.prepareProject(base.projectPath);
            return new TestContext(base.projectPath, base.tools,
                    new DefaultAgentScopeWorkflowAgentFactory.PptAgentToolRuntime(
                            job, base.properties, base.executor, base.events),
                    base.executor, base.factory, job, base.properties, base.events, base.agentProperties);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * 验证无 fallback 模型组时，工厂构建的仍是单一模型。
     */
    @Test
    void createsSingleModelWithoutFallback() throws Exception {
        TestContext context = createContext();
        Model model = context.factory.buildModel();
        assertThat(model).isNotInstanceOf(FallbackModel.class);
        assertThat(model.getModelName()).isEqualTo("dummy-model");
    }

    /**
     * 验证配置 fallback 模型组后，工厂构建的是 {@link FallbackModel} 包装器。
     */
    @Test
    void createsFallbackModelWithFallbackGroups() throws Exception {
        TestContext context = createContextWithFallbacks();
        Model model = context.factory.buildModel();
        assertThat(model).isInstanceOf(FallbackModel.class);
        assertThat(model.getModelName()).contains("dummy-model").contains("gpt-4o-mini");
    }

    /**
     * 验证当主模型组无效时，前置条件检查会抛出异常。
     */
    @Test
    void rejectsInvalidPrimaryModelGroup() {
        AgentScopeProperties properties = new AgentScopeProperties(
                null,
                null,
                null,
                null,
                8,
                tempDir.resolve("sessions"),
                "ppt-master-service",
                null,
                null,
                null);
        PptMasterProperties pptProperties = new PptMasterProperties(
                tempDir.resolve("repo"),
                tempDir.resolve("workspace"),
                "python3",
                Duration.ofMinutes(1),
                null,
                1_048_576L);
        DefaultAgentScopeWorkflowAgentFactory factory = new DefaultAgentScopeWorkflowAgentFactory(
                properties,
                pptProperties,
                new RecordingCommandExecutor(pptProperties),
                new PptWorkflowEvents(new PptJobEventPublisher()));

        assertThatThrownBy(factory::buildModel)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primary model group must be configured");
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

        PptMasterProperties properties = new PptMasterProperties(
                repoPath, workspacePath, "python3", Duration.ofMinutes(1), null, 1_048_576L);
        AgentScopeProperties agentProperties = new AgentScopeProperties(
                "openai",
                "dummy-model",
                null,
                null,
                8,
                tempDir.resolve("sessions"),
                "ppt-master-service",
                null,
                null,
                null);
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
        return new TestContext(projectPath, tools, runtime, executor, factory, job, properties, events, agentProperties);
    }

    private TestContext createContextWithFallbacks() {
        try {
            TestContext base = createContext();
            AgentScopeProperties agentProperties = new AgentScopeProperties(
                    "openai",
                    "dummy-model",
                    null,
                    null,
                    8,
                    tempDir.resolve("sessions"),
                    "ppt-master-service",
                    new ModelGroup("openai", "dummy-model", null, null),
                    List.of(new ModelGroup("openai", "gpt-4o-mini", null, null)),
                    new Execution(3, Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(120)));
            DefaultAgentScopeWorkflowAgentFactory factory = new DefaultAgentScopeWorkflowAgentFactory(
                    agentProperties,
                    base.properties,
                    new RecordingCommandExecutor(base.properties),
                    base.events);
            return new TestContext(base.projectPath, factory.new PptAgentTools(),
                    new DefaultAgentScopeWorkflowAgentFactory.PptAgentToolRuntime(
                            base.job, base.properties, base.executor, base.events),
                    base.executor, factory, base.job, base.properties, base.events, agentProperties);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private record TestContext(
            Path projectPath,
            DefaultAgentScopeWorkflowAgentFactory.PptAgentTools tools,
            DefaultAgentScopeWorkflowAgentFactory.PptAgentToolRuntime runtime,
            RecordingCommandExecutor executor,
            DefaultAgentScopeWorkflowAgentFactory factory,
            PptJob job,
            PptMasterProperties properties,
            PptWorkflowEvents events,
            AgentScopeProperties agentProperties) {
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
