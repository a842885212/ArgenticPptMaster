package domi.argenticpptmaster.agent;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptConfirmation;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.service.PptJobEventPublisher;
import domi.argenticpptmaster.service.PptWorkflowEvents;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
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
                "ppt-master-service");
    }

    private static PptJob sampleJob() {
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "make a deck", Path.of("var/ppt-master/jobs/demo"));
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
