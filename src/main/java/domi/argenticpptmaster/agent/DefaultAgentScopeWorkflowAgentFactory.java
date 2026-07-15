package domi.argenticpptmaster.agent;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptNodeExecution;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.service.PptWorkflowEvents;
import domi.argenticpptmaster.service.PptOutlineStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import domi.argenticpptmaster.config.AgentScopeProperties.ModelGroup;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.ollama.OllamaOptions;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * AgentScope 工作流代理的默认工厂实现。
 * <p>
 * 该实现负责根据 {@link PptJob} 任务信息，完整构建并配置一个
 * AgentScope 工作流代理。主要职责包括：
 * </p>
 * <ul>
 *   <li>初始化 AgentScope {@link ReActAgent} 实例及其运行器</li>
 *   <li>构建系统提示词（System Prompt），注入项目上下文和文件信息</li>
 *   <li>注册 PPT 生成所需的工具函数（文件操作、用户确认等）</li>
 *   <li>配置 LLM 模型参数（端点、API Key、模型名称等）</li>
 * </ul>
 * <p>
 * 内部通过 {@link PptAgentTools} 提供文件操作工具，通过
 * {@link UserPlanApprovalTool} 提供用户交互确认能力。
 * </p>
 */
@Component
public class DefaultAgentScopeWorkflowAgentFactory implements AgentScopeWorkflowAgentFactory {

    private static final String PROJECT_MANAGER_SCRIPT = "skills/ppt-master/scripts/project_manager.py";
    private static final String FINALIZE_SVG_SCRIPT = "skills/ppt-master/scripts/finalize_svg.py";
    private static final String TOTAL_MD_SPLIT_SCRIPT = "skills/ppt-master/scripts/total_md_split.py";
    private static final String SVG_QUALITY_CHECKER_SCRIPT = "skills/ppt-master/scripts/svg_quality_checker.py";
    private static final String EXPORT_SCRIPT = "skills/ppt-master/scripts/svg_to_pptx.py";
    private static final String IMAGE_GEN_SCRIPT = "skills/ppt-master/scripts/image_gen.py";
    private static final Logger log = LoggerFactory.getLogger(DefaultAgentScopeWorkflowAgentFactory.class);

    private static final String APPROVAL_TOOL_NAME = "request_plan_confirmation";
    private static final int DEFAULT_FILE_PREVIEW_CHARS = 8000;
    private static final Predicate<Throwable> RETRY_ON_RECOVERABLE_ONLY = ExecutionConfig.RETRYABLE_ERRORS;

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

    /**
     * 根据 PPT 任务创建并配置 AgentScope 工作流代理。
     * <p>
     * 构建过程包括：校验前置条件、初始化 Toolkit 并注册工具、
     * 构建 ReActAgent 实例（配置模型、系统提示词、会话存储等）、
     * 创建工具运行时上下文，最终返回一个 Lambda 包装后的
     * {@link AgentScopeWorkflowAgent} 实例。
     * </p>
     *
     * @param job PPT 任务信息
     * @return 配置完成的 AgentScope 工作流代理实例
     */
    @Override
    public AgentScopeWorkflowAgent create(PptJob job) {
        ensureAgentPrerequisites();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PptAgentTools());
        toolkit.registerTool(new UserPlanApprovalTool());

        ReActAgent agent = ReActAgent.builder()
                .name("ppt_master_orchestrator")
                .description("Orchestrates the ppt-master workflow: markdown-first route with optional image-enhanced generation.")
                .sysPrompt(buildSystemPrompt(job.workflowMode()))
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

