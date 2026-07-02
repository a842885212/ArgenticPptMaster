package domi.argenticpptmaster.agent;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptConfirmation;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.service.PptWorkflowEvents;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 基于 AgentScope 的 PPT 代理运行器实现。
 * <p>
 * 实现 {@link PptAgentRunner} 接口，使用 AgentScope 框架驱动 AI 代理
 * 执行 PPT 生成工作流。主要职责包括：
 * </p>
 * <ul>
 *   <li>工作流启动（{@link #start(PptJob)}）和恢复执行（{@link #resume(PptJob, PptConfirmation)}）</li>
 *   <li>管理 AgentScope 事件流的订阅与事件分发</li>
 *   <li>将 AgentScope 内部事件转换为统一的 {@link PptJobEvent} 事件并记录</li>
 *   <li>处理外部工具执行（等待用户确认）等异步交互场景</li>
 * </ul>
 * <p>
 * 内部维护 {@link AgentRunState} 记录代理运行时状态，包括是否需要等待
 * 用户确认以及代理最终输出文本。
 * </p>
 */
@Component
public class AgentScopePptAgentRunner implements PptAgentRunner {

    private final PptMasterProperties properties;
    private final AgentScopeProperties agentScopeProperties;
    private final AgentScopeWorkflowAgentFactory workflowAgentFactory;
    private final PptWorkflowEvents events;

    public AgentScopePptAgentRunner(
            PptMasterProperties properties,
            AgentScopeProperties agentScopeProperties,
            AgentScopeWorkflowAgentFactory workflowAgentFactory,
            PptWorkflowEvents events) {
        this.properties = properties;
        this.agentScopeProperties = agentScopeProperties;
        this.workflowAgentFactory = workflowAgentFactory;
        this.events = events;
    }

    /**
     * 启动 PPT 生成工作流。
     * <p>
     * 准备项目路径，创建 AgentScope 运行时上下文，构建初始指令消息，
     * 然后驱动 AI 代理执行 PPT 生成任务。
     * </p>
     *
     * @param job PPT 任务信息
     */
    @Override
    public void start(PptJob job) {
        prepareProjectPath(job);
        RuntimeContext context = runtimeContext(job);
        List<Msg> input = List.of(new UserMessage(buildInitialInstruction(job)));
        runAgent(job, input, context);
    }

    /**
     * 从中断点恢复 PPT 工作流的执行。
     * <p>
     * 当工作流因等待用户确认而暂停后，调用此方法恢复执行。
     * 恢复时读取已保存的待处理工具调用，将用户的确认结果
     * 作为工具结果消息回传给 AI 代理，使其继续执行。
     * </p>
     *
     * @param job          PPT 任务信息，包含恢复所需的上下文状态
     * @param confirmation 用户的确认结果
     * @throws IllegalStateException 当没有待处理的工具调用时抛出
     */
    @Override
    public void resume(PptJob job, PptConfirmation confirmation) {
        RuntimeContext context = runtimeContext(job);
        List<ToolUseBlock> pendingToolCalls = pendingToolCalls(job);
        if (pendingToolCalls.isEmpty()) {
            throw new IllegalStateException("job has no pending external tool calls to resume");
        }
        List<ToolResultBlock> results = pendingToolCalls.stream()
                .map(toolCall -> buildApprovedToolResult(toolCall, confirmation))
                .toList();
        List<Msg> input = List.of(new ToolResultMessage(results));
        runAgent(job, input, context);
    }

    /**
     * 准备项目路径。
     * <p>
     * 如果任务尚未设置项目路径，则根据项目名称、任务 ID 和当前日期
     * 在工作区中生成唯一的项目目录路径，并更新任务状态。
     * 包含路径穿越安全检查。
     * </p>
     *
     * @param job PPT 任务信息
     */
    private void prepareProjectPath(PptJob job) {
        if (job.projectPath().isPresent()) {
            return;
        }
        String projectSlug = job.projectName() + "_" + job.id().toString().substring(0, 8);
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Path projectsDir = properties.workspacePath()
                .resolve("projects")
                .toAbsolutePath()
                .normalize();
        Path projectPath = projectsDir
                .resolve(projectSlug + "_" + job.format() + "_" + date)
                .toAbsolutePath()
                .normalize();
        if (!projectPath.startsWith(projectsDir)) {
            throw new IllegalStateException("project path escapes workspace: " + projectPath);
        }
        job.prepareProject(projectPath);
        events.record(job, PptJobEvent.of(
                PptJobEventType.PROJECT_PREPARED,
                "ppt-master project path prepared",
                Map.of("projectName", projectPath.getFileName().toString())));
    }

    /**
     * 创建 AgentScope 运行时上下文。
     * <p>
     * 基于配置的服务用户 ID 和任务 ID 构建 AgentScope 的运行时上下文，
     * 用于标识会话和用户信息。
     * </p>
     *
     * @param job PPT 任务信息
     * @return AgentScope 运行时上下文
     */
    private RuntimeContext runtimeContext(PptJob job) {
        return RuntimeContext.builder()
                .userId(agentScopeProperties.serviceUserId())
                .sessionId(job.id().toString())
                .build();
    }

    /**
     * 运行 AI 代理并处理事件流。
     * <p>
     * 创建工作流代理实例，订阅其事件流并将每个事件分发给
     * {@link #handleEvent} 方法处理。事件流结束后，检查代理的结束状态：
     * 如果等待外部执行（用户确认）或已导出成品，则正常返回；
     * 否则抛出异常表示代理异常终止。
     * </p>
     *
     * @param job            PPT 任务信息
     * @param input          输入消息列表
     * @param runtimeContext AgentScope 运行时上下文
     * @throws IllegalStateException 当代理结束但未导出成品时抛出
     */
    private void runAgent(PptJob job, List<Msg> input, RuntimeContext runtimeContext) {
        AgentScopeWorkflowAgent workflowAgent = workflowAgentFactory.create(job);
        AgentRunState runState = new AgentRunState();
        workflowAgent.streamEvents(input, runtimeContext)
                .doOnNext(event -> handleEvent(job, runState, event))
                .blockLast();

        if (runState.waitingForExternalExecution) {
            return;
        }
        if (job.exportPath().isPresent()) {
            return;
        }
        String finalText = runState.finalResultText == null ? "" : runState.finalResultText.trim();
        throw new IllegalStateException(finalText.isBlank()
                ? "agent finished without export artifact"
                : "agent finished without export artifact: " + finalText);
    }

    /**
     * 处理 AgentScope 事件并记录工作流事件。
     * <p>
     * 根据事件类型执行不同的处理逻辑：
     * </p>
     * <ul>
     *   <li>{@link AgentStartEvent} — 标记代理已启动，记录 replyId</li>
     *   <li>{@link TextBlockDeltaEvent} — 提取文本增量，发布代理消息事件</li>
     *   <li>{@link ToolCallEndEvent} — 记录工具调用准备完成事件</li>
     *   <li>{@link ToolResultEndEvent} — 记录工具执行结果事件</li>
     *   <li>{@link RequireExternalExecutionEvent} — 设置等待用户确认状态，
     *       生成确认 ID 和载荷，发布 {@link PptJobEventType#CONFIRMATION_REQUIRED} 事件</li>
     *   <li>{@link AgentResultEvent} — 保存代理最终输出文本</li>
     * </ul>
     *
     * @param job      PPT 任务信息
     * @param runState 代理运行状态
     * @param event    AgentScope 事件
     */
    private void handleEvent(PptJob job, AgentRunState runState, AgentEvent event) {
        if (event instanceof AgentStartEvent startEvent) {
            job.startAgent();
            events.record(job, PptJobEvent.of(
                    PptJobEventType.AGENT_STARTED,
                    "AgentScope stream started",
                    Map.of("replyId", startEvent.getReplyId())));
            return;
        }
        if (event instanceof TextBlockDeltaEvent deltaEvent) {
            if (!deltaEvent.getDelta().isBlank()) {
                events.record(job, PptJobEvent.of(
                        PptJobEventType.AGENT_MESSAGE,
                        deltaEvent.getDelta(),
                        Map.of("replyId", deltaEvent.getReplyId(), "kind", "text")));
            }
            return;
        }
        if (event instanceof ToolCallEndEvent toolCallEndEvent) {
            events.record(job, PptJobEvent.of(
                    PptJobEventType.AGENT_MESSAGE,
                    "tool prepared: " + toolCallEndEvent.getToolCallName(),
                    Map.of(
                            "replyId", toolCallEndEvent.getReplyId(),
                            "toolCallId", toolCallEndEvent.getToolCallId(),
                            "toolName", toolCallEndEvent.getToolCallName(),
                            "kind", "tool_call")));
            return;
        }
        if (event instanceof ToolResultEndEvent toolResultEndEvent) {
            events.record(job, PptJobEvent.of(
                    PptJobEventType.AGENT_MESSAGE,
                    "tool finished: " + toolResultEndEvent.getToolCallName(),
                    Map.of(
                            "replyId", toolResultEndEvent.getReplyId(),
                            "toolCallId", toolResultEndEvent.getToolCallId(),
                            "toolName", toolResultEndEvent.getToolCallName(),
                            "state", toolResultEndEvent.getState().getValue(),
                            "kind", "tool_result")));
            return;
        }
        if (event instanceof RequireExternalExecutionEvent externalExecutionEvent) {
            runState.waitingForExternalExecution = true;
            String confirmationId = UUID.randomUUID().toString();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stage", "agentscope_external_execution");
            payload.put("message", "Agent 需要人工确认当前 PPT 执行方案后继续");
            payload.put("replyId", externalExecutionEvent.getReplyId());
            payload.put("toolCalls", externalExecutionEvent.getToolCalls().stream()
                    .map(this::toToolCallPayload)
                    .toList());
            payload.put("agent", Map.of(
                    "framework", "AgentScope Java",
                    "mode", "streamEvents + Human-in-the-Loop"));
            if (!externalExecutionEvent.getToolCalls().isEmpty()) {
                ToolUseBlock firstCall = externalExecutionEvent.getToolCalls().get(0);
                payload.put("recommended", firstCall.getInput());
                payload.put("toolName", firstCall.getName());
            }
            job.requireConfirmation(confirmationId, payload);
            events.record(job, PptJobEvent.of(
                    PptJobEventType.CONFIRMATION_REQUIRED,
                    "waiting for user confirmation",
                    Map.of(
                            "confirmationId", confirmationId,
                            "replyId", externalExecutionEvent.getReplyId(),
                            "toolCallCount", externalExecutionEvent.getToolCalls().size())));
            return;
        }
        if (event instanceof AgentResultEvent resultEvent) {
            runState.finalResultText = resultEvent.getResult().getTextContent();
            if (runState.finalResultText != null && !runState.finalResultText.isBlank()) {
                events.record(job, PptJobEvent.of(
                        PptJobEventType.AGENT_MESSAGE,
                        runState.finalResultText,
                        Map.of("kind", "final_result")));
            }
        }
    }

    /**
     * 将 AgentScope 工具调用转换为统一的载荷 Map。
     * <p>
     * 提取工具调用的 ID、名称和输入参数，构建可供确认流程使用的
     * 工具调用载荷。
     * </p>
     *
     * @param toolCall AgentScope 工具使用块
     * @return 包含 id、name 和 input 的 Map
     */
    private Map<String, Object> toToolCallPayload(ToolUseBlock toolCall) {
        return Map.of(
                "id", toolCall.getId(),
                "name", toolCall.getName(),
                "input", toolCall.getInput());
    }

    /**
     * 获取任务中待处理的工具调用列表。
     * <p>
     * 从任务的确认载荷中提取工具调用信息，转换为 AgentScope 的
     * {@link ToolUseBlock} 列表，用于恢复工作流时回传给 AI 代理。
     * </p>
     *
     * @param job PPT 任务信息
     * @return 待处理的工具使用块列表
     */
    private List<ToolUseBlock> pendingToolCalls(PptJob job) {
        Object toolCalls = job.confirmationPayload().get("toolCalls");
        if (!(toolCalls instanceof List<?> entries)) {
            return List.of();
        }
        return entries.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::toToolUseBlock)
                .toList();
    }

    /**
     * 将载荷 Map 转换为 AgentScope 工具使用块。
     * <p>
     * 从确认载荷中的工具调用 Map 中提取 id、name 和 input 字段，
     * 构建 {@link ToolUseBlock} 实例。包含字段类型验证和参数规范化。
     * </p>
     *
     * @param payload 工具调用载荷 Map
     * @return AgentScope 工具使用块
     * @throws IllegalStateException 当载荷格式无效时抛出
     */
    private ToolUseBlock toToolUseBlock(Map<?, ?> payload) {
        Object id = payload.get("id");
        Object name = payload.get("name");
        Object input = payload.get("input");
        if (!(id instanceof String toolCallId) || !(name instanceof String toolName) || !(input instanceof Map<?, ?> inputMap)) {
            throw new IllegalStateException("invalid tool call payload: " + payload);
        }
        Map<String, Object> normalizedInput = inputMap.entrySet().stream()
                .filter(entry -> entry.getKey() instanceof String)
                .collect(LinkedHashMap::new,
                        (map, entry) -> map.put((String) entry.getKey(), entry.getValue()),
                        Map::putAll);
        return new ToolUseBlock(toolCallId, toolName, normalizedInput);
    }

    /**
     * 构建已批准的工具结果块。
     * <p>
     * 根据用户的确认结果构建 AgentScope 工具结果块，包含确认输出文本。
     * 工具结果状态标记为 {@link ToolResultState#SUCCESS}。
     * </p>
     *
     * @param toolCall     工具使用块
     * @param confirmation 用户的确认结果
     * @return AgentScope 工具结果块
     */
    private ToolResultBlock buildApprovedToolResult(ToolUseBlock toolCall, PptConfirmation confirmation) {
        String output = buildApprovalOutput(confirmation, toolCall);
        return ToolResultBlock.builder()
                .id(toolCall.getId())
                .name(toolCall.getName())
                .output(List.of(TextBlock.builder().text(output).build()))
                .state(ToolResultState.SUCCESS)
                .build();
    }

    /**
     * 构建确认输出文本。
     * <p>
     * 根据用户的确认结果生成对应的文本输出，包含工具名称、用户答案、
     * 评论和原始输入，用于告知 AI 代理用户的确认结果。
     * </p>
     *
     * @param confirmation 用户的确认结果
     * @param toolCall     工具使用块
     * @return 确认输出文本
     */
    private String buildApprovalOutput(PptConfirmation confirmation, ToolUseBlock toolCall) {
        StringBuilder builder = new StringBuilder();
        builder.append("Operator approved tool: ").append(toolCall.getName()).append('\n');
        if (!confirmation.answers().isEmpty()) {
            builder.append("answers=").append(confirmation.answers()).append('\n');
        }
        if (confirmation.comment() != null && !confirmation.comment().isBlank()) {
            builder.append("comment=").append(confirmation.comment()).append('\n');
        }
        builder.append("original_input=").append(toolCall.getInput());
        return builder.toString();
    }

    /**
     * 构建初始指令文本。
     * <p>
     * 在工作流启动时生成发送给 AI 代理的初始用户指令，
     * 包含任务上下文信息（jobId、项目名称、格式、源文件数量等），
     * 指示代理按 markdown 路线推进 PPT 生成。
     * </p>
     *
     * @param job PPT 任务信息
     * @return 初始指令文本
     */
    private String buildInitialInstruction(PptJob job) {
        return """
                请为当前 PPT 任务建立 ppt-master 工作区，并按 markdown 路线推进：
                1. 导入上传材料并确认 sources/ 与 analysis/ 的真实内容。
                2. 基于 markdown 材料提出页数、结构、风险与执行计划。
                3. 在写 design_spec.md、spec_lock.md、notes/total.md、svg_output/*.svg 之前，必须调用 request_plan_confirmation 请求人工确认。
                4. 用户确认后，再继续生成、校验、finalize 和导出。
                
                任务上下文：
                - jobId: %s
                - projectName: %s
                - format: %s
                - sourceCount: %d
                - instruction: %s
                """.formatted(
                job.id(),
                job.projectName(),
                job.format(),
                job.sourceFiles().size(),
                job.instruction() == null ? "" : job.instruction());
    }

    /**
     * 代理运行时状态记录。
     * <p>
     * 保存 AI 代理执行过程中的运行时状态，包括：
     * </p>
     * <ul>
     *   <li>{@link #waitingForExternalExecution} — 是否需要等待用户确认</li>
     *   <li>{@link #finalResultText} — 代理的最终输出文本</li>
     * </ul>
     */
    private static final class AgentRunState {
        private boolean waitingForExternalExecution;
        private String finalResultText;
    }
}
