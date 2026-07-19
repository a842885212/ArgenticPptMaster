package domi.argenticpptmaster.agent;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptConfirmation;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptNodeExecution;
import domi.argenticpptmaster.domain.PptOutline;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.service.PptWorkflowEvents;
import domi.argenticpptmaster.service.PptOutlineStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基于 AgentScope 的 PPT 代理运行器实现。
 * <p>
 * 实现 {@link PptAgentRunner} 接口，使用 AgentScope 框架驱动 AI 代理
 * 执行 PPT 生成工作流。主要职责包括：
 * </p>
 * <ul>
 *   <li>工作流启动（{@link #start(PptJob)}）和恢复执行（{@link #resume(PptJob, PptConfirmation)}）</li>
 *   <li>失败后从 checkpoint 恢复（{@link #resumeFromCheckpoint(PptJob, PptJobNode)}）</li>
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

    private static final Logger log = LoggerFactory.getLogger(AgentScopePptAgentRunner.class);

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
        log.info("ppt_agent_runner_start: jobId={}", job.id());
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
        log.info("ppt_agent_runner_resume: jobId={}, confirmationId={}",
                job.id(), confirmation.confirmationId());
        RuntimeContext context = effectiveRuntimeContext(job);
        List<ToolUseBlock> pendingToolCalls = pendingToolCalls(job);
        if (pendingToolCalls.isEmpty()) {
            log.warn("ppt_agent_runner_resume_no_pending_calls: jobId={}, confirmationId={}",
                    job.id(), confirmation.confirmationId());
            throw new IllegalStateException("job has no pending external tool calls to resume");
        }
        List<ToolResultBlock> results = pendingToolCalls.stream()
                .map(toolCall -> buildApprovedToolResult(toolCall, confirmation))
                .toList();
        List<Msg> input = List.of(new ToolResultMessage(results));
        runAgent(job, input, context);
    }

    /**
     * 从指定 checkpoint 节点继续执行失败后的 PPT 工作流。
     * <p>
     * 该方法会启动一次新的 AgentScope attempt session，并构建 checkpoint 恢复指令，
     * 明确告知 Agent 哪些节点已完成、应从哪个节点之后继续、以及不允许重写已完成节点。
     * </p>
     *
     * @param job        PPT 任务信息
     * @param checkpoint 恢复起点，即最近成功完成的业务节点
     */
    @Override
    public void resumeFromCheckpoint(PptJob job, PptJobNode checkpoint) {
        log.info("ppt_agent_runner_resume_from_checkpoint: jobId={}, checkpoint={}, attemptSessionId={}",
                job.id(), checkpoint.name(), job.activeAttemptSessionId());
        prepareProjectPath(job);
        RuntimeContext context = runtimeContextForCheckpoint(job);
        List<Msg> input = List.of(new UserMessage(buildCheckpointResumeInstruction(job, checkpoint)));
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
     * 根据当前 attempt 选择正确的 AgentScope 运行时上下文。
     * <p>
     * 初始 attempt（resumeCount == 0）使用原始 {@code jobId} session；
     * checkpoint 恢复后的 attempt（resumeCount > 0）使用 {@code jobId-attempt-N} session，
     * 确保确认恢复的回填与 checkpoint 恢复处于同一会话。
     * </p>
     *
     * @param job PPT 任务信息
     * @return AgentScope 运行时上下文
     */
    private RuntimeContext effectiveRuntimeContext(PptJob job) {
        if (job.resumeCount() > 0) {
            return runtimeContextForCheckpoint(job);
        }
        return runtimeContext(job);
    }

    /**
     * 为 checkpoint 恢复创建 AgentScope 运行时上下文。
     * <p>
     * 使用任务的 {@code activeAttemptSessionId} 作为 sessionId，确保失败后的恢复
     * 启用新的 attempt session，避免旧失败上下文污染。
     * </p>
     *
     * @param job PPT 任务信息
     * @return AgentScope 运行时上下文
     */
    private RuntimeContext runtimeContextForCheckpoint(PptJob job) {
        return RuntimeContext.builder()
                .userId(agentScopeProperties.serviceUserId())
                .sessionId(job.id() + "-" + job.activeAttemptSessionId())
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
        log.info("ppt_agent_runner_stream_begin: jobId={}, sessionId={}",
                job.id(), runtimeContext.getSessionId());
        AgentScopeWorkflowAgent workflowAgent = workflowAgentFactory.create(job);
        AgentRunState runState = new AgentRunState();
        workflowAgent.streamEvents(input, runtimeContext)
                .doOnNext(event -> handleEvent(job, runState, event))
                .blockLast();

        if (!runState.waitingForExternalExecution) {
            recoverWaitingConfirmationFromPersistedState(job, runState, runtimeContext.getSessionId());
        }
        log.info("ppt_agent_runner_stream_end: jobId={}, waitingForExternalExecution={}, exportPathPresent={}, status={}",
                job.id(), runState.waitingForExternalExecution, job.exportPath().isPresent(), job.status());
        if (runState.waitingForExternalExecution) {
            log.info("ppt_agent_runner_waiting_confirmation: jobId={}, confirmationId={}",
                    job.id(), job.currentConfirmationId().orElse("none"));
            return;
        }
        if (job.exportPath().isPresent()) {
            log.info("ppt_agent_runner_export_ready: jobId={}, fileName={}",
                    job.id(), job.exportPath().get().getFileName());
            return;
        }
        int finalTextLength = runState.finalResultText == null ? 0 : runState.finalResultText.length();
        log.warn("ppt_agent_runner_no_artifact: jobId={}, finalTextLength={}, status={}",
                job.id(), finalTextLength, job.status());
        throw new IllegalStateException("agent finished without export artifact");
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
            log.info("ppt_agent_runner_event_start: jobId={}, replyId={}",
                    job.id(), startEvent.getReplyId());
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
            log.debug("ppt_agent_runner_event_tool_call: jobId={}, replyId={}, toolCallId={}, toolName={}",
                    job.id(), toolCallEndEvent.getReplyId(), toolCallEndEvent.getToolCallId(),
                    toolCallEndEvent.getToolCallName());
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
            log.debug("ppt_agent_runner_event_tool_result: jobId={}, replyId={}, toolCallId={}, toolName={}, state={}",
                    job.id(), toolResultEndEvent.getReplyId(), toolResultEndEvent.getToolCallId(),
                    toolResultEndEvent.getToolCallName(), toolResultEndEvent.getState().getValue());
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
            log.info("ppt_agent_runner_event_require_external_execution: jobId={}, replyId={}, toolCallCount={}",
                    job.id(), externalExecutionEvent.getReplyId(), externalExecutionEvent.getToolCalls().size());
            runState.waitingForExternalExecution = markWaitingForConfirmation(
                    job,
                    externalExecutionEvent.getReplyId(),
                    externalExecutionEvent.getToolCalls());
            return;
        }
        if (event instanceof AgentResultEvent resultEvent) {
            runState.finalResultText = resultEvent.getResult().getTextContent();
            log.info("ppt_agent_runner_event_result: jobId={}, finalTextLength={}, generateReason={}",
                    job.id(), runState.finalResultText == null ? 0 : runState.finalResultText.length(),
                    resultEvent.getResult().getGenerateReason());
            if (resultEvent.getResult().getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
                List<ToolUseBlock> suspendedToolCalls = resultEvent.getResult().getContentBlocks(ToolUseBlock.class);
                log.info("ppt_agent_runner_event_result_suspended: jobId={}, toolCallCount={}",
                        job.id(), suspendedToolCalls.size());
                if (!suspendedToolCalls.isEmpty()) {
                    runState.waitingForExternalExecution = markWaitingForConfirmation(
                            job,
                            resultEvent.getResult().getId(),
                            suspendedToolCalls);
                    return;
                }
            }
            if (runState.finalResultText != null && !runState.finalResultText.isBlank()) {
                events.record(job, PptJobEvent.of(
                        PptJobEventType.AGENT_MESSAGE,
                        runState.finalResultText,
                        Map.of("kind", "final_result")));
            }
        }
    }

    /**
     * 从 AgentScope 持久化会话状态中恢复待确认的工具调用。
     * <p>
     * 某些外部工具挂起场景下，事件流里不会显式出现
     * {@link RequireExternalExecutionEvent} 或 {@link GenerateReason#TOOL_SUSPENDED}，
     * 但 AgentScope 已经把 pending tool call 和运行中的 tool result 写入了会话状态。
     * 此时通过读取持久化的 {@code agent_state}，仍然可以将任务恢复为
     * {@link domi.argenticpptmaster.domain.PptJobStatus#WAITING_CONFIRMATION}，避免误判失败。
     * </p>
     *
     * @param job      PPT 任务信息
     * @param runState 代理运行状态
     */
    private void recoverWaitingConfirmationFromPersistedState(PptJob job, AgentRunState runState, String sessionId) {
        if (job.exportPath().isPresent()) {
            log.info("ppt_agent_runner_skip_waiting_confirmation_recovery: jobId={}, status={}, reason=export_artifact_present",
                    job.id(), job.status());
            return;
        }
        loadPersistedPendingToolCalls(job, sessionId).ifPresent(toolCalls -> {
            if (!toolCalls.isEmpty()) {
                runState.waitingForExternalExecution = markWaitingForConfirmation(job, sessionId, toolCalls);
            }
        });
    }

    /**
     * 从 AgentScope 持久化状态中提取仍处于 pending 的工具调用。
     * <p>
     * AgentScope 对外部挂起工具的落盘形态有两种：
     * </p>
     * <ul>
     *   <li>只有 tool_use，尚无对应 tool_result</li>
     *   <li>已有同 id 的 {@link ToolResultBlock}，但其 {@link ToolResultState} 为 RUNNING</li>
     * </ul>
     * <p>
     * 同时，最后一条 assistant 消息未必仍然携带工具调用；Agent 可能继续输出一段
     * 纯文本总结。因此恢复时需要从最近的 assistant 消息中反向查找“最后一个带
     * tool_use 的 assistant 消息”，并把没有结果或结果仍为 RUNNING 的工具视为待确认。
     * </p>
     *
     * @param job PPT 任务信息
     * @return 待确认工具调用列表；若会话状态不存在或无法识别，则返回空 Optional
     */
    private java.util.Optional<List<ToolUseBlock>> loadPersistedPendingToolCalls(PptJob job, String sessionId) {
        try {
            JsonFileAgentStateStore stateStore = new JsonFileAgentStateStore(agentScopeProperties.sessionStorePath());
            return stateStore
                    .get(agentScopeProperties.serviceUserId(), sessionId, "agent_state", AgentState.class)
                    .map(this::extractPendingToolCallsFromState);
        } catch (RuntimeException ex) {
            log.warn("ppt_agent_runner_load_state_failed: jobId={}, sessionId={}", job.id(), sessionId, ex);
            return java.util.Optional.empty();
        }
    }

    /**
     * 按 AgentScope 的挂起规则，从持久化状态中提取待确认工具调用。
     *
     * @param state AgentScope 持久化状态
     * @return 待确认工具调用列表
     */
    private List<ToolUseBlock> extractPendingToolCallsFromState(AgentState state) {
        List<Msg> context = state.getContext();
        Msg lastAssistantWithToolUse = null;
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg message = context.get(i);
            if (message.getRole() == MsgRole.ASSISTANT && message.hasContentBlocks(ToolUseBlock.class)) {
                lastAssistantWithToolUse = message;
                break;
            }
        }
        if (lastAssistantWithToolUse == null) {
            return List.of();
        }
        java.util.Map<String, ToolResultBlock> latestToolResults = new java.util.LinkedHashMap<>();
        for (Msg message : context) {
            for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                if (result.getId() != null) {
                    latestToolResults.put(result.getId(), result);
                }
            }
        }
        return lastAssistantWithToolUse.getContentBlocks(ToolUseBlock.class).stream()
                .filter(toolUse -> isPendingToolUse(toolUse, latestToolResults))
                .toList();
    }

    /**
     * 判断工具调用是否仍处于待人工确认状态。
     * <p>
     * 若没有任何结果，或最近一次结果的状态仍为 {@link ToolResultState#RUNNING}，
     * 说明 Agent 仍在等待外部回填结果。
     * </p>
     *
     * @param toolUse           工具调用
     * @param latestToolResults 当前上下文中每个工具调用最新的结果
     * @return true 表示该工具仍待确认
     */
    private boolean isPendingToolUse(ToolUseBlock toolUse, java.util.Map<String, ToolResultBlock> latestToolResults) {
        ToolResultBlock result = latestToolResults.get(toolUse.getId());
        return result == null || result.getState() == null || result.getState() == ToolResultState.RUNNING;
    }

    /**
     * 将 Agent 的挂起工具调用转换为等待人工确认状态。
     * <p>
     * AgentScope RC3 既可能通过 {@link RequireExternalExecutionEvent} 暴露外部执行需求，
     * 也可能在 {@link AgentResultEvent} 中返回 {@link GenerateReason#TOOL_SUSPENDED}
     * 的结果消息。两种路径最终都统一落到任务确认载荷中，供前端展示并在恢复执行时回传。
     * </p>
     *
     * @param job       PPT 任务信息
     * @param replyId   当前回复 ID
     * @param toolCalls 挂起的工具调用列表
     */
    private boolean markWaitingForConfirmation(PptJob job, String replyId, List<ToolUseBlock> toolCalls) {
        if (job.exportPath().isPresent()) {
            log.warn("ppt_agent_runner_ignore_waiting_confirmation_after_export: jobId={}, status={}, toolCallCount={}",
                    job.id(), job.status(), toolCalls.size());
            return false;
        }
        log.info("ppt_agent_runner_waiting_confirmation_marked: jobId={}, toolCallCount={}",
                job.id(), toolCalls.size());
        String confirmationId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        String stage = "agentscope_external_execution";
        String message = "Agent 需要人工确认当前 PPT 执行方案后继续";
        if (!toolCalls.isEmpty()) {
            ToolUseBlock firstCall = toolCalls.get(0);
            payload.put("recommended", firstCall.getInput());
            payload.put("toolName", firstCall.getName());
            Map<String, Object> input = extractStringKeyMap(firstCall.getInput());
            Object requestedStage = input.get("stage");
            if (requestedStage instanceof String requestedStageStr && !requestedStageStr.isBlank()) {
                stage = requestedStageStr;
            }
            Object requestedMessage = input.get("message");
            if (requestedMessage instanceof String requestedMessageStr && !requestedMessageStr.isBlank()) {
                message = requestedMessageStr;
            }
            Object title = input.get("title");
            if (title instanceof String titleStr && !titleStr.isBlank()) {
                payload.put("title", titleStr);
            }
            Object choices = input.get("choices");
            if (choices instanceof List<?>) {
                payload.put("choices", choices);
            }
            Object contextData = input.get("contextData");
            if (!hasApprovedOutline(job) && !"outline_confirmation".equals(stage)
                    && !"plan_confirmation".equals(stage)) {
                throw new IllegalStateException(
                        "the first request_plan_confirmation must use stage=outline_confirmation with a structured outline");
            }
            if ("outline_confirmation".equals(stage) || "plan_confirmation".equals(stage)) {
                if (!(contextData instanceof Map<?, ?> contextMap)
                        || !"ppt_outline".equals(contextMap.get("type"))) {
                    throw new IllegalStateException(
                            "outline_confirmation must contain contextData.type=ppt_outline with structured slides");
                }
                validateOutlineContextData(contextMap);
                Map<String, Object> normalizedContext = new LinkedHashMap<>();
                contextMap.forEach((key, value) -> normalizedContext.put(String.valueOf(key), value));
                if ("outline_confirmation".equals(stage)) {
                    int version = contextMap.get("version") instanceof Number number ? number.intValue() : 1;
                    if (version <= 0) {
                        throw new IllegalStateException("outline version must be positive");
                    }
                    normalizedContext.put("version", version);
                    normalizedContext.put("locked", false);
                    PptOutline outline = PptOutline.fromPayload(version, contextMap.get("slides"));
                    job.projectPath().ifPresent(path -> {
                        PptOutlineStore outlineStore = new PptOutlineStore();
                        Path outlinePath = outlineStore.path(path);
                        PptOutline effectiveOutline = outline;
                        if (Files.isRegularFile(outlinePath)) {
                            PptOutline existing = outlineStore.read(path);
                            if (existing.locked() || version < existing.version()) {
                                throw new IllegalStateException("outline version is stale or already locked");
                            }
                            if (version == existing.version()) {
                                effectiveOutline = existing;
                            }
                        }
                        if (effectiveOutline == outline) {
                            outlineStore.write(path, outline);
                        }
                        PptOutline finalOutline = effectiveOutline;
                        normalizedContext.put("version", finalOutline.version());
                        normalizedContext.put("slides", finalOutline.slides().stream().map(slide -> {
                            Map<String, Object> value = new LinkedHashMap<>();
                            value.put("slideNo", slide.slideNo());
                            value.put("title", slide.title());
                            value.put("keyMessage", slide.keyMessage());
                            value.put("bullets", slide.bullets());
                            value.put("visualSuggestion", slide.visualSuggestion());
                            value.put("imageRequirement", slide.imageRequirement());
                            return value;
                        }).toList());
                        outlineStore.snapshot(path, finalOutline.version()).ifPresent(snapshot -> {
                            if (snapshot.parentVersion() != null) {
                                normalizedContext.put("parentVersion", snapshot.parentVersion());
                            }
                            if (snapshot.diff() != null) {
                                normalizedContext.put("diff", snapshot.diff());
                            }
                        });
                    });
                }
                payload.put("contextData", normalizedContext);
            } else if (contextData instanceof Map<?, ?> contextMap) {
                if (contextMap.get("slides") != null) {
                    validateOutlineContextData(contextMap);
                }
                payload.put("contextData", contextData);
            }
        }
        payload.put("stage", stage);
        payload.put("message", message);
        payload.put("replyId", replyId);
        payload.put("toolCalls", toolCalls.stream()
                .map(this::toToolCallPayload)
                .toList());
        payload.put("agent", Map.of(
                "framework", "AgentScope Java",
                "mode", "streamEvents + Human-in-the-Loop"));
        if ("outline_confirmation".equals(stage)) {
            job.waitNodeConfirmation(PptJobNode.OUTLINE_DRAFTED);
        }
        job.requireConfirmation(confirmationId, payload);
        events.record(job, PptJobEvent.of(
                PptJobEventType.CONFIRMATION_REQUIRED,
                "waiting for user confirmation",
                Map.of(
                        "confirmationId", confirmationId,
                        "confirmationPayload", payload,
                        "replyId", replyId,
                        "toolCallCount", toolCalls.size())));
        return true;
    }

    private boolean hasApprovedOutline(PptJob job) {
        return isNodeCompleted(job, PptJobNode.OUTLINE_CONFIRMED)
                || isNodeCompleted(job, PptJobNode.PLAN_CONFIRMED);
    }

    private boolean isNodeCompleted(PptJob job, PptJobNode node) {
        PptNodeExecution execution = job.nodeExecution(node);
        return execution != null && execution.status() == PptJobNodeStatus.COMPLETED;
    }

    private void validateOutlineContextData(Map<?, ?> contextData) {
        if (!"ppt_outline".equals(contextData.get("type"))) {
            throw new IllegalStateException("contextData.type must be ppt_outline");
        }
        Object slidesValue = contextData.get("slides");
        if (!(slidesValue instanceof List<?> slides) || slides.isEmpty()) {
            throw new IllegalStateException("ppt_outline contextData must contain slides");
        }
        int previousSlideNo = 0;
        for (Object slideValue : slides) {
            if (!(slideValue instanceof Map<?, ?> slide)) {
                throw new IllegalStateException("ppt_outline slide must be an object");
            }
            Object slideNoValue = slide.get("slideNo");
            if (!(slideNoValue instanceof Number number)
                    || number.doubleValue() != number.intValue()
                    || number.intValue() <= previousSlideNo) {
                throw new IllegalStateException("ppt_outline slideNo values must be positive and ordered");
            }
            if (isBlankString(slide.get("title"))
                    || isBlankString(slide.get("keyMessage"))
                    || !(slide.get("bullets") instanceof List<?> bullets)
                    || bullets.isEmpty()
                    || bullets.stream().anyMatch(this::isBlankString)
                    || isBlankString(slide.get("visualSuggestion"))) {
                throw new IllegalStateException("ppt_outline slide is missing required fields");
            }
            previousSlideNo = number.intValue();
        }
    }

    private boolean isBlankString(Object value) {
        return !(value instanceof String string) || string.isBlank();
    }

    /**
     * 将工具输入 Map 过滤为仅保留 String 键的 Map。
     * <p>
     * AgentScope 工具输入在 JSON 解析后键通常为 String，但类型擦除后编译器无法保证。
     * 该方法用于安全地提取 stage、title、message 等字符串键字段。
     * </p>
     *
     * @param input 工具输入 Map
     * @return 仅包含 String 键的 Map
     */
    private Map<String, Object> extractStringKeyMap(Map<String, Object> input) {
        if (input == null) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
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
        builder.append("Operator action: ").append(confirmation.action() == null ? "APPROVE" : confirmation.action())
                .append(" for tool: ").append(toolCall.getName()).append('\n');
        Map<String, Object> input = extractStringKeyMap(toolCall.getInput());
        Object stage = input.get("stage");
        if (stage instanceof String stageStr && !stageStr.isBlank()) {
            builder.append("confirmation_stage=").append(stageStr).append('\n');
        }
        Object title = input.get("title");
        if (title instanceof String titleStr && !titleStr.isBlank()) {
            builder.append("confirmation_title=").append(titleStr).append('\n');
        }
        if (!confirmation.answers().isEmpty()) {
            builder.append("answers=").append(confirmation.answers()).append('\n');
        }
        if (confirmation.comment() != null && !confirmation.comment().isBlank()) {
            builder.append("operator_action=").append(confirmation.comment()).append('\n');
        }
        if (confirmation.overallComment() != null && !confirmation.overallComment().isBlank()) {
            builder.append("outline_overall_comment=").append(confirmation.overallComment()).append('\n');
        }
        if (!confirmation.slideEdits().isEmpty()) {
            builder.append("outline_slide_edits=").append(confirmation.slideEdits()).append('\n');
        }
        if (confirmation.outlineVersion() != null) {
            builder.append("outline_version=").append(confirmation.outlineVersion()).append('\n');
        }
        if (!confirmation.outlineEdits().isEmpty()) {
            builder.append("outline_edits=").append(confirmation.outlineEdits()).append('\n');
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
        String workflowSteps = job.workflowMode() == PptWorkflowMode.IMAGE_ENHANCED
                ? """
                3. 完成资料分析后，先生成逐页大纲并写入 outline.json（version 从 1 开始、locked=false），通过 request_plan_confirmation（stage="outline_confirmation"）在 contextData 中返回 {type:"ppt_outline", version, locked:false, slides:[...]}。
                4. 收到 REQUEST_REVISION 时，读取 Operator 的整体意见、outline_slide_edits 和 outline_edits，重生成完整的新版本大纲并再次请求 outline_confirmation；在 APPROVE 前不得写 design_spec.md、spec_lock.md、notes 或 svg。
                5. 用户批准后，先产出 design_spec.md 与 spec_lock.md。
                6. 调用 derive_image_manifest_from_locked_outline 从锁定大纲派生 images/image_prompts.json；调用 request_plan_confirmation（stage="image_manifest_confirmation"）展示清单。只有 APPROVE 后才调用 generate_project_images；收到 REQUEST_REVISION 时保留锁定大纲版本，重新派生未生成清单并再次请求确认。
                7. 调用 inspect_image_manifest_status 检查图片状态：
                   - 若有 Failed：不要结束任务，不要输出总结；调用 request_plan_confirmation（stage="image_retry_decision"）询问用户是否重试失败图片，用户要求重试后再调用 generate_project_images，循环直到全部 Generated。
                   - 若全部 Generated：调用 request_plan_confirmation（stage="image_ready_continue_confirmation"）询问用户是否继续后续 PPT 制作（notes、SVG、finalize、导出），只有用户确认继续后才进入下一步。
                8. 图片就绪且用户确认继续后，再写 notes/total.md 与 svg_output/*.svg；svg_output 引用图片时必须使用真实存在于 images/ 的文件。
                9. 生成 svg_output 后，调用 validate_svg_output，再 finalize 和导出。
                """
                : """
                3. 完成资料分析后，先生成逐页大纲并写入 outline.json（version 从 1 开始、locked=false），通过 request_plan_confirmation（stage="outline_confirmation"）在 contextData 中返回 {type:"ppt_outline", version, locked:false, slides:[...]}。
                4. 收到 REQUEST_REVISION 时，读取 Operator 的整体意见、outline_slide_edits 和 outline_edits，重生成完整的新版本大纲并再次请求 outline_confirmation；在 APPROVE 前不得写 design_spec.md、spec_lock.md、notes 或 svg。
                5. 用户批准后，再继续生成、校验、finalize 和导出。
                """;
        return """
                请为当前 PPT 任务建立 ppt-master 工作区，并按 markdown 路线推进：
                1. 导入上传材料并确认 sources/ 与 analysis/ 的真实内容。
                2. 基于 markdown 材料提出页数、结构、风险与执行计划。
                %s

                任务上下文：
                - jobId: %s
                - projectName: %s
                - format: %s
                - workflowMode: %s
                - sourceCount: %d
                - instruction: %s
                """.formatted(
                workflowSteps,
                job.id(),
                job.projectName(),
                job.format(),
                job.workflowMode().name(),
                job.sourceFiles().size(),
                job.instruction() == null ? "" : job.instruction());
    }

    /**
     * 构建从 checkpoint 恢复时的用户指令文本。
     * <p>
     * 该指令会明确告知 Agent：
     * </p>
     * <ol>
     *   <li>当前任务模式与最近成功完成的节点</li>
     *   <li>本次恢复允许推进的后续节点范围</li>
     *   <li>已完成节点默认不可重写，除非发现证据缺失需先报告</li>
     *   <li>恢复后首先调用 inspect_checkpoint_status 确认项目现状</li>
     * </ol>
     *
     * @param job        PPT 任务信息
     * @param checkpoint 恢复起点
     * @return checkpoint 恢复指令文本
     */
    private String buildCheckpointResumeInstruction(PptJob job, PptJobNode checkpoint) {
        List<PptJobNode> completedNodes = collectCompletedNodes(job, checkpoint);
        List<PptJobNode> allowedNextNodes = collectAllowedNextNodes(job, checkpoint);
        return """
                当前 PPT 任务正在从上一个成功节点恢复执行，请严格按照以下要求继续：

                1. 先调用 inspect_checkpoint_status 确认项目当前真实状态。
                2. 以下节点已被记录为已完成，默认不要重写，除非 inspect_checkpoint_status 显示关键证据缺失：
                   %s
                3. 你本次只需要推进以下后续节点：
                   %s
                4. 当前工作流模式：%s。
                5. 恢复起点：%s。
                6. 如果发现 checkpoint 与真实文件状态不一致，请先说明不一致之处，不要强行继续。

                任务上下文：
                - jobId: %s
                - projectName: %s
                - format: %s
                - workflowMode: %s
                - sourceCount: %d
                - instruction: %s
                """.formatted(
                formatNodeList(completedNodes),
                formatNodeList(allowedNextNodes),
                job.workflowMode().name(),
                checkpoint.name(),
                job.id(),
                job.projectName(),
                job.format(),
                job.workflowMode().name(),
                job.sourceFiles().size(),
                job.instruction() == null ? "" : job.instruction());
    }

    /**
     * 收集从起点到 checkpoint（含）之间所有应被视为已完成的节点。
     *
     * @param job        PPT 任务
     * @param checkpoint 恢复起点
     * @return 已完成节点列表
     */
    private List<PptJobNode> collectCompletedNodes(PptJob job, PptJobNode checkpoint) {
        List<PptJobNode> result = new ArrayList<>();
        for (PptJobNode node : PptJobNode.values()) {
            if (!isActiveWorkflowNode(job, node) || !node.applicableTo(job.workflowMode())) {
                continue;
            }
            result.add(node);
            if (node == checkpoint) {
                break;
            }
        }
        return result;
    }

    /**
     * 收集 checkpoint 之后允许推进的节点。
     *
     * @param job        PPT 任务
     * @param checkpoint 恢复起点
     * @return 允许推进的后续节点列表
     */
    private List<PptJobNode> collectAllowedNextNodes(PptJob job, PptJobNode checkpoint) {
        List<PptJobNode> result = new ArrayList<>();
        boolean afterCheckpoint = false;
        for (PptJobNode node : PptJobNode.values()) {
            if (!isActiveWorkflowNode(job, node) || !node.applicableTo(job.workflowMode())) {
                continue;
            }
            if (afterCheckpoint) {
                result.add(node);
            }
            if (node == checkpoint) {
                afterCheckpoint = true;
            }
        }
        return result;
    }

    /**
     * 判断节点是否属于当前任务的主流程。
     * <p>
     * {@code PLAN_CONFIRMED} 仅为旧版整体计划确认保留。新任务使用大纲节点，
     * 存量任务则依据历史确认载荷继续使用旧节点，避免两套阶段在恢复提示中交叉出现。
     * </p>
     */
    private boolean isActiveWorkflowNode(PptJob job, PptJobNode node) {
        boolean legacyPlanFlow = "plan_confirmation".equals(job.confirmationPayload().get("stage"))
                || isNodeCompleted(job, PptJobNode.PLAN_CONFIRMED);
        if (legacyPlanFlow) {
            return node != PptJobNode.OUTLINE_DRAFTED && node != PptJobNode.OUTLINE_CONFIRMED;
        }
        return node != PptJobNode.PLAN_CONFIRMED;
    }

    private String formatNodeList(List<PptJobNode> nodes) {
        if (nodes.isEmpty()) {
            return "（无）";
        }
        return nodes.stream()
                .map(PptJobNode::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("（无）");
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