    /**
     * 确保代理运行的前置条件满足。
     * <p>
     * 检查所需的 ppt-master 脚本文件是否存在，以及主模型组是否已配置。
     * 如果前置条件不满足，则抛出 {@link IllegalStateException}。
     * </p>
     */
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
        if (!Files.exists(pptMasterProperties.repoPath().resolve(IMAGE_GEN_SCRIPT))) {
            throw new IllegalStateException("ppt-master image_gen not found under " + pptMasterProperties.repoPath());
        }
        ModelGroup primary = agentScopeProperties.effectivePrimary();
        if (!primary.isValid()) {
            throw new IllegalStateException("agentscope primary model group must be configured");
        }
        if (requiresApiKey(primary) && (primary.apiKey() == null || primary.apiKey().isBlank())) {
            log.warn("agentscope primary model group has no api-key configured: provider={}", primary.provider());
        }
    }

    /**
     * 判断指定模型组是否需要 API Key。
     *
     * @param group 模型组
     * @return true 表示需要 API Key
     */
    private boolean requiresApiKey(ModelGroup group) {
        String provider = group.normalizedProvider();
        return "openai".equals(provider) || "dashscope".equals(provider);
    }

    /**
     * 构建工作流使用的最终模型实例。
     * <p>
     * 根据 {@link AgentScopeProperties} 配置构造主模型，并可选地包装为
     * {@link FallbackModel} 以支持 fallback 模型组。所有单模型均会注入
     * 统一的执行策略（重试、超时）。
     * </p>
     *
     * @return 配置完成的 AgentScope 模型实例
     */
    Model buildModel() {
        ModelGroup primaryGroup = agentScopeProperties.effectivePrimary();
        if (!primaryGroup.isValid()) {
            throw new IllegalStateException("agentscope primary model group must be configured");
        }
        Model primary = buildModelGroup(primaryGroup);
        List<Model> fallbackModels = agentScopeProperties.effectiveFallbacks().stream()
                .map(this::buildModelGroup)
                .toList();
        if (fallbackModels.isEmpty()) {
            return primary;
        }
        List<Model> candidates = new ArrayList<>(fallbackModels.size() + 1);
        candidates.add(primary);
        candidates.addAll(fallbackModels);
        return new FallbackModel(candidates);
    }

    /**
     * 根据模型组配置构建单个 AgentScope 模型实例。
     * <p>
     * 支持 OpenAI、DashScope 和 Ollama 三种提供者，并统一注入执行策略。
     * </p>
     *
     * @param group 模型组配置
     * @return AgentScope 模型实例
     * @throws IllegalStateException 当 provider 不受支持时抛出
     */
    private Model buildModelGroup(ModelGroup group) {
        String provider = group.normalizedProvider();
        return switch (provider) {
            case "dashscope" -> buildDashScopeModel(group);
            case "ollama" -> buildOllamaModel(group);
            case "openai" -> buildOpenAiModel(group);
            default -> throw new IllegalStateException("unsupported agentscope.provider: " + provider);
        };
    }

    /**
     * 构建统一的执行策略配置。
     * <p>
     * 结合 {@link AgentScopeProperties#execution()} 配置与 AgentScope 默认策略，
     * 生成用于 {@link GenerateOptions} 或 {@link OllamaOptions} 的 {@link ExecutionConfig}。
     * </p>
     *
     * @return 执行策略配置
     */
    private ExecutionConfig buildExecutionConfig() {
        AgentScopeProperties.Execution execution = agentScopeProperties.execution();
        return ExecutionConfig.builder()
                .timeout(execution.timeout())
                .maxAttempts(execution.maxAttempts())
                .initialBackoff(execution.initialBackoff())
                .maxBackoff(execution.maxBackoff())
                .backoffMultiplier(2.0)
                .retryOn(RETRY_ON_RECOVERABLE_ONLY)
                .build();
    }

    /**
     * 构建 OpenAI 兼容的模型实例。
     * <p>
     * 使用 {@link OpenAIChatModel} 构建器，配置模型名称、API Key、
     * 自定义 Base URL 以及统一的执行策略。
     * </p>
     *
     * @param group 模型组配置
     * @return OpenAI 模型实例
     */
    private Model buildOpenAiModel(ModelGroup group) {
        OpenAIChatModel.Builder builder = new OpenAIChatModel.Builder()
                .modelName(group.modelName())
                .stream(true)
                .generateOptions(GenerateOptions.builder()
                        .executionConfig(buildExecutionConfig())
                        .build());
        if (group.apiKey() != null && !group.apiKey().isBlank()) {
            builder.apiKey(group.apiKey());
        }
        if (group.baseUrl() != null && !group.baseUrl().isBlank()) {
            builder.baseUrl(group.baseUrl());
        }
        return builder.build();
    }

    /**
     * 构建 DashScope（阿里通义千问）模型实例。
     * <p>
     * 使用 {@link DashScopeChatModel} 构建器，配置模型名称、API Key、
     * 自定义 Base URL 以及统一的执行策略。
     * </p>
     *
     * @param group 模型组配置
     * @return DashScope 模型实例
     */
    private Model buildDashScopeModel(ModelGroup group) {
        DashScopeChatModel.Builder builder = new DashScopeChatModel.Builder()
                .modelName(group.modelName())
                .stream(true)
                .defaultOptions(GenerateOptions.builder()
                        .executionConfig(buildExecutionConfig())
                        .build());
        if (group.apiKey() != null && !group.apiKey().isBlank()) {
            builder.apiKey(group.apiKey());
        }
        if (group.baseUrl() != null && !group.baseUrl().isBlank()) {
            builder.baseUrl(group.baseUrl());
        }
        return builder.build();
    }

    /**
     * 构建 Ollama 本地模型实例。
     * <p>
     * 使用 {@link OllamaChatModel} 构建器，配置模型名称、自定义 Base URL
     * 以及统一的执行策略。
     * </p>
     *
     * @param group 模型组配置
     * @return Ollama 模型实例
     */
    private Model buildOllamaModel(ModelGroup group) {
        OllamaOptions options = OllamaOptions.builder()
                .executionConfig(buildExecutionConfig())
                .build();
        OllamaChatModel.Builder builder = new OllamaChatModel.Builder()
                .modelName(group.modelName())
                .defaultOptions(options);
        if (group.baseUrl() != null && !group.baseUrl().isBlank()) {
            builder.baseUrl(group.baseUrl());
        }
        return builder.build();
    }

    /**
     * 构建系统提示词（System Prompt）。
     * <p>
     * 采用“上游规则 + 本地补丁”的分层结构：
     * </p>
     * <ul>
     *   <li>上游规则层 —— 摘取自 ppt-master SKILL.md / image-generator.md 的稳定流程规则</li>
     *   <li>本地流程补丁 —— 根据 {@link PptWorkflowMode} 选择基础或进阶流程的具体步骤</li>
     *   <li>本地约束补丁 —— 当前服务暴露的工具、写路径、确认机制与失败规则</li>
     * </ul>
     *
     * @param workflowMode 当前任务的工作流模式
     * @return 系统提示词文本
     */
    String buildSystemPrompt(PptWorkflowMode workflowMode) {
        return String.join("\n\n",
                buildUpstreamCoreRulesPrompt(),
                workflowMode == PptWorkflowMode.IMAGE_ENHANCED
                        ? buildImageEnhancedWorkflowPatchPrompt()
                        : buildBasicWorkflowPatchPrompt(),
                buildLocalToolingConstraintsPrompt());
    }

    /**
     * 上游核心规则摘要。
     * <p>
     * 复用 ppt-master 主流程的不可变规则：阶段顺序、人工确认、串行执行、
     * 不跨阶段预执行、SVG 必须手写等。
     * </p>
     *
     * @return 上游规则文本
     */
    private String buildUpstreamCoreRulesPrompt() {
        return """
                你是 ArgenticPptMaster 的 ppt-master 编排代理。

                上游核心规则（来自 ppt-master）：
                1. 主流程按严格顺序执行：Source → Strategist → [Image_Generator] → Executor → Post-processing → Export。
                2. BLOCKING 阶段必须停下来等待用户明确回复；非 BLOCKING 阶段在前提满足后可连续执行。
                3. 不要跨阶段预执行：Strategist 阶段不要写 SVG，Executor 阶段不要重新决定设计。
                4. 每页 SVG 必须手写，不能通过脚本批量生成。
                5. Executor 生成每页 SVG 前必须重新读取 spec_lock.md，颜色 / 字体 / 图标 / 图片均来自该文件。
                6. 所有图片最终必须落在 project/images/<filename>；image_prompts.json 是图片阶段的共享契约。
                7. 如果缺少前置条件，清楚说明缺了什么，不要伪造“已完成”。
                """;
    }

    /**
     * 基础流程本地补丁。
     * <p>
     * 对应 {@link PptWorkflowMode#BASIC}：不启用文生图，严格走 markdown-first 路线。
     * </p>
     *
     * @return 基础流程补丁文本
     */
    private String buildBasicWorkflowPatchPrompt() {
        return """
                当前任务模式：BASIC（基础流程）。

                执行顺序：
                1. 调用 describe_job 了解任务，再调用 init_ppt_project，然后调用 import_job_sources。
                2. 导入完成后，调用 collect_source_markdown、inspect_project_info、list_project_files、read_project_text_file 了解 sources/ 与 analysis/ 的真实内容。
                3. 完成资料分析后，先生成按 slideNo 升序排列的逐页大纲并写入 outline.json（version 从 1 开始、locked=false）；每页必须包含 slideNo、title、keyMessage、bullets、visualSuggestion，并通过 request_plan_confirmation（stage="outline_confirmation"）在 contextData 中以 {type:"ppt_outline", version, locked:false, slides:[...]} 返回。
                4. 如果用户要求修改（REQUEST_REVISION），必须将整体意见和 slideEdits 逐项落实，重新生成完整逐页大纲并再次调用同一确认；在 APPROVE 前不得写任何下游产物。
                5. 在写 design_spec.md、spec_lock.md、notes/total.md、svg_output/*.svg 之前，必须完成上述大纲确认。
                6. 用户确认后，产出：
                   - design_spec.md
                   - spec_lock.md
                   - notes/total.md
                   - svg_output/*.svg
                7. 生成 svg_output 后，调用 validate_svg_output，再调用 split_speaker_notes、finalize_project_svg，最后调用 export_project_pptx。
                8. 当前模式不生成 images/image_prompts.json，也不调用 generate_project_images。
                """;
    }

    /**
     * 文生图进阶流程本地补丁。
     * <p>
     * 对应 {@link PptWorkflowMode#IMAGE_ENHANCED}：在 Strategist 与 Executor 之间插入图片阶段。
     * </p>
     *
     * @return 进阶流程补丁文本
     */
    private String buildImageEnhancedWorkflowPatchPrompt() {
        return """
                当前任务模式：IMAGE_ENHANCED（文生图进阶流程）。

                执行顺序：
                1. 调用 describe_job 了解任务，再调用 init_ppt_project，然后调用 import_job_sources。
                2. 导入完成后，调用 collect_source_markdown、inspect_project_info、list_project_files、read_project_text_file 了解 sources/ 与 analysis/ 的真实内容。
                3. 完成资料分析后，先生成按 slideNo 升序排列的逐页大纲并写入 outline.json（version 从 1 开始、locked=false）；每页必须包含 slideNo、title、keyMessage、bullets、visualSuggestion 和可选 imageRequirement，并通过 request_plan_confirmation（stage="outline_confirmation"）在 contextData 中以 {type:"ppt_outline", version, locked:false, slides:[...]} 返回；确认载荷中需说明将启用文生图。
                4. 如果用户要求修改（REQUEST_REVISION），必须将整体意见和 slideEdits 逐项落实，重新生成完整逐页大纲并再次调用同一确认；在 APPROVE 前不得写 design_spec.md、spec_lock.md 或任何图片/下游产物。
                5. 用户批准逐页大纲后，先产出 design_spec.md 与 spec_lock.md。
                6. 如果 design_spec 中声明了 AI 图片需求，必须写 images/image_prompts.json：
                   - 每个 item 必须包含 filename、prompt、aspect_ratio、status（初始为 Pending）
                   - 可包含 image_size、model、alt_text、purpose 等可选字段
                7. 调用 generate_project_images 执行 image_gen.py --manifest 生成图片。
                8. 调用 inspect_image_manifest_status 确认所有 item 状态：
                   - 若存在 Failed：
                     a. 绝不要直接结束任务、不要输出 FINAL 总结、不要写 notes/svg、不要导出。
                     b. 必须调用 request_plan_confirmation，stage 固定为 "image_retry_decision"。
                     c. 在 message/contextData 中说明失败图片、失败原因（取 manifest 的 last_error）、建议操作。
                     d. 用户回复后，如果其意图是“重试/继续生成/再试一次”，再次调用 generate_project_images。
                     e. 然后重新调用 inspect_image_manifest_status，重复本步骤，直到全部 Generated 或用户明确放弃。
                   - 若全部 Generated：
                     a. 必须调用 request_plan_confirmation，stage 固定为 "image_ready_continue_confirmation"。
                     b. 询问用户“图片已全部生成，是否继续后续 PPT 制作（notes、SVG、finalize、导出）”。
                     c. 只有用户确认继续后，才进入步骤 8。
                9. 图片就绪且用户确认继续后，再写 notes/total.md 与 svg_output/*.svg；svg_output 中引用图片时必须使用 images/<filename> 且文件真实存在。
                10. 生成 svg_output 后，调用 validate_svg_output，再调用 split_speaker_notes、finalize_project_svg，最后调用 export_project_pptx。

                图片阶段约束：
                - 第一版只支持 Path A：调用 generate_project_images（底层 image_gen.py --manifest）。
                - 不支持自动切换到 host-native 或 manual。
                - 图片生成失败时不可中止，必须进入 image_retry_decision 确认环，等待用户决定重试或放弃。
                - 只有用户明确放弃或任务被取消时才允许终止；不要在图片未生成的情况下继续 SVG 生成或导出。
                """;
    }

    /**
     * 根据写入的相对路径推进对应的业务节点。
     * <p>
     * 将文件写入与业务节点完成绑定，确保节点状态能反映真实产物状态。
     * </p>
     *
     * @param runtime       工具运行时上下文
     * @param normalizedPath 规范化的相对路径（使用正斜杠）
     */
    private void advanceNodeForWrittenFile(PptAgentToolRuntime runtime, String normalizedPath) {
        if (normalizedPath.equals("design_spec.md")) {
            runtime.completeNode(PptJobNode.DESIGN_SPEC_WRITTEN, Map.of("file", normalizedPath));
        } else if (normalizedPath.equals("spec_lock.md")) {
            runtime.completeNode(PptJobNode.SPEC_LOCK_WRITTEN, Map.of("file", normalizedPath));
        } else if (normalizedPath.equals("images/image_prompts.json")) {
            runtime.completeNode(PptJobNode.IMAGES_MANIFEST_WRITTEN, Map.of("file", normalizedPath));
        } else if (normalizedPath.equals("notes/total.md")) {
            runtime.completeNode(PptJobNode.NOTES_TOTAL_WRITTEN, Map.of("file", normalizedPath));
        }
    }

    /**
     * 本地工具与约束补丁。
     * <p>
     * 描述当前服务实际暴露的工具集合、可写路径、确认机制与失败策略。
     * </p>
     *
     * @return 本地约束补丁文本
     */
    private String buildLocalToolingConstraintsPrompt() {
        return """
                本地工具约束：
                - 可用工具：describe_job, init_ppt_project, import_job_sources, inspect_project_info, validate_project, list_project_files, collect_source_markdown, read_project_text_file, write_project_text_file, split_speaker_notes, validate_svg_output, finalize_project_svg, export_project_pptx, generate_project_images, inspect_image_manifest_status, inspect_checkpoint_status, request_plan_confirmation。
                - 允许写入的路径：design_spec.md、spec_lock.md、notes/*、svg_output/*、analysis/*、images/*。
                - 人工确认统一通过 request_plan_confirmation；只有该工具能暂停工作流等待用户回复。
                - 不要假设本地文件内容；所有文件状态通过 list_project_files / read_project_text_file / inspect_image_manifest_status / inspect_checkpoint_status 确认。
                - 不要走 template_fill_pptx 或 pptx 模板路线；当前服务只支持 markdown-first 路线。
                """;
    }

    /**
     * PPT 代理工具运行时上下文记录。
     * <p>
     * 封装工具执行过程中所需的共享状态，包括 PPT 任务信息、项目配置、
     * 命令执行器和事件记录器。
     * </p>
     *
     * @param job       PPT 任务信息
     * @param properties PPT 主配置属性
     * @param executor   PPT Master 命令执行器
     * @param events     工作流事件记录器
     */
    record PptAgentToolRuntime(
            PptJob job,
            PptMasterProperties properties,
            PptMasterCommandExecutor executor,
            PptWorkflowEvents events) {

        /**
         * 推进指定业务节点到已完成状态，并记录节点完成事件。
         *
         * @param node    业务节点
         * @param summary 完成摘要
         */
        void completeNode(PptJobNode node, Map<String, Object> summary) {
            if (!node.applicableTo(job.workflowMode())) {
                return;
            }
            PptNodeExecution execution = job.nodeExecution(node);
            if (execution == null || execution.status() == PptJobNodeStatus.PENDING) {
                job.startNode(node);
                events.record(job, PptJobEvent.of(
                        PptJobEventType.NODE_STARTED,
                        "node started: " + node.name(),
                        Map.of("node", node.name())));
            }
            job.completeNode(node, summary);
            events.record(job, PptJobEvent.of(
                    PptJobEventType.NODE_COMPLETED,
                    "node completed: " + node.name(),
                    Map.of("node", node.name(), "summary", summary)));
        }
    }

    /**
     * PPT 代理工具集。
     * <p>
     * 提供给 AI 代理的工具集合，用于在 PPT 生成过程中操作项目文件系统
     * 和执行 ppt-master 脚本。每个方法使用 {@link Tool} 注解暴露给
     * AgentScope 框架，供 LLM 调用。
     * </p>
     * <p>包含的工具：</p>
     * <ul>
     *   <li>任务查询：describe_job</li>
     *   <li>项目管理：init_ppt_project, import_job_sources, inspect_project_info, validate_project</li>
     *   <li>文件操作：list_project_files, collect_source_markdown, read_project_text_file, write_project_text_file</li>
     *   <li>后处理：split_speaker_notes, validate_svg_output, finalize_project_svg, export_project_pptx</li>
     * </ul>
     */
    final class PptAgentTools {

        /**
         * 描述当前 PPT 任务元数据和上传的源文件。
         * <p>
         * 返回任务 ID、项目名称、格式、用户指令、工作区路径、
         * 项目路径以及源文件列表等信息。
         * </p>
         *
         * @param runtime 工具运行时上下文
         * @return 包含任务元数据和源文件信息的 Map
         */
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

        /**
         * 初始化本地 ppt-master 项目目录。
         * <p>
         * 在当前任务的 workspace 中创建 ppt-master 项目目录结构。
         * 如果项目已初始化（存在 README.md），则直接返回已有路径。
         * </p>
         *
         * @param runtime 工具运行时上下文
         * @return 初始化结果描述
         */
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

        /**
         * 将上传的源文件导入到 ppt-master 项目的 sources 目录。
         * <p>
         * 如果 sources 目录中已有文件，则跳过导入以避免重复。
         * 导入时使用 --copy 模式保留原始文件。
         * </p>
         *
         * @param runtime 工具运行时上下文
         * @return 导入结果描述
         */
        @Tool(name = "import_job_sources", description = "Import uploaded source files into the prepared ppt-master project.")
        public String importJobSources(PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            Path sourcesDir = projectPath.resolve("sources");
            try (Stream<Path> sourceStream = Files.isDirectory(sourcesDir) ? Files.list(sourcesDir) : Stream.empty()) {
                if (sourceStream.findAny().isPresent()) {
                    runtime.completeNode(PptJobNode.PROJECT_READY, Map.of("skipped", true));
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
            runtime.completeNode(PptJobNode.PROJECT_READY, Map.of("sourcesDir", sourcesDir.toString()));
            return result.output().isBlank() ? "sources imported into " + sourcesDir : result.output();
        }

        /**
         * 检查当前项目工作区的状态。
         * <p>
         * 运行 ppt-master 的 info 命令，返回项目结构的详细信息，
         * 用于让 AI 代理了解当前工作区的进展情况。
         * </p>
         *
         * @param runtime 工具运行时上下文
         * @return 项目状态信息文本
         */
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

        /**
         * 检查当前 checkpoint 与真实项目文件状态是否一致。
         * <p>
         * 为 checkpoint 恢复场景提供一个稳定的结构化入口，避免 Agent 在恢复指令里
         * 调用不存在的工具。该工具会汇总项目 info、关键目录文件列表、已记录的节点执行状态，
         * 并返回当前缺失的关键证据，供 Agent 判断是否可以直接继续后续节点。
         * </p>
         *
         * @param runtime 工具运行时上下文
         * @return checkpoint 状态概览
         */
        @Tool(name = "inspect_checkpoint_status", description = "Inspect the current checkpoint state, including project info, key files, completed nodes, and missing evidence.", readOnly = true)
        public Map<String, Object> inspectCheckpointStatus(PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            String projectInfo = inspectProjectInfo(runtime);
            Map<String, Object> files = listProjectFiles(runtime);
            Map<String, Object> nodeStates = new LinkedHashMap<>();
            List<String> missingEvidence = new ArrayList<>();
            for (PptJobNode node : PptJobNode.values()) {
                if (!node.applicableTo(runtime.job().workflowMode())) {
                    continue;
                }
                PptNodeExecution execution = runtime.job().nodeExecution(node);
                Map<String, Object> entry = new LinkedHashMap<>();
                String status = execution == null ? "NOT_APPLICABLE" : execution.status().name();
                entry.put("status", status);
                if (execution != null && !execution.summary().isEmpty()) {
                    entry.put("summary", execution.summary());
                }
                boolean evidencePresent = hasCheckpointEvidence(projectPath, files, node);
                entry.put("evidencePresent", evidencePresent);
                if (execution != null && execution.status() == PptJobNodeStatus.COMPLETED && !evidencePresent) {
                    missingEvidence.add(node.name());
                }
                nodeStates.put(node.name(), entry);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("projectRoot", projectPath.toString());
            result.put("projectInfo", projectInfo);
            result.put("files", files);
            result.put("currentNode", runtime.job().currentNode().map(Enum::name).orElse(null));
            result.put("lastCompletedNode", runtime.job().lastCompletedNode().map(Enum::name).orElse(null));
            result.put("lastFailureNode", runtime.job().lastFailureNode().map(Enum::name).orElse(null));
            result.put("resumeCount", runtime.job().resumeCount());
            result.put("activeAttemptSessionId", runtime.job().activeAttemptSessionId());
            result.put("nodeStates", nodeStates);
            result.put("missingEvidence", missingEvidence);
            result.put("consistent", missingEvidence.isEmpty());
            return result;
        }

        /**
         * 验证 ppt-master 项目结构和导出资产。
         * <p>
         * 运行 ppt-master 的 validate 命令，检查项目目录结构是否完整、
         * 导出文件是否正确生成。
         * </p>
         *
         * @param runtime 工具运行时上下文
         * @return 验证结果文本
         */
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

        /**
         * 列出项目中的重要文件，按目录分组。
         * <p>
         * 遍历项目的 sources、analysis、notes、svg_output、svg_final
         * 和 exports 子目录，返回各目录下的文件列表，帮助 AI 代理
         * 了解项目文件结构。
         * </p>
         *
         * @param runtime 工具运行时上下文
         * @return 按目录分组的文件列表 Map
         */
        @Tool(name = "list_project_files", description = "List key files inside the prepared project, grouped by important directories.", readOnly = true)
        public Map<String, Object> listProjectFiles(PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("projectRoot", projectPath.toString());
            result.put("sources", listRelativeFiles(projectPath, "sources"));
            result.put("analysis", listRelativeFiles(projectPath, "analysis"));
            result.put("images", listRelativeFiles(projectPath, "images"));
            result.put("notes", listRelativeFiles(projectPath, "notes"));
            result.put("svgOutput", listRelativeFiles(projectPath, "svg_output"));
            result.put("svgFinal", listRelativeFiles(projectPath, "svg_final"));
            result.put("exports", listRelativeFiles(projectPath, "exports"));
            return result;
        }

        private boolean hasCheckpointEvidence(Path projectPath, Map<String, Object> files, PptJobNode node) {
            return switch (node) {
                case PROJECT_READY -> hasAnyFile(files.get("sources"));
                case OUTLINE_DRAFTED -> Files.isRegularFile(projectPath.resolve("outline.json"));
                case OUTLINE_CONFIRMED -> {
                    try {
                        yield new PptOutlineStore().read(projectPath).locked();
                    } catch (IllegalStateException ex) {
                        yield false;
                    }
                }
                case PLAN_CONFIRMED, IMAGE_CONTINUE_CONFIRMED -> true;
                case DESIGN_SPEC_WRITTEN -> Files.isRegularFile(projectPath.resolve("design_spec.md"));
                case SPEC_LOCK_WRITTEN -> Files.isRegularFile(projectPath.resolve("spec_lock.md"));
                case IMAGES_MANIFEST_WRITTEN -> Files.isRegularFile(projectPath.resolve("images/image_prompts.json"));
                case IMAGES_GENERATED -> hasGeneratedImages(projectPath);
                case NOTES_TOTAL_WRITTEN -> Files.isRegularFile(projectPath.resolve("notes/total.md"));
                case SVG_OUTPUT_VALIDATED -> hasAnyFile(files.get("svgOutput"));
                case SPEAKER_NOTES_SPLIT -> hasSplitSpeakerNotes(files.get("notes"));
                case SVG_FINALIZED -> hasAnyFile(files.get("svgFinal"));
                case PPT_EXPORTED -> hasAnyFile(files.get("exports"));
            };
        }

        private boolean hasGeneratedImages(Path projectPath) {
            Path imagesDir = projectPath.resolve("images");
            if (!Files.isDirectory(imagesDir)) {
                return false;
            }
            try (Stream<Path> stream = Files.list(imagesDir)) {
                return stream.anyMatch(path -> Files.isRegularFile(path)
                        && !path.getFileName().toString().equals("image_prompts.json"));
            } catch (IOException ex) {
                throw new IllegalStateException("failed to inspect images dir", ex);
            }
        }

        private boolean hasSplitSpeakerNotes(Object notesEntry) {
            if (!(notesEntry instanceof List<?> noteFiles)) {
                return false;
            }
            return noteFiles.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .anyMatch(path -> !path.equals("notes/total.md"));
        }

        private boolean hasAnyFile(Object value) {
            return value instanceof List<?> list && !list.isEmpty();
        }

        /**
         * 从 sources/ 目录读取规范化的 Markdown 源文件。
         * <p>
         * 用于 markdown 生成路线，读取已转换为 Markdown 格式的源文件内容。
         * 支持限制返回的文件数量和每个文件的字符数，避免超出上下文窗口。
         * </p>
         *
         * @param maxFiles        最多包含的 Markdown 文件数量（可选，默认 20）
         * @param maxCharsPerFile 每个文件返回的最大字符数（可选，默认 8000）
         * @param runtime         工具运行时上下文
         * @return 包含项目根路径和 Markdown 文件内容的 Map
         */
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

        /**
         * 读取项目中的文本文件用于源文件检查。
         * <p>
         * 根据相对于项目根目录的路径读取文件内容，包含路径穿越安全检查。
         * 支持限制返回的字符数，避免超出上下文窗口。
         * </p>
         *
         * @param relativePath 相对于项目根目录的文件路径
         * @param maxChars     返回的最大 UTF-8 字符数（可选，默认 8000）
         * @param runtime      工具运行时上下文
         * @return 文件文本内容
         */
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

        /**
         * 写入或覆盖项目文本文件。
         * <p>
         * 将内容写入项目中的指定路径，如 design_spec.md、spec_lock.md、
         * notes/total.md 或 svg_output/*.svg。包含路径穿越和白名单检查，
         * 只允许写入预定义的文件路径。
         * </p>
         *
         * @param relativePath 相对于项目根目录的写入路径
         * @param content      UTF-8 文本内容
         * @param runtime      工具运行时上下文
         * @return 包含写入结果信息的 Map（相对路径、字符数、字节数）
         */
        @Tool(name = "write_project_text_file", description = "Write or overwrite a project text file such as design_spec.md, spec_lock.md, notes/total.md, or svg_output/*.svg.")
        public Map<String, Object> writeProjectTextFile(
                @ToolParam(name = "relativePath", description = "Project-relative path to write.", required = true)
                String relativePath,
                @ToolParam(name = "content", description = "UTF-8 text content to write.", required = true)
                String content,
                PptAgentToolRuntime runtime) {
            Path target = resolveWritableProjectFile(runtime, relativePath);
            Path projectPath = runtime.job().projectPath().orElseThrow();
            String normalized = projectPath.relativize(target).toString().replace('\\', '/');
            if (normalized.equals("outline.json") && Files.isRegularFile(target)) {
                try {
                    if (new PptOutlineStore().read(projectPath).locked()) {
                        throw new IllegalStateException("locked outline cannot be overwritten");
                    }
                } catch (IllegalStateException ex) {
                    throw new IllegalStateException("outline.json cannot be overwritten after lock", ex);
                }
            }
            if ((normalized.equals("design_spec.md") || normalized.equals("spec_lock.md"))
                    && Files.isRegularFile(new PptOutlineStore().path(projectPath))) {
                int expectedVersion = new PptOutlineStore().read(projectPath).version();
                Matcher matcher = Pattern.compile("outlineVersion\\s*[:=]\\s*(\\d+)").matcher(content);
                if (!matcher.find() || Integer.parseInt(matcher.group(1)) != expectedVersion) {
                    throw new IllegalStateException(normalized + " must record current outlineVersion=" + expectedVersion);
                }
            }
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, content, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("failed to write project file: " + relativePath, ex);
            }
            advanceNodeForWrittenFile(runtime, normalized);
            return Map.of(
                    "relativePath", normalized,
                    "charCount", content.length(),
                    "bytes", content.getBytes(StandardCharsets.UTF_8).length);
        }

        /**
         * 拆分演讲者备注文件。
         * <p>
         * 使用 ppt-master 的 total_md_split.py 脚本将 notes/total.md
         * 拆分为每页独立的备注文件，存储在 notes/ 目录下。
         * </p>
         *
         * @param runtime 工具运行时上下文
         * @return 拆分结果描述
         */
        @Tool(name = "split_speaker_notes", description = "Split notes/total.md into per-slide notes files under notes/ using ppt-master total_md_split.py.")
        public String splitSpeakerNotes(PptAgentToolRuntime runtime) {
            requireApprovedOutline(runtime, "split speaker notes");
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            PptMasterCommandExecutor.CommandResult result = runtime.executor().runPythonScript(
                    TOTAL_MD_SPLIT_SCRIPT,
                    List.of(projectPath.toString()));
            if (!result.successful()) {
                throw new IllegalStateException("speaker notes split failed: " + result.output());
            }
            runtime.completeNode(PptJobNode.SPEAKER_NOTES_SPLIT, Map.of());
            return result.output().isBlank() ? "speaker notes split completed" : result.output();
        }

        /**
         * 验证 SVG 输出质量。
         * <p>
         * 使用 ppt-master 的 svg_quality_checker.py 对当前项目的 SVG 输出
         * 进行质量检查，确保 SVG 文件符合规范后再进行 finalize 和导出。
         * </p>
         *
         * @param runtime 工具运行时上下文
         * @return 验证结果描述
         */
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
            runtime.completeNode(PptJobNode.SVG_OUTPUT_VALIDATED, Map.of());
            return result.output().isBlank() ? "svg quality validation passed" : result.output();
        }

        /**
         * 最终处理项目 SVG 文件。
         * <p>
         * 使用 ppt-master 的 finalize_svg.py 脚本将 svg_output 目录中的
         * SVG 文件处理为最终的 svg_final 版本，包括格式调整和优化。
         * </p>
         *
         * @param runtime 工具运行时上下文
         * @return 处理结果描述
         */
        @Tool(name = "finalize_project_svg", description = "Run ppt-master finalize_svg.py to build svg_final from svg_output.")
        public String finalizeProjectSvg(PptAgentToolRuntime runtime) {
            requireApprovedOutline(runtime, "finalize SVG");
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            PptMasterCommandExecutor.CommandResult result = runtime.executor().runPythonScript(
                    FINALIZE_SVG_SCRIPT,
                    List.of(projectPath.toString()));
            if (!result.successful()) {
                throw new IllegalStateException("svg finalize failed: " + result.output());
            }
            runtime.completeNode(PptJobNode.SVG_FINALIZED, Map.of());
            return result.output().isBlank() ? "svg finalization completed" : result.output();
        }

        /**
         * 将 svg_final 项目导出为 PPTX 成品。
         * <p>
         * 检查 svg_final 目录是否存在且非空，然后使用 svg_to_pptx.py 脚本
         * 导出 PPTX 文件。导出完成后更新任务状态为完成，并发布
         * {@link PptJobEventType#EXPORT_READY} 事件通知外部消费者。
         * </p>
         *
         * @param runtime 工具运行时上下文
         * @return 导出结果描述
         */
        @Tool(name = "export_project_pptx", description = "Export the prepared svg_final project into a PPTX artifact.")
        public String exportProjectPptx(PptAgentToolRuntime runtime) {
            requireApprovedOutline(runtime, "export PPTX");
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
            runtime.completeNode(PptJobNode.PPT_EXPORTED, Map.of("fileName", artifact.getFileName().toString()));
            runtime.events().record(runtime.job(), PptJobEvent.of(
                    PptJobEventType.EXPORT_READY,
                    "ppt artifact exported",
                    Map.of("fileName", artifact.getFileName().toString())));
            return result.output().isBlank() ? "ppt exported: " + artifact.getFileName() : result.output();
        }

        /**
         * 执行图片生成（Path A）。
         * <p>
         * 当工作流模式为 {@link domi.argenticpptmaster.domain.PptWorkflowMode#IMAGE_ENHANCED}
         * 且已生成 {@code images/image_prompts.json} 后，调用 ppt-master 的
         * {@code image_gen.py --manifest} 命令批量生成图片。
         * 脚本会根据当前进程环境或 {@code .env} 中的 {@code IMAGE_BACKEND} 配置选择后端。
         * </p>
         *
         * @param runtime 工具运行时上下文
         * @return 图片生成结果描述
         */
        @Tool(name = "generate_project_images", description = "Run ppt-master image_gen.py --manifest to generate AI images listed in images/image_prompts.json.")
        public String generateProjectImages(PptAgentToolRuntime runtime) {
            requireApprovedOutline(runtime, "generate images");
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            Path manifestPath = projectPath.resolve("images/image_prompts.json");
            if (!Files.isRegularFile(manifestPath)) {
                throw new IllegalStateException("image prompts manifest not found: " + manifestPath);
            }
            runtime.events().record(runtime.job(), PptJobEvent.of(
                    PptJobEventType.AGENT_MESSAGE,
                    "generating ai images from manifest",
                    Map.of("manifestPath", manifestPath.toString())));
            PptMasterCommandExecutor.CommandResult result = runtime.executor().runPythonScript(
                    IMAGE_GEN_SCRIPT,
                    List.of(
                            "--manifest", manifestPath.toString(),
                            "--output", manifestPath.getParent().toString()));
            if (!result.successful()) {
                throw new IllegalStateException("image generation failed: " + result.output());
            }
            return result.output().isBlank() ? "image generation completed" : result.output();
        }

        /**
         * 检查图片 manifest 的当前状态。
         * <p>
         * 读取 {@code images/image_prompts.json}，返回每个 item 的 filename、status、
         * 以及汇总计数（Pending / Generated / Failed / Needs-Manual）。
         * 用于在进阶流程中确认图片阶段是否可以进入下一步 SVG 生成。
         * </p>
         *
         * @param runtime 工具运行时上下文
         * @return manifest 状态汇总
         */
        @Tool(name = "inspect_image_manifest_status", description = "Read images/image_prompts.json and return item statuses plus summary counts.", readOnly = true)
        public Map<String, Object> inspectImageManifestStatus(PptAgentToolRuntime runtime) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            Path manifestPath = projectPath.resolve("images/image_prompts.json");
            if (!Files.isRegularFile(manifestPath)) {
                return Map.of(
                        "manifestPath", manifestPath.toString(),
                        "exists", false,
                        "summary", Map.of("total", 0, "pending", 0, "generated", 0, "failed", 0, "needsManual", 0),
                        "items", List.of());
            }
            try {
                String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                java.util.Map<String, Object> manifest = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
                Object itemsObj = manifest.get("items");
                List<?> items = itemsObj instanceof List<?> list ? list : List.of();
                int pending = 0;
                int generated = 0;
                int failed = 0;
                int needsManual = 0;
                List<Map<String, Object>> itemSummaries = new ArrayList<>();
                for (Object itemObj : items) {
                    if (!(itemObj instanceof Map<?, ?> raw)) {
                        continue;
                    }
                    Map<String, Object> item = raw.entrySet().stream()
                            .filter(e -> e.getKey() instanceof String)
                            .collect(LinkedHashMap::new,
                                    (m, e) -> m.put((String) e.getKey(), e.getValue()),
                                    Map::putAll);
                    String status = item.getOrDefault("status", "Unknown").toString();
                    switch (status) {
                        case "Pending" -> pending++;
                        case "Generated" -> generated++;
                        case "Failed" -> failed++;
                        case "Needs-Manual" -> needsManual++;
                        default -> {
                            // no-op
                        }
                    }
                    itemSummaries.add(Map.of(
                            "filename", item.getOrDefault("filename", ""),
                            "status", status,
                            "lastError", item.getOrDefault("last_error", "")));
                }
                Map<String, Object> summary = Map.of(
                        "total", items.size(),
                        "pending", pending,
                        "generated", generated,
                        "failed", failed,
                        "needsManual", needsManual);
                if (generated == items.size() && items.size() > 0) {
                    runtime.completeNode(PptJobNode.IMAGES_GENERATED, summary);
                }
                return Map.of(
                        "manifestPath", manifestPath.toString(),
                        "exists", true,
                        "summary", summary,
                        "items", itemSummaries);
            } catch (IOException ex) {
                throw new IllegalStateException("failed to read image manifest: " + manifestPath, ex);
            }
        }

        /**
         * 列出项目中指定目录下的所有相对文件路径。
         * <p>
         * 递归遍历指定子目录，收集所有文件的路径（相对于项目根目录）。
         * 如果目录不存在则返回空列表。
         * </p>
         *
         * @param projectPath   项目根目录的绝对路径
         * @param directoryName 子目录名称（如 sources、notes）
         * @return 文件中相对路径列表
         */
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

        /**
         * 解析可写的项目文件绝对路径。
         * <p>
         * 将相对路径解析为项目根目录下的绝对路径，并进行安全检查：
         * 防止路径穿越攻击（路径必须位于项目根目录范围内），
         * 并且只允许写入白名单中的路径（design_spec.md、spec_lock.md、
         * notes/*、svg_output/*、analysis/*）。
         * </p>
         *
         * @param runtime      工具运行时上下文
         * @param relativePath 相对于项目根目录的文件路径
         * @return 解析后的安全绝对路径
         * @throws IllegalStateException 当路径穿越或不在白名单中时抛出
         */
        private Path resolveWritableProjectFile(PptAgentToolRuntime runtime, String relativePath) {
            Path projectPath = runtime.job().projectPath()
                    .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
            Path target = projectPath.resolve(relativePath).normalize();
            if (!target.startsWith(projectPath)) {
                throw new IllegalStateException("relativePath escapes project: " + relativePath);
            }
            String normalized = projectPath.relativize(target).toString().replace('\\', '/');
            boolean allowed = normalized.equals("outline.json")
                    || normalized.equals("design_spec.md")
                    || normalized.equals("spec_lock.md")
                    || normalized.startsWith("notes/")
                    || normalized.startsWith("svg_output/")
                    || normalized.startsWith("analysis/")
                    || normalized.startsWith("images/");
            if (!allowed) {
                throw new IllegalStateException("writing this project path is not allowed: " + relativePath);
            }
            boolean downstreamArtifact = normalized.equals("design_spec.md")
                    || normalized.equals("spec_lock.md")
                    || normalized.startsWith("notes/")
                    || normalized.startsWith("svg_output/")
                    || normalized.startsWith("images/");
            if (downstreamArtifact) {
                requireApprovedOutline(runtime, relativePath);
            }
            return target;
        }

        private void requireApprovedOutline(PptAgentToolRuntime runtime, String operation) {
            PptNodeExecution outlineExecution = runtime.job().nodeExecution(PptJobNode.OUTLINE_CONFIRMED);
            PptNodeExecution legacyExecution = runtime.job().nodeExecution(PptJobNode.PLAN_CONFIRMED);
            boolean outlineApproved = outlineExecution != null
                    && outlineExecution.status() == PptJobNodeStatus.COMPLETED;
            boolean legacyOutlineApproved = legacyExecution != null
                    && legacyExecution.status() == PptJobNodeStatus.COMPLETED;
            if (outlineApproved) {
                Path projectPath = runtime.job().projectPath().orElseThrow();
                try {
                    if (!new PptOutlineStore().read(projectPath).locked()) {
                        throw new IllegalStateException("outline is not locked");
                    }
                } catch (IllegalStateException ex) {
                    throw new IllegalStateException("锁定大纲无效，禁止执行下游操作: " + operation, ex);
                }
            }
            if (!outlineApproved && !legacyOutlineApproved) {
                throw new IllegalStateException("逐页大纲尚未批准，禁止执行下游操作: " + operation);
            }
        }

        /**
         * 以 UTF-8 编码读取文本文件的全部内容。
         *
         * @param path 文件路径
         * @return 文件文本内容
         * @throws IllegalStateException 当读取失败时抛出
         */
        private String readText(Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("failed to read text file: " + path, ex);
            }
        }

        /**
         * 截断文本到指定长度。
         * <p>
         * 如果文本长度超过限制，则在截断处添加 "[truncated]" 标记。
         * </p>
         *
         * @param text  原始文本
         * @param limit 最大字符数
         * @return 截断后的文本
         */
        private String truncate(String text, int limit) {
            if (text.length() <= limit) {
                return text;
            }
            return text.substring(0, limit) + "\n...[truncated]";
        }

        /**
         * 检测 exports 目录中最新的 PPTX 导出文件。
         * <p>
         * 遍历 exports 目录下的所有 .pptx 文件，按最后修改时间排序，
         * 返回最新的文件。
         * </p>
         *
         * @param projectPath 项目根目录路径
         * @return 最新的 PPTX 文件路径
         * @throws IllegalStateException 当 exports 目录不存在或没有 PPTX 文件时抛出
         */
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

    /**
     * 检查当前 checkpoint 状态。
     * <p>
     * 返回项目中关键文件与目录的存在性，帮助 Agent 在恢复执行时快速判断
     * 从哪个节点继续，减少反复读取多个文件的调用次数。
     * </p>
     *
     * @param runtime 工具运行时上下文
     * @return checkpoint 状态汇总
     */
    @Tool(name = "inspect_checkpoint_status", description = "Inspect project checkpoint status: which key files/directories exist for resuming the workflow.", readOnly = true)
    public Map<String, Object> inspectCheckpointStatus(PptAgentToolRuntime runtime) {
        Path projectPath = runtime.job().projectPath()
                .orElseThrow(() -> new IllegalStateException("job project path is not prepared"));
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("projectPath", projectPath.toString());
        status.put("designSpecExists", fileExistsAndNonEmpty(projectPath, "design_spec.md"));
        status.put("specLockExists", fileExistsAndNonEmpty(projectPath, "spec_lock.md"));
        status.put("imageManifestExists", fileExistsAndNonEmpty(projectPath, "images/image_prompts.json"));
        status.put("notesTotalExists", fileExistsAndNonEmpty(projectPath, "notes/total.md"));
        status.put("svgOutputCount", countRelativeFiles(projectPath, "svg_output"));
        status.put("svgFinalCount", countRelativeFiles(projectPath, "svg_final"));
        status.put("exportFiles", listRelativeFilesForCheckpoint(projectPath, "exports"));
        status.put("lastCompletedNode", runtime.job().lastCompletedNode().map(Enum::name).orElse("none"));
        status.put("workflowMode", runtime.job().workflowMode().name());
        return status;
    }

    private List<String> listRelativeFilesForCheckpoint(Path projectPath, String directoryName) {
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
            return List.of();
        }
    }

    private boolean fileExistsAndNonEmpty(Path projectPath, String relativePath) {
        Path target = projectPath.resolve(relativePath).normalize();
        if (!target.startsWith(projectPath)) {
            return false;
        }
        try {
            return Files.isRegularFile(target) && Files.size(target) > 0;
        } catch (IOException ex) {
            return false;
        }
    }

    private int countRelativeFiles(Path projectPath, String directoryName) {
        Path directory = projectPath.resolve(directoryName);
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            return (int) stream.filter(Files::isRegularFile).count();
        } catch (IOException ex) {
            return 0;
        }
    }

    /**
     * 用户计划确认工具。
     * <p>
     * 继承自 {@link ToolBase}，用于在 PPT 生成工作流中请求用户确认
     * AI 代理生成的计划。该工具被标记为外部工具（externalTool=true），
     * AgentScope 框架在调用时会触发 {@link RequireExternalExecutionEvent}，
     * 暂停工作流等待用户通过外部接口（如 REST API）提交确认结果。
     * </p>
     * <p>输入参数：</p>
     * <ul>
     *   <li>planSummary — 计划摘要（必填）</li>
     *   <li>sourceFindings — 源文件分析结果</li>
     *   <li>pendingSteps — 待执行步骤（必填）</li>
     *   <li>risks — 风险评估</li>
     *   <li>stage — 确认阶段标识（可选），例如 {@code outline_confirmation}、{@code plan_confirmation}、
     *       {@code image_retry_decision}、{@code image_ready_continue_confirmation}</li>
     *   <li>title — 展示给用户的标题（可选）</li>
     *   <li>message — 展示给用户的核心说明（可选）</li>
     *   <li>choices — 建议操作列表（可选），每个条目包含 {@code label} 与 {@code value}</li>
     *   <li>contextData — 结构化上下文（可选），例如失败图片列表、lastError 摘要等</li>
     * </ul>
     */
    static final class UserPlanApprovalTool extends ToolBase {

        UserPlanApprovalTool() {
            super(ToolBase.builder()
                    .name(APPROVAL_TOOL_NAME)
                    .description("Request operator confirmation for the proposed PPT execution plan. "
                            + "For image-enhanced workflows, this tool is also used to pause after image "
                            + "generation failures so the operator can choose to retry failed images or continue.")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "planSummary", Map.of("type", "string"),
                                    "sourceFindings", Map.of("type", "string"),
                                    "pendingSteps", Map.of("type", "string"),
                                    "risks", Map.of("type", "string"),
                                    "stage", Map.of("type", "string"),
                                    "title", Map.of("type", "string"),
                                    "message", Map.of("type", "string"),
                                    "choices", Map.of(
                                            "type", "array",
                                            "items", Map.of(
                                                    "type", "object",
                                                    "properties", Map.of(
                                                            "label", Map.of("type", "string"),
                                                            "value", Map.of("type", "string")),
                                                    "required", List.of("label", "value"))),
                                    "contextData", Map.of(
                                            "type", "object",
                                            "description", "For outline_confirmation use {type: ppt_outline, version, locked:false, slides: [{slideNo, title, keyMessage, bullets, visualSuggestion, imageRequirement}]}")),
                            "required", List.of("planSummary", "pendingSteps")))
                    .readOnly(false)
                    .concurrencySafe(true)
                    .externalTool(true));
        }

    }
}
