package domi.argenticpptmaster.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptConfirmation;
import domi.argenticpptmaster.domain.PptConfirmationAction;
import domi.argenticpptmaster.domain.PptOutline;
import domi.argenticpptmaster.domain.PptSlideEdit;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.domain.SlideOutline;
import domi.argenticpptmaster.service.PptImageManifestStore;
import domi.argenticpptmaster.service.PptJobEventPublisher;
import domi.argenticpptmaster.service.PptOutlineStore;
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
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * {@link AgentScopePptAgentRunner} 的单元测试。
 * <p>
 * 验证 Agent 启动流程能够正确触发待确认状态，
 * 以及恢复（resume）流程能够将人工审批结果以工具调用结果消息（ToolResultMessage）
 * 的形式传递给 Agent 继续执行。
 */
class AgentScopePptAgentRunnerTests {

    @TempDir
    Path tempDir;

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
                                outlineConfirmationInput("import first", "confirm plan"))))));

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
                                outlineConfirmationInput("phase one", "confirm"))))));
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-2", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-2",
                        List.of(new ToolUseBlock(
                                "call-2",
                                "request_plan_confirmation",
                                outlineConfirmationInput("phase two", "confirm export"))))));

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
                Instant.now(),
                PptConfirmationAction.REQUEST_REVISION,
                "调整整体叙事",
                List.of(new PptSlideEdit(2, "替换视觉建议")));
        job.receiveConfirmation(confirmation);

        runner.resume(job, confirmation);

        assertThat(factory.messages()).hasSize(2);
        Msg resumeMessage = factory.messages().get(1).get(0);
        assertThat(resumeMessage).isInstanceOf(ToolResultMessage.class);
        List<ToolResultBlock> blocks = resumeMessage.getContentBlocks(ToolResultBlock.class);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getName()).isEqualTo("request_plan_confirmation");
        assertThat(((TextBlock) blocks.get(0).getOutput().get(0)).getText())
                .contains("Operator action: REQUEST_REVISION")
                .contains("outline_overall_comment=调整整体叙事")
                .contains("outline_slide_edits=[PptSlideEdit[slideNo=2, comment=替换视觉建议]]");
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
                outlineConfirmationInput("import first", "confirm plan"));
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

    @Test
    void deduplicatesSameApprovedToolCallAcrossExternalAndSuspendedEvents() {
        PptJob job = sampleJob();
        Path projectPath = tempDir.resolve(job.id().toString());
        job.prepareProject(projectPath);
        new PptOutlineStore().write(projectPath, new PptOutline(1, true, List.of(new SlideOutline(
                1, "封面", "结论", List.of("要点"), "插图", null))));
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        ToolUseBlock duplicateCall = new ToolUseBlock(
                "call-duplicate",
                "request_plan_confirmation",
                outlineConfirmationInput("重复大纲", "等待确认"));
        Msg suspendedMessage = AssistantMessage.builder()
                .name("test-agent")
                .content(List.of(duplicateCall, ToolResultBlock.suspended(duplicateCall)))
                .generateReason(GenerateReason.TOOL_SUSPENDED)
                .build();
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent("reply-1", List.of(duplicateCall)),
                new AgentResultEvent(suspendedMessage)));
        factory.enqueue((messages, runtimeContext) -> Flux.concat(
                Flux.just(new AgentStartEvent("reply-2", runtimeContext.getSessionId(), "test-agent")),
                Flux.defer(() -> {
                    job.complete(Path.of("var/ppt-master/exports/deduplicated.pptx"));
                    return Flux.just(new AgentResultEvent(new AssistantMessage("completed")));
                })));
        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(), agentScopeProperties(), factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));

        runner.start(job);

        List<ToolResultBlock> results = factory.messages().get(1).get(0)
                .getContentBlocks(ToolResultBlock.class);
        assertThat(results).singleElement().extracting(ToolResultBlock::getId).isEqualTo("call-duplicate");
        assertThat(job.events()).filteredOn(event ->
                        "confirmation_auto_acknowledged".equals(event.data().get("kind")))
                .hasSize(1);
        assertThat(job.events()).filteredOn(event -> event.type() == PptJobEventType.CONFIRMATION_REQUIRED).isEmpty();
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
                outlineConfirmationInput("persisted plan", "confirm plan"));
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

    @Test
    void autoAcknowledgesApprovedPersistedPendingToolCallAndContinues() throws Exception {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new AgentResultEvent(new AssistantMessage("pending confirmation persisted"))));
        PptJob job = sampleJob();
        Path projectPath = tempDir.resolve(job.id().toString());
        job.prepareProject(projectPath);
        new PptOutlineStore().write(projectPath, new PptOutline(1, true, List.of(new SlideOutline(
                1, "封面", "结论", List.of("要点"), "插图", null))));
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        factory.enqueue((messages, runtimeContext) -> Flux.concat(
                Flux.just(new AgentStartEvent("reply-2", runtimeContext.getSessionId(), "test-agent")),
                Flux.defer(() -> {
                    job.complete(Path.of("var/ppt-master/exports/persisted.pptx"));
                    return Flux.just(new AgentResultEvent(new AssistantMessage("completed")));
                })));

        Path sessionStorePath = tempDir.resolve("agent-state-" + job.id());
        AgentScopeProperties properties = new AgentScopeProperties(
                "openai", "dummy-model", null, null, 8, sessionStorePath,
                "ppt-master-service", null, null, null);
        ToolUseBlock pendingCall = new ToolUseBlock(
                "call-persisted-approved",
                "request_plan_confirmation",
                outlineConfirmationInput("persisted outline", "confirm outline"));
        AgentState persistedState = AgentState.builder()
                .sessionId(job.id().toString())
                .userId("ppt-master-service")
                .context(List.of(AssistantMessage.builder()
                        .name("test-agent")
                        .content(List.of(pendingCall))
                        .build()))
                .build();
        new JsonFileAgentStateStore(sessionStorePath)
                .save("ppt-master-service", job.id().toString(), "agent_state", persistedState);
        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(), properties, factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));

        runner.start(job);

        assertThat(job.status()).isEqualTo(PptJobStatus.COMPLETED);
        assertThat(job.currentConfirmationId()).isEmpty();
        ToolResultBlock result = factory.messages().get(1).get(0).getContentBlocks(ToolResultBlock.class).get(0);
        assertThat(result.getId()).isEqualTo("call-persisted-approved");
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).contains("ALREADY_APPROVED");
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
                outlineConfirmationInput("persisted plan", "confirm plan"));
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
                                outlineConfirmationInput("basic plan", "confirm"))))));

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
                                outlineConfirmationInput("image enhanced plan", "confirm"))))));

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
    void imageRetryConfirmationStageIsPreservedInPayload() throws Exception {
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
        prepareImageWorkflow(job, "Failed");
        job.confirmNode(PptJobNode.IMAGE_MANIFEST_CONFIRMED);

        runner.start(job);

        assertThat(job.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        assertThat(job.confirmationPayload()).containsEntry("stage", "image_retry_decision");
        assertThat(job.confirmationPayload()).containsEntry("title", "图片生成失败");
        assertThat(job.confirmationPayload()).containsEntry("message", "部分图片生成失败，请选择是否重试");
        assertThat(job.confirmationPayload()).containsKey("contextData");
        assertThat(job.confirmationPayload()).containsEntry("toolName", "request_plan_confirmation");
    }

    @Test
    void outlineConfirmationPayloadKeepsStructuredSlides() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        Map<String, Object> outline = Map.of(
                "type", "ppt_outline",
                "slides", List.of(Map.of(
                        "slideNo", 1,
                        "title", "市场背景",
                        "keyMessage", "市场处于增长窗口",
                        "bullets", List.of("趋势", "机会"),
                        "visualSuggestion", "增长曲线")));
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-1",
                        List.of(new ToolUseBlock(
                                "call-1",
                                "request_plan_confirmation",
                                Map.of(
                                        "stage", "outline_confirmation",
                                        "planSummary", "逐页大纲",
                                        "pendingSteps", "等待大纲确认",
                                        "contextData", outline))))));

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                agentScopeProperties(),
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();

        runner.start(job);

        assertThat(job.confirmationPayload()).containsEntry("stage", "outline_confirmation");
        assertThat(job.confirmationPayload().get("contextData"))
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("type", "ppt_outline")
                .containsEntry("version", 1)
                .containsEntry("locked", false);
    }

    @Test
    void reusesExistingUnlockedOutlineWhenRecoveringSameConfirmationVersion() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        Map<String, Object> confirmationInput = outlineConfirmationInput("首次大纲", "等待确认");
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent("reply-1", List.of(new ToolUseBlock(
                        "call-1", "request_plan_confirmation", confirmationInput)))));
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-2", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent("reply-2", List.of(new ToolUseBlock(
                        "call-2", "request_plan_confirmation", confirmationInput)))));

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(), agentScopeProperties(), factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();

        runner.start(job);
        runner.start(job);

        assertThat(job.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        assertThat(job.confirmationPayload()).containsEntry("stage", "outline_confirmation");
    }

    @Test
    void autoAcknowledgesRepeatedLockedOutlineAndContinuesWithoutNewConfirmation() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        Map<String, Object> confirmationInput = outlineConfirmationInput("首次大纲", "等待确认");
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent("reply-1", List.of(new ToolUseBlock(
                        "call-1", "request_plan_confirmation", confirmationInput)))));
        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(), agentScopeProperties(), factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();

        runner.start(job);
        PptOutlineStore outlineStore = new PptOutlineStore();
        PptOutline lockedOutline = outlineStore.read(job.projectPath().orElseThrow()).lock();
        outlineStore.write(job.projectPath().orElseThrow(), lockedOutline);
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        String originalConfirmationId = job.currentConfirmationId().orElseThrow();
        PptConfirmation confirmation = new PptConfirmation(
                originalConfirmationId, true, Map.of(), null, Instant.now(), PptConfirmationAction.APPROVE,
                null, List.of(), 1, List.of());
        job.receiveConfirmation(confirmation);
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-2", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent("reply-2", List.of(new ToolUseBlock(
                        "call-2", "request_plan_confirmation", confirmationInput)))));
        factory.enqueue((messages, runtimeContext) -> Flux.concat(
                Flux.just(new AgentStartEvent("reply-3", runtimeContext.getSessionId(), "test-agent")),
                Flux.defer(() -> {
                    job.complete(Path.of("var/ppt-master/exports/demo.pptx"));
                    return Flux.just(new AgentResultEvent(new AssistantMessage("completed")));
                })));

        runner.resume(job, confirmation);

        assertThat(job.status()).isEqualTo(PptJobStatus.COMPLETED);
        assertThat(job.currentConfirmationId()).isEmpty();
        assertThat(job.nodeExecution(PptJobNode.OUTLINE_CONFIRMED).status().name()).isEqualTo("COMPLETED");
        assertThat(factory.messages()).hasSize(3);
        ToolResultBlock autoResult = factory.messages().get(2).get(0).getContentBlocks(ToolResultBlock.class).get(0);
        assertThat(autoResult.getId()).isEqualTo("call-2");
        assertThat(((TextBlock) autoResult.getOutput().get(0)).getText())
                .contains("confirmation_status=ALREADY_APPROVED");
        assertThat(job.events()).filteredOn(event -> "confirmation_auto_acknowledged".equals(event.data().get("kind")))
                .hasSize(1);
    }

    @Test
    void autoAcknowledgesRepeatedImageManifestAndContinuesInSameSession() {
        PptJob job = newImageEnhancedJob();
        prepareImageWorkflow(job, "Pending");
        job.confirmNode(PptJobNode.IMAGE_MANIFEST_CONFIRMED);
        RecordingAgentFactory factory = new RecordingAgentFactory();
        Map<String, Object> input = Map.of(
                "stage", "image_manifest_confirmation",
                "contextData", Map.of(
                        "type", "ppt_image_manifest",
                        "outlineVersion", 1,
                        "items", List.of()));
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent("reply-1", List.of(new ToolUseBlock(
                        "image-call-1", "request_plan_confirmation", input)))));
        factory.enqueue((messages, runtimeContext) -> Flux.concat(
                Flux.just(new AgentStartEvent("reply-2", runtimeContext.getSessionId(), "test-agent")),
                Flux.defer(() -> {
                    job.complete(Path.of("var/ppt-master/exports/images.pptx"));
                    return Flux.just(new AgentResultEvent(new AssistantMessage("completed")));
                })));
        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(), agentScopeProperties(), factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));

        runner.start(job);

        assertThat(job.status()).isEqualTo(PptJobStatus.COMPLETED);
        assertThat(job.currentConfirmationId()).isEmpty();
        assertThat(job.events()).filteredOn(event -> event.type() == PptJobEventType.CONFIRMATION_REQUIRED).isEmpty();
        assertThat(factory.contexts()).extracting(RuntimeContext::getSessionId).containsOnly(job.id().toString());
        ToolResultBlock result = factory.messages().get(1).get(0).getContentBlocks(ToolResultBlock.class).get(0);
        assertThat(result.getId()).isEqualTo("image-call-1");
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).contains("ALREADY_APPROVED");
    }

    @Test
    void rejectsMoreThanThreeConsecutiveAutomaticConfirmationShortcuts() {
        PptJob job = sampleJob();
        Path projectPath = tempDir.resolve(job.id().toString());
        job.prepareProject(projectPath);
        new PptOutlineStore().write(projectPath, new PptOutline(1, true, List.of(new SlideOutline(
                1, "封面", "结论", List.of("要点"), "插图", null))));
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        RecordingAgentFactory factory = new RecordingAgentFactory();
        for (int index = 1; index <= 4; index++) {
            int callIndex = index;
            factory.enqueue((messages, runtimeContext) -> Flux.just(
                    new AgentStartEvent("reply-" + callIndex, runtimeContext.getSessionId(), "test-agent"),
                    new RequireExternalExecutionEvent("reply-" + callIndex, List.of(new ToolUseBlock(
                            "call-" + callIndex,
                            "request_plan_confirmation",
                            outlineConfirmationInput("重复大纲", "等待确认"))))));
        }
        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(), agentScopeProperties(), factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));

        assertThatThrownBy(() -> runner.start(job))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than 3 times");
        assertThat(factory.messages()).hasSize(4);
        assertThat(job.currentConfirmationId()).isEmpty();
        assertThat(job.events()).filteredOn(event -> event.type() == PptJobEventType.CONFIRMATION_REQUIRED).isEmpty();
        assertThat(job.events()).filteredOn(event ->
                        "confirmation_auto_acknowledged".equals(event.data().get("kind")))
                .hasSize(3);
    }

    @Test
    void rejectsMixedBatchInsteadOfAutoAcknowledgingEveryToolCallFromFirstStage() throws Exception {
        PptJob job = newImageEnhancedJob();
        prepareImageWorkflow(job, "Failed");
        job.confirmNode(PptJobNode.IMAGE_MANIFEST_CONFIRMED);
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-mixed", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent("reply-mixed", List.of(
                        new ToolUseBlock(
                                "call-outline", "request_plan_confirmation",
                                outlineConfirmationInput("重复大纲", "等待确认")),
                        new ToolUseBlock(
                                "call-retry", "request_plan_confirmation",
                                Map.of(
                                        "stage", "image_retry_decision",
                                        "contextData", Map.of("outlineVersion", 1)))))));
        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(), agentScopeProperties(), factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));

        assertThatThrownBy(() -> runner.start(job))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mixed confirmation stages");
        assertThat(job.currentConfirmationId()).isEmpty();
        assertThat(job.events()).filteredOn(event -> event.type() == PptJobEventType.CONFIRMATION_REQUIRED).isEmpty();
    }

    @Test
    void approvedOutlineConfirmationTellsAgentToContinueWithoutAnotherOutlineRequest() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent("reply-1", List.of(new ToolUseBlock(
                        "call-1", "request_plan_confirmation", outlineConfirmationInput("逐页大纲", "等待确认"))))));
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-2", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent("reply-2", List.of(new ToolUseBlock(
                        "call-2", "request_plan_confirmation", outlineConfirmationInput("图片清单", "等待确认"))))));
        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(), agentScopeProperties(), factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();
        runner.start(job);
        PptConfirmation confirmation = new PptConfirmation(
                job.currentConfirmationId().orElseThrow(), true, Map.of(), null, Instant.now(), PptConfirmationAction.APPROVE,
                null, List.of(), 1, List.of());
        job.receiveConfirmation(confirmation);
        runner.resume(job, confirmation);

        Msg resumeMessage = factory.messages().get(1).get(0);
        String output = ((TextBlock) resumeMessage.getContentBlocks(ToolResultBlock.class).get(0).getOutput().get(0)).getText();
        assertThat(output)
                .contains("outline_status=LOCKED")
                .contains("Do not request outline_confirmation again");
    }

    @Test
    void approvedImageManifestConfirmationTellsAgentToGenerateImagesWithoutRepeatingTheConfirmation() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        Map<String, Object> imageManifestConfirmation = Map.of(
                "stage", "image_manifest_confirmation",
                "planSummary", "图片清单已生成",
                "pendingSteps", "等待图片清单确认",
                "contextData", Map.of("type", "ppt_image_manifest", "outlineVersion", 1, "items", List.of()));
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent("reply-1", List.of(new ToolUseBlock(
                        "call-1", "request_plan_confirmation", imageManifestConfirmation)))));
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-2", runtimeContext.getSessionId(), "test-agent"),
                new AgentResultEvent(new AssistantMessage("continue image generation"))));
        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(), agentScopeProperties(), factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = newImageEnhancedJob();
        prepareImageWorkflow(job, "Pending");

        runner.start(job);
        PptConfirmation confirmation = new PptConfirmation(
                job.currentConfirmationId().orElseThrow(), true, Map.of(), null, Instant.now(),
                PptConfirmationAction.APPROVE, null, List.of(), null, List.of());
        job.confirmNode(PptJobNode.IMAGE_MANIFEST_CONFIRMED);
        job.receiveConfirmation(confirmation);
        assertThatThrownBy(() -> runner.resume(job, confirmation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without export artifact");

        Msg resumeMessage = factory.messages().get(1).get(0);
        String output = ((TextBlock) resumeMessage.getContentBlocks(ToolResultBlock.class).get(0).getOutput().get(0)).getText();
        assertThat(output)
                .contains("image_manifest_status=APPROVED")
                .contains("Do not request image_manifest_confirmation again")
                .contains("generate_project_images");
    }

    @Test
    void rejectedLegacyOutlineConfirmationDoesNotTellAgentItIsLocked() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent("reply-1", List.of(new ToolUseBlock(
                        "call-1", "request_plan_confirmation", outlineConfirmationInput("逐页大纲", "等待确认"))))));
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-2", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent("reply-2", List.of(new ToolUseBlock(
                        "call-2", "request_plan_confirmation", outlineConfirmationInput("修订大纲", "等待确认"))))));
        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(), agentScopeProperties(), factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();
        runner.start(job);
        PptConfirmation confirmation = new PptConfirmation(
                job.currentConfirmationId().orElseThrow(), false, Map.of(), null, Instant.now());
        job.receiveConfirmation(confirmation);

        runner.resume(job, confirmation);

        Msg resumeMessage = factory.messages().get(1).get(0);
        String output = ((TextBlock) resumeMessage.getContentBlocks(ToolResultBlock.class).get(0).getOutput().get(0)).getText();
        assertThat(output)
                .doesNotContain("outline_status=LOCKED")
                .doesNotContain("Do not request outline_confirmation again");
    }

    @Test
    void firstLegacyPlanConfirmationWaitsWithStructuredOutline() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        Map<String, Object> planConfirmationInput = Map.of(
                "stage", "plan_confirmation",
                "planSummary", "仅有整体计划",
                "pendingSteps", "等待确认",
                "contextData", Map.of(
                        "type", "ppt_outline",
                        "slides", List.of(Map.of(
                                "slideNo", 1,
                                "title", "测试页面",
                                "keyMessage", "测试关键信息",
                                "bullets", List.of("测试要点"),
                                "visualSuggestion", "测试视觉方案"))));
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-1",
                        List.of(new ToolUseBlock(
                                "call-1",
                                "request_plan_confirmation",
                                planConfirmationInput)))));

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                agentScopeProperties(),
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();

        runner.start(job);

        assertThat(job.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        assertThat(job.confirmationPayload()).containsEntry("stage", "plan_confirmation");
        assertThat(job.currentConfirmationId()).isPresent();
    }

    @Test
    void autoAcknowledgesRepeatedCompletedLegacyPlanConfirmation() {
        PptJob job = sampleJob();
        job.confirmNode(PptJobNode.PLAN_CONFIRMED);
        RecordingAgentFactory factory = new RecordingAgentFactory();
        Map<String, Object> planInput = Map.of(
                "stage", "plan_confirmation",
                "contextData", Map.of(
                        "type", "ppt_outline",
                        "slides", List.of(Map.of(
                                "slideNo", 1,
                                "title", "历史计划",
                                "keyMessage", "继续执行",
                                "bullets", List.of("已批准"),
                                "visualSuggestion", "流程图"))));
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-plan-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent("reply-plan-1", List.of(new ToolUseBlock(
                        "call-plan-1", "request_plan_confirmation", planInput)))));
        factory.enqueue((messages, runtimeContext) -> Flux.concat(
                Flux.just(new AgentStartEvent("reply-plan-2", runtimeContext.getSessionId(), "test-agent")),
                Flux.defer(() -> {
                    job.complete(Path.of("var/ppt-master/exports/legacy-plan.pptx"));
                    return Flux.just(new AgentResultEvent(new AssistantMessage("completed")));
                })));
        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(), agentScopeProperties(), factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));

        runner.start(job);

        assertThat(job.status()).isEqualTo(PptJobStatus.COMPLETED);
        assertThat(job.currentConfirmationId()).isEmpty();
        ToolResultBlock result = factory.messages().get(1).get(0).getContentBlocks(ToolResultBlock.class).get(0);
        assertThat(result.getId()).isEqualTo("call-plan-1");
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).contains("ALREADY_APPROVED");
    }

    @Test
    void firstConfirmationWithoutOutlineStageIsRejectedBeforeWaitingForUser() {
        List<Map<String, Object>> invalidInputs = List.of(
                Map.of("planSummary", "仅有整体计划", "pendingSteps", "等待确认"),
                Map.of(
                        "stage", "unknown_confirmation",
                        "planSummary", "伪造阶段",
                        "pendingSteps", "等待确认",
                        "contextData", Map.of(
                                "type", "ppt_outline",
                                "slides", List.of(Map.of(
                                        "slideNo", 1,
                                        "title", "测试页面",
                                        "keyMessage", "测试关键信息",
                                        "bullets", List.of("测试要点"),
                                        "visualSuggestion", "测试视觉方案")))),
                Map.of(
                        "stage", "image_manifest_confirmation",
                        "planSummary", "图片清单",
                        "pendingSteps", "等待图片确认",
                        "contextData", Map.of(
                                "type", "ppt_image_manifest",
                                "outlineVersion", 1,
                                "items", List.of())));

        for (Map<String, Object> input : invalidInputs) {
            RecordingAgentFactory factory = new RecordingAgentFactory();
            factory.enqueue((messages, runtimeContext) -> Flux.just(
                    new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                    new RequireExternalExecutionEvent(
                            "reply-1",
                            List.of(new ToolUseBlock("call-1", "request_plan_confirmation", input)))));
            AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                    pptMasterProperties(),
                    agentScopeProperties(),
                    factory,
                    new PptWorkflowEvents(new PptJobEventPublisher()));
            PptJob job = sampleJob();

            assertThatThrownBy(() -> runner.start(job))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("first request_plan_confirmation");
            assertThat(job.status()).isNotEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        }
    }

    /**
     * 验证图片阶段恢复时，ToolResultMessage 中携带阶段信息与用户动作，
     * 便于 Agent 识别当前应进入重试还是继续后续步骤。
     */
    @Test
    void imageStageResumeCarriesStageAndOperatorAction() throws Exception {
        PptJob job = newImageEnhancedJob();
        prepareImageWorkflow(job, "Failed");
        job.confirmNode(PptJobNode.IMAGE_MANIFEST_CONFIRMED);
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
        factory.enqueue((messages, runtimeContext) -> {
            updateManifestStatus(job, "Generated");
            job.completeNode(PptJobNode.IMAGES_GENERATED, Map.of());
            return Flux.just(
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
                                            "message", "图片已全部生成，是否继续后续 PPT 制作",
                                            "contextData", Map.of("outlineVersion", 1))))));
        });

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                agentScopeProperties(),
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
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
                java.time.Duration.ofMinutes(10),
                null,
                1_048_576L,
                0, 0, 0, 0, null, null, null, null, null);
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

    private static Map<String, Object> outlineConfirmationInput(String planSummary, String pendingSteps) {
        return Map.of(
                "stage", "outline_confirmation",
                "planSummary", planSummary,
                "pendingSteps", pendingSteps,
                "contextData", Map.of(
                        "type", "ppt_outline",
                        "slides", List.of(Map.of(
                                "slideNo", 1,
                                "title", "测试页面",
                                "keyMessage", "测试关键信息",
                                "bullets", List.of("测试要点"),
                                "visualSuggestion", "测试视觉方案"))));
    }

    /**
     * 验证 checkpoint 恢复会启用新的 attempt session，避免复用旧失败上下文。
     */
    @Test
    void resumeFromCheckpointUsesNewAttemptSession() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-1",
                        List.of(new ToolUseBlock(
                                "call-1",
                                "request_plan_confirmation",
                                outlineConfirmationInput("checkpoint resume", "continue"))))));

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                agentScopeProperties(),
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();
        String initialSessionId = job.activeAttemptSessionId();
        job.startNewResumeAttempt();

        runner.resumeFromCheckpoint(job, domi.argenticpptmaster.domain.PptJobNode.PROJECT_READY);

        assertThat(job.activeAttemptSessionId()).isNotEqualTo(initialSessionId);
        assertThat(factory.contexts()).hasSize(1);
        assertThat(factory.contexts().get(0).getSessionId())
                .contains(job.id().toString())
                .contains(job.activeAttemptSessionId());
        Msg resumeMessage = factory.messages().get(0).get(0);
        String text = resumeMessage.getTextContent();
        assertThat(text).contains("从上一个成功节点恢复执行");
        assertThat(text).contains("PROJECT_READY");
    }

    /**
     * 验证 checkpoint 恢复时，若事件流未显式挂起但持久化状态中存在 pending tool call，
     * 运行器应从 checkpoint attempt session（jobId-attempt-N）读取状态并恢复为等待确认。
     */
    @Test
    void resumeFromCheckpointRecoversWaitingConfirmationFromCheckpointSessionState() throws Exception {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        Msg finalMessage = new AssistantMessage("checkpoint resumed with persisted pending tool call");
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
        job.fail("previous failure");
        job.startNewResumeAttempt();
        String checkpointSessionId = job.id() + "-" + job.activeAttemptSessionId();

        ToolUseBlock approvalToolCall = new ToolUseBlock(
                "call-checkpoint",
                "request_plan_confirmation",
                outlineConfirmationInput("checkpoint plan", "confirm checkpoint"));
        AgentState persistedState = AgentState.builder()
                .sessionId(checkpointSessionId)
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
                .save("ppt-master-service", checkpointSessionId, "agent_state", persistedState);

        runner.resumeFromCheckpoint(job, domi.argenticpptmaster.domain.PptJobNode.PROJECT_READY);

        assertThat(job.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        assertThat(job.currentConfirmationId()).isPresent();
        assertThat(job.confirmationPayload()).containsEntry("toolName", "request_plan_confirmation");
        assertThat(job.confirmationPayload()).containsEntry("replyId", checkpointSessionId);
    }

    /**
     * 验证 checkpoint 恢复后再触发确认，确认恢复应复用 checkpoint attempt 的 session。
     */
    @Test
    void resumeAfterCheckpointUsesCheckpointSessionForConfirmation() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-1",
                        List.of(new ToolUseBlock(
                                "call-1",
                                "request_plan_confirmation",
                                outlineConfirmationInput("checkpoint resume", "continue"))))));

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                agentScopeProperties(),
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = sampleJob();
        job.startNewResumeAttempt();
        String checkpointSessionId = job.id() + "-" + job.activeAttemptSessionId();

        runner.resumeFromCheckpoint(job, domi.argenticpptmaster.domain.PptJobNode.PROJECT_READY);
        String confirmationId = job.currentConfirmationId().orElseThrow();
        PptConfirmation confirmation = new PptConfirmation(
                confirmationId, true, Map.of(), "approved", Instant.now());
        job.receiveConfirmation(confirmation);
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-2", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-2",
                        List.of(new ToolUseBlock(
                                "call-2",
                                "request_plan_confirmation",
                                outlineConfirmationInput("checkpoint confirmation resumed", "continue"))))));

        runner.resume(job, confirmation);

        assertThat(factory.contexts()).hasSize(2);
        assertThat(factory.contexts().get(1).getSessionId()).isEqualTo(checkpointSessionId);
    }

    /**
     * 验证 checkpoint 恢复指令中包含已完成节点和允许推进的后续节点。
     */
    @Test
    void checkpointResumeInstructionContainsCompletedAndNextNodes() {
        RecordingAgentFactory factory = new RecordingAgentFactory();
        factory.enqueue((messages, runtimeContext) -> Flux.just(
                new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                new RequireExternalExecutionEvent(
                        "reply-1",
                        List.of(new ToolUseBlock(
                                "call-1",
                                "request_plan_confirmation",
                                outlineConfirmationInput("checkpoint resume", "continue"))))));

        AgentScopePptAgentRunner runner = new AgentScopePptAgentRunner(
                pptMasterProperties(),
                agentScopeProperties(),
                factory,
                new PptWorkflowEvents(new PptJobEventPublisher()));
        PptJob job = newImageEnhancedJob();

        runner.resumeFromCheckpoint(job, domi.argenticpptmaster.domain.PptJobNode.SPEC_LOCK_WRITTEN);

        String text = factory.messages().get(0).get(0).getTextContent();
        assertThat(text).contains("inspect_checkpoint_status");
        assertThat(text).contains("PROJECT_READY");
        assertThat(text).contains("OUTLINE_CONFIRMED");
        assertThat(text).contains("DESIGN_SPEC_WRITTEN");
        assertThat(text).contains("SPEC_LOCK_WRITTEN");
        assertThat(text).contains("IMAGES_MANIFEST_WRITTEN");
        assertThat(text).contains("IMAGES_GENERATED");
        assertThat(text).contains("IMAGE_CONTINUE_CONFIRMED");
        assertThat(text).contains("NOTES_TOTAL_WRITTEN");
        assertThat(text).contains("如果发现 checkpoint 与真实文件状态不一致");
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

    private void prepareImageWorkflow(PptJob job, String imageStatus) {
        Path projectPath = tempDir.resolve(job.id().toString());
        job.prepareProject(projectPath);
        PptOutline outline = new PptOutline(1, true, List.of(new SlideOutline(
                1,
                "封面",
                "结论",
                List.of("要点"),
                "插图",
                Map.of("purpose", "封面", "prompt", "blue abstract background"))));
        new PptOutlineStore().write(projectPath, outline);
        new PptImageManifestStore().writeFromLockedOutline(projectPath);
        if (!"Pending".equals(imageStatus)) {
            updateManifestStatus(job, imageStatus);
        }
        job.confirmNode(PptJobNode.OUTLINE_CONFIRMED);
        job.completeNode(PptJobNode.IMAGES_MANIFEST_WRITTEN, Map.of());
    }

    private void updateManifestStatus(PptJob job, String status) {
        try {
            Path projectPath = job.projectPath().orElseThrow();
            PptImageManifestStore store = new PptImageManifestStore();
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> manifest = objectMapper.readValue(
                    store.path(projectPath).toFile(), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) ((List<?>) manifest.get("items")).get(0);
            item.put("status", status);
            if ("Failed".equals(status)) {
                item.put("last_error", "generation failed");
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(store.path(projectPath).toFile(), manifest);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to prepare image manifest test fixture", ex);
        }
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
