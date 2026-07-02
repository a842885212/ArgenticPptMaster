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

    @Override
    public void start(PptJob job) {
        prepareProjectPath(job);
        RuntimeContext context = runtimeContext(job);
        List<Msg> input = List.of(new UserMessage(buildInitialInstruction(job)));
        runAgent(job, input, context);
    }

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

    private RuntimeContext runtimeContext(PptJob job) {
        return RuntimeContext.builder()
                .userId(agentScopeProperties.serviceUserId())
                .sessionId(job.id().toString())
                .build();
    }

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

    private Map<String, Object> toToolCallPayload(ToolUseBlock toolCall) {
        return Map.of(
                "id", toolCall.getId(),
                "name", toolCall.getName(),
                "input", toolCall.getInput());
    }

    @SuppressWarnings("unchecked")
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

    private ToolResultBlock buildApprovedToolResult(ToolUseBlock toolCall, PptConfirmation confirmation) {
        String output = buildApprovalOutput(confirmation, toolCall);
        return ToolResultBlock.builder()
                .id(toolCall.getId())
                .name(toolCall.getName())
                .output(List.of(TextBlock.builder().text(output).build()))
                .state(ToolResultState.SUCCESS)
                .build();
    }

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

    private static final class AgentRunState {
        private boolean waitingForExternalExecution;
        private String finalResultText;
    }
}
