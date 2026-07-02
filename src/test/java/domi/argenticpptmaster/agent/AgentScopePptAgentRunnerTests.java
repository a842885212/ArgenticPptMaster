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

class AgentScopePptAgentRunnerTests {

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
