package domi.argenticpptmaster.agent;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptConfirmation;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.service.PptJobEventPublisher;
import domi.argenticpptmaster.service.PptWorkflowEvents;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * {@link AgentScopePptAgentRunner} 的单元测试。
 * <p>
 * 验证 Agent 启动流程能够正确触发待确认状态，
 * 以及恢复（resume）流程能够将人工审批结果以工具调用结果消息（ToolResultMessage）
 * 的形式传递给 Agent 继续执行。
 */
class AgentScopePptAgentRunnerTests {

    /**
     * 验证启动 Agent 后，当 Agent 发出 RequireExternalExecutionEvent 事件时，
     * Job 状态正确转换为 {@link PptJobStatus#WAITING_CONFIRMATION}，
     * 且确认 ID、回复 ID、工具名称等确认载荷信息正确保存。
     */
    @Test
    void startTransitionsToWaitingConfirmationOnExternalExecution() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-1",
                        List.of(new ToolUseBlock(
                                "call-1",
                                "request_plan_confirmation",
                                Map.of("planSummary", "import first", "pendingSteps", "confirm plan"))))));

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                agentScopeProperties(),
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();

        runner.start(job);

        assertThat(job.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        assertThat(job.currentConfirmationId()).isPresent();
        assertThat(job.confirmationPayload()).containsEntry("replyId", "reply-1");
        assertThat(job.confirmationPayload()).containsEntry("toolName", "request_plan_confirmation");
        assertThat(job.events()).isNotEmpty();
        assertThat(job.events().get(job.events().size() - 1).data())
                .containsKey("confirmationPayload")
                .containsEntry("confirmationId", job.currentConfirmationId().orElseThrow());
        assertThat(factory.contexts()).hasSize(1);
        assertThat(factory.contexts().get(0).getSessionId()).isEqualTo(job.id().toString());
    }

    /**
     * 验证在人工确认后调用 resume()，Agent 收到的恢复消息是
     * {@link ToolResultMessage} 类型，其中包含之前暂停时使用的工具名称
     * 和人工审批备注，并且 Job 状态重新进入等待确认状态以处理下一阶段。
     */
    @Test
    void resumeUsesToolResultMessageForHumanInTheLoopContinuation() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-1",
                        List.of(new ToolUseBlock(
                                "call-1",
                                "request_plan_confirmation",
                                Map.of("planSummary", "phase one", "pendingSteps", "confirm"))))));
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-2", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-2",
                        List.of(new ToolUseBlock(
                                "call-2",
                                "request_plan_confirmation",
                                Map.of("planSummary", "phase two", "pendingSteps", "confirm export"))))));

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                agentScopeProperties(),
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();

        runner.start(job);
        PptConfirmation confirmation = new PptConfirmation(
                job.currentConfirmationId().orElseThrow(),
                true,
                Map.of("approvedPlan", "continue"),
                "operator approved",
                Instant.now());
        job.receiveConfirmation(confirmation);

        runner.resume(job, confirmation);

        assertThat(factory.messages()).hasSize(2);
        Msg resumeMessage = factory.messages().get(1).get(0);
        assertThat(resumeMessage).isInstanceOf(ToolResultMessage.class);
        List<ToolResultBlock> blocks = resumeMessage.getContentBlocks(ToolResultBlock.class);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getName()).isEqualTo("request_plan_confirmation");
        assertThat(((TextBlock) blocks.get(0).getOutput().get(0)).getText()).contains("operator approved");
        assertThat(job.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        assertThat(job.confirmationPayload()).containsEntry("replyId", "reply-2");
    }

    /**
     * 验证当 AgentScope RC3 以 TOOL_SUSPENDED 结果消息返回外部工具挂起时，
     * 运行器仍能正确把任务切换到 {@link PptJobStatus#WAITING_CONFIRMATION}。
     */
    @Test
    void startTransitionsToWaitingConfirmationOnSuspendedResultMessage() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        ToolUseBlock approvalToolCall = new ToolUseBlock(
                "call-1",
                "request_plan_confirmation",
                Map.of("planSummary", "import first", "pendingSteps", "confirm plan"));
        ToolResultBlock suspendedResult = ToolResultBlock.suspended(approvalToolCall);
        Msg suspendedMessage = AssistantMessage.builder()
                .name("test-agent")
                .content(List.of(
                        approvalToolCall,
                        suspendedResult,
                        TextBlock.builder().text("waiting for confirmation").build()))
                .generateReason(GenerateReason.TOOL_SUSPENDED)
                .build();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new AgentResultEvent(suspendedMessage)));

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                agentScopeProperties(),
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();

        runner.start(job);

        assertThat(job.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        assertThat(job.currentConfirmationId()).isPresent();
        assertThat(job.confirmationPayload()).containsEntry("toolName", "request_plan_confirmation");
        assertThat(job.confirmationPayload()).containsEntry("replyId", suspendedMessage.getId());
    }

    /**
     * 验证当事件流未显式返回挂起信号，但 AgentScope 持久化状态中仍有 pending tool call 时，
     * 运行器能够从 agent_state.json 恢复等待确认状态。
     */
    @Test
    void startRecoversWaitingConfirmationFromPersistedAgentState() throws Exception {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        Msg finalMessage = new AssistantMessage("现在流程按工具要求暂停在人工确认点。你确认后，我会继续生成后续产物。");
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new AgentResultEvent(finalMessage)));

        Path sessionStorePath = Files.createTempDirectory("agentscope-session-store");
        AgentScopeProperties properties = new AgentScopeProperties(
                "openai",
                "dummy-model",
                null,
                null,
                8,
                sessionStorePath,
                "ppt-master-service",
                null,
                null,
                null);
        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                properties,
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();

        ToolUseBlock approvalToolCall = new ToolUseBlock(
                "call-persisted",
                "request_plan_confirmation",
                Map.of("planSummary", "persisted plan", "pendingSteps", "confirm plan"));
        ToolResultBlock runningResult = ToolResultBlock.builder()
                .id(approvalToolCall.getId())
                .name(approvalToolCall.getName())
                .output(List.of(TextBlock.builder().text("Error: Tool execution failed: Tool execution suspended").build()))
                .state(io.agentscope.core.message.ToolResultState.RUNNING)
                .build();
        AgentState persistedState = AgentState.builder()
                .sessionId(job.id().toString())
                .userId("ppt-master-service")
                .context(List.of(
                        AssistantMessage.builder()
                                .name("test-agent")
                                .content(List.of(
                                        TextBlock.builder().text("已发起人工确认").build(),
                                        approvalToolCall))
                                .build(),
                        new io.agentscope.core.message.ToolResultMessage(List.of(runningResult)),
                        new AssistantMessage("现在流程按工具要求暂停在人工确认点。")))
                .build();
        new JsonFileAgentStateStore(sessionStorePath)
                .save("ppt-master-service", job.id().toString(), "agent_state", persistedState);

        runner.start(job);

        assertThat(job.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        assertThat(job.currentConfirmationId()).isPresent();
        assertThat(job.confirmationPayload()).containsEntry("toolName", "request_plan_confirmation");
        assertThat(job.confirmationPayload()).containsEntry("replyId", job.id().toString());
    }

    /**
     * 验证当任务已生成导出产物时，持久化会话中残留的 pending tool call
     * 不会再把任务错误回退到 {@link PptJobStatus#WAITING_CONFIRMATION}。
     */
    @Test
    void startDoesNotRecoverWaitingConfirmationAfterExportArtifactExists() throws Exception {
        Msg finalMessage = new AssistantMessage("ppt 已导出完成。");
        Path exportPath = Path.of("var/ppt-master/jobs/demo/exports/demo.pptx");
        AgentScopeWorkflowAgentFactory factory = job -> (messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new AgentResultEvent(finalMessage)).doOnComplete(() -> job.complete(exportPath));

        Path sessionStorePath = Files.createTempDirectory("agentscope-session-store");
        AgentScopeProperties properties = new AgentScopeProperties(
                "openai",
                "dummy-model",
                null,
                null,
                8,
                sessionStorePath,
                "ppt-master-service",
                null,
                null,
                null);
        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                properties,
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();

        ToolUseBlock approvalToolCall = new ToolUseBlock(
                "call-persisted",
                "request_plan_confirmation",
                Map.of("planSummary", "persisted plan", "pendingSteps", "confirm plan"));
        AgentState persistedState = AgentState.builder()
                .sessionId(job.id().toString())
                .userId("ppt-master-service")
                .context(List.of(
                        AssistantMessage.builder()
                                .name("test-agent")
                                .content(List.of(
                                        TextBlock.builder().text("已发起人工确认").build(),
                                        approvalToolCall))
                                .build()))
                .build();
        new JsonFileAgentStateStore(sessionStorePath)
                .save("ppt-master-service", job.id().toString(), "agent_state", persistedState);

        runner.start(job);

        assertThat(job.status()).isEqualTo(PptJobStatus.COMPLETED);
        assertThat(job.exportPath()).contains(exportPath);
        assertThat(job.currentConfirmationId()).isEmpty();
    }

    /**
     * 验证基础流程的初始指令中不包含文生图相关步骤。
     */
    @Test
    void basicModeInitialInstructionDoesNotMentionImageGeneration() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-1",
                        List.of(new ToolUseBlock(
                                "call-1",
                                "request_plan_confirmation",
                                Map.of("planSummary", "basic plan", "pendingSteps", "confirm"))))));

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                agentScopeProperties(),
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();

        runner.start(job);

        Msg initialMessage = factory.messages().get(0).get(0);
        String text = initialMessage.getTextContent();
        assertThat(text).contains("workflowMode: BASIC");
        assertThat(text).doesNotContain("generate_project_images");
        assertThat(text).doesNotContain("images/image_prompts.json");
    }

    /**
     * 验证文生图进阶流程的初始指令中包含 workflowMode 与图片阶段步骤。
     */
    @Test
    void imageEnhancedModeInitialInstructionMentionsImageWorkflow() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-1",
                        List.of(new ToolUseBlock(
                                "call-1",
                                "request_plan_confirmation",
                                Map.of("planSummary", "image enhanced plan", "pendingSteps", "confirm"))))));

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                agentScopeProperties(),
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = newImageEnhancedJob();

        runner.start(job);

        Msg initialMessage = factory.messages().get(0).get(0);
        String text = initialMessage.getTextContent();
        assertThat(text).contains("workflowMode: IMAGE_ENHANCED");
        assertThat(text).contains("generate_project_images");
        assertThat(text).contains("images/image_prompts.json");
    }

    /**
     * 验证图片失败重试确认场景下，confirmationPayload 能正确保存阶段信息。
     */
    @Test
    void imageRetryConfirmationStageIsPreservedInPayload() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-1",
                        List.of(new ToolUseBlock(
                                "call-1",
                                "request_plan_confirmation",
                                Map.of(
                                        "planSummary", "image generation failed",
                                        "pendingSteps", "retry failed images",
                                        "stage", "image_retry_decision",
                                        "title", "图片生成失败",
                                        "message", "部分图片生成失败，请选择是否重试",
                                        "contextData", Map.of(
                                                "failedImages", List.of("cover.png"),
                                                "lastError", "502 Bad gateway")))))));

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                agentScopeProperties(),
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = newImageEnhancedJob();

        runner.start(job);

        assertThat(job.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        assertThat(job.confirmationPayload()).containsEntry("stage", "image_retry_decision");
        assertThat(job.confirmationPayload()).containsEntry("title", "图片生成失败");
        assertThat(job.confirmationPayload()).containsEntry("message", "部分图片生成失败，请选择是否重试");
        assertThat(job.confirmationPayload()).containsKey("contextData");
        assertThat(job.confirmationPayload()).containsEntry("toolName", "request_plan_confirmation");
    }

    /**
     * 验证图片阶段恢复时，ToolResultMessage 中携带阶段信息与用户动作，
     * 便于 Agent 识别当前应进入重试还是继续后续步骤。
     */
    @Test
    void imageStageResumeCarriesStageAndOperatorAction() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-1",
                        List.of(new ToolUseBlock(
                                "call-1",
                                "request_plan_confirmation",
                                Map.of(
                                        "planSummary", "image generation failed",
                                        "pendingSteps", "retry failed images",
                                        "stage", "image_retry_decision",
                                        "title", "图片生成失败",
                                        "message", "部分图片生成失败，请选择是否重试"))))));
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-2", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-2",
                        List.of(new ToolUseBlock(
                                "call-2",
                                "request_plan_confirmation",
                                Map.of(
                                        "planSummary", "images ready",
                                        "pendingSteps", "continue to svg generation",
                                        "stage", "image_ready_continue_confirmation",
                                        "title", "图片已就绪",
                                        "message", "图片已全部生成，是否继续后续 PPT 制作"))))));

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                agentScopeProperties(),
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = newImageEnhancedJob();

        runner.start(job);
        PptConfirmation retryConfirmation = new PptConfirmation(
                job.currentConfirmationId().orElseThrow(),
                true,
                Map.of(),
                "重试生成失败的图片",
                Instant.now());
        job.receiveConfirmation(retryConfirmation);

        runner.resume(job, retryConfirmation);

        assertThat(factory.messages()).hasSize(2);
        Msg resumeMessage = factory.messages().get(1).get(0);
        assertThat(resumeMessage).isInstanceOf(ToolResultMessage.class);
        List<ToolResultBlock> blocks = resumeMessage.getContentBlocks(ToolResultBlock.class);
        assertThat(blocks).hasSize(1);
        String outputText = ((TextBlock) blocks.get(0).getOutput().get(0)).getText();
        assertThat(outputText).contains("confirmation_stage=image_retry_decision");
        assertThat(outputText).contains("operator_action=重试生成失败的图片");
        assertThat(job.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        assertThat(job.confirmationPayload()).containsEntry("stage", "image_ready_continue_confirmation");
    }

    private static PptMasterProperties pptMasterProperties() {
        return new PptMasterProperties(
                Path.of("/home/zhang/PycharmProjects/ppt-master"),
                Path.of("var/ppt-master"),
                "python3",
                java.time.Duration.ofMinutes(10));
    }

    private static AgentScopeProperties agentScopeProperties() {
        return new AgentScopeProperties(
                "openai",
                "dummy-model",
                null,
                null,
                8,
                Path.of("var/ppt-master/agent-sessions"),
                "ppt-master-service",
                null,
                null,
                null);
    }

    private static PptJob sampleJob() {
        PptJob job = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                Path.of("var/ppt-master/jobs/demo"));
        job.addSource(new PptSourceFile("source.md", "text/markdown", 12L, Path.of("var/ppt-master/jobs/demo/uploads/source.md")));
        return job;
    }

    private static PptJob newImageEnhancedJob() {
        PptJob job = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck with ai images",
                PptWorkflowMode.IMAGE_ENHANCED,
                Path.of("var/ppt-master/jobs/demo"));
        job.addSource(new PptSourceFile("source.md", "text/markdown", 12L, Path.of("var/ppt-master/jobs/demo/uploads/source.md")));
        return job;
    }

    /**
     * 测试用 {@link AgentScopeWorkflowAgentFactory} 实现。
     * <p>
     * 预先注册一系列 Agent 行为（通过 {@link #enqueue(AgentScopeWorkflowAgent)}），
     * 每次调用 {@link #create(PptJob)} 时依次取出并包装，同时记录调用时传入的
     * 消息列表和运行时上下文，便于后续断言验证。
     */
    private static final class RecordingAgentFactory implements AgentScopeWorkflowAgentFactory {

        private final Queue<AgentScopeWorkflowAgent> queue = new ArrayDeque<>();
        private final List<List<Msg>> messages = new java.util.ArrayList<>();
        private final List<RuntimeContext> contexts = new java.util.ArrayList<>();

        void enqueue(AgentScopeWorkflowAgent agent) {
            queue.add(agent);
        }

        List<List<Msg>> messages() {
            return messages;
        }

        List<RuntimeContext> contexts() {
            return contexts;
        }

        @Override
        public AgentScopeWorkflowAgent create(PptJob job) {
            AgentScopeWorkflowAgent next = queue.remove();
            return (messages, runtimeContext) -> {
                this.messages.add(messages);
                this.contexts.add(runtimeContext);
                return next.streamEvents(messages, runtimeContext);
            };
        }
    }
}
