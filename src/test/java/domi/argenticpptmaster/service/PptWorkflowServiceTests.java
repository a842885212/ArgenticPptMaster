package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptConfirmationAction;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobResumeException;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.agent.AgentScopeWorkflowAgent;
import domi.argenticpptmaster.agent.AgentScopeWorkflowAgentFactory;
import domi.argenticpptmaster.repository.PptJobRepository;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import domi.argenticpptmaster.web.dto.PptSlideEditRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;

/**
 * {@link PptWorkflowService} 的集成测试。
 * <p>
 * 验证 PPT 任务的创建、源文件存储、文件扩展名校验、画布格式校验，
 * 以及 Agent 任务在 Spring 上下文中的完整启动流程。
 */
@SpringBootTest
class PptWorkflowServiceTests {

    @TempDir
    static Path tempDir;

    @Autowired
    private PptWorkflowService workflowService;

    @Autowired
    private PptJobRepository repository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ppt-master.workspace-path", () -> tempDir.resolve("workspace").toString());
        registry.add("ppt-master.repo-path", () -> tempDir.resolve("repo").toString());
    }

    @BeforeEach
    void setUpPptMasterScript() throws IOException {
        Path script = tempDir.resolve("repo/skills/ppt-master/scripts/project_manager.py");
        Files.createDirectories(script.getParent());
        Files.writeString(script, """
                #!/usr/bin/env python3
                import argparse
                from datetime import datetime
                from pathlib import Path

                parser = argparse.ArgumentParser()
                parser.add_argument("command")
                parser.add_argument("project_name")
                parser.add_argument("--format", default="ppt169")
                parser.add_argument("--dir", required=True)
                args = parser.parse_args()

                if args.command != "init":
                    raise SystemExit(2)

                date = datetime.now().strftime("%Y%m%d")
                project = Path(args.dir) / f"{args.project_name}_{args.format}_{date}"
                for name in ("svg_output", "svg_final", "images", "icons", "notes", "templates", "sources", "analysis", "exports"):
                    (project / name).mkdir(parents=True, exist_ok=True)
                """);
    }

    /**
     * 验证创建任务时，能够正确接收上传的 Markdown 源文件并将其持久化到工作区，
     * 任务初始化后状态为 ACCEPTED / PREPARING / WAITING_CONFIRMATION 之一。
     */
    @Test
    void createsJobAndStoresSource() {
        MockMultipartFile source = new MockMultipartFile(
                "files",
                "source.md",
                MediaType.TEXT_MARKDOWN_VALUE,
                "# Title".getBytes());

        PptJob job = workflowService.createJob(
                java.util.List.of(source),
                "demo",
                "ppt169",
                "make a concise business deck",
                "basic");

        assertThat(job.projectName()).isEqualTo("demo");
        assertThat(job.format()).isEqualTo("ppt169");
        assertThat(job.sourceFiles()).hasSize(1);
        assertThat(Files.exists(job.sourceFiles().get(0).storedPath())).isTrue();
        assertThat(job.status()).isIn(PptJobStatus.ACCEPTED, PptJobStatus.PREPARING, PptJobStatus.WAITING_CONFIRMATION);
    }

    /**
     * 验证创建任务时传入 image-enhanced 工作流模式，任务对象会正确记录该模式。
     */
    @Test
    void createsJobWithImageEnhancedMode() {
        MockMultipartFile source = new MockMultipartFile(
                "files",
                "source.md",
                MediaType.TEXT_MARKDOWN_VALUE,
                "# Title".getBytes());

        PptJob job = workflowService.createJob(
                java.util.List.of(source),
                "demo",
                "ppt169",
                "make a deck with ai images",
                "image-enhanced");

        assertThat(job.workflowMode()).isEqualTo(PptWorkflowMode.IMAGE_ENHANCED);
    }

    /**
     * 验证上传不支持的文件扩展名（如 .exe）时，
     * 服务抛出 {@link PptJobStateException} 异常并提示不支持的文件类型。
     */
    @Test
    void rejectsUnsupportedSourceExtension() {
        MockMultipartFile source = new MockMultipartFile(
                "files",
                "source.exe",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "bad".getBytes());

        assertThatThrownBy(() -> workflowService.createJob(java.util.List.of(source), "demo", "ppt169", null, null))
                .isInstanceOf(PptJobStateException.class)
                .hasMessage("unsupported source file extension: exe");
    }

    /**
     * 验证传入不支持的画布格式（如包含路径遍历的 "../bad"）时，
     * 服务抛出 {@link PptJobStateException} 异常并提示不支持的格式。
     */
    @Test
    void rejectsUnsupportedCanvasFormat() {
        MockMultipartFile source = new MockMultipartFile(
                "files",
                "source.md",
                MediaType.TEXT_MARKDOWN_VALUE,
                "# Title".getBytes());

        assertThatThrownBy(() -> workflowService.createJob(java.util.List.of(source), "demo", "../bad", null, null))
                .isInstanceOf(PptJobStateException.class)
                .hasMessage("unsupported canvas format: ../bad");
    }

    /**
     * 验证失败任务在存在已完成节点时可以恢复，且恢复后 resumeCount 增加。
     */
    @Test
    void resumeJobFromFailureIncreasesResumeCount() {
        PptJob job = new PptJob(
                java.util.UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                tempDir.resolve("jobs/demo"));
        repository.save(job);
        job.completeNode(PptJobNode.PROJECT_READY, Map.of());
        job.failNode(PptJobNode.SVG_OUTPUT_VALIDATED, "svg validation failed");

        PptJob resumed = workflowService.resumeJob(job.id());

        assertThat(resumed.status()).isEqualTo(PptJobStatus.RUNNING_AGENT);
        assertThat(resumed.resumeCount()).isEqualTo(1);
        assertThat(resumed.activeAttemptSessionId()).isEqualTo("attempt-1");
        assertThat(resumed.lastFailureNode()).isEmpty();
    }

    /**
     * 验证等待确认状态的任务调用 resume 会被拒绝。
     */
    @Test
    void rejectsResumeWhenJobIsWaitingConfirmation() {
        PptJob job = new PptJob(
                java.util.UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                tempDir.resolve("jobs/demo"));
        repository.save(job);
        job.requireConfirmation("c-1", Map.of());

        assertThatThrownBy(() -> workflowService.resumeJob(job.id()))
                .isInstanceOf(PptJobResumeException.class)
                .hasMessageContaining("waiting for confirmation");
    }

    /**
     * 验证已完成任务调用 resume 会被拒绝。
     */
    @Test
    void rejectsResumeWhenJobIsCompleted() {
        PptJob job = new PptJob(
                java.util.UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                tempDir.resolve("jobs/demo"));
        repository.save(job);
        job.complete(Path.of("var/ppt-master/exports/demo.pptx"));

        assertThatThrownBy(() -> workflowService.resumeJob(job.id()))
                .isInstanceOf(PptJobResumeException.class)
                .hasMessageContaining("not in failed state");
    }

    @Test
    void rejectsOutlineRevisionForUnknownSlideAndKeepsWaitingState() {
        PptJob job = new PptJob(
                java.util.UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                tempDir.resolve("jobs/demo-outline"));
        repository.save(job);
        job.requireConfirmation("outline-1", Map.of(
                "stage", "plan_confirmation",
                "contextData", Map.of(
                        "type", "ppt_outline",
                        "slides", List.of(Map.of("slideNo", 1)))));

        assertThatThrownBy(() -> workflowService.submitConfirmation(
                job.id(),
                "outline-1",
                true,
                Map.of(),
                null,
                PptConfirmationAction.REQUEST_REVISION,
                null,
                List.of(new PptSlideEditRequest(2, "修改"))))
                .isInstanceOf(PptJobStateException.class)
                .hasMessage("slide edit references an invalid slide number");
        assertThat(job.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
    }

    @Test
    void approvesOutlineConfirmationAndCompletesExplicitNode() {
        PptJob job = new PptJob(
                java.util.UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                tempDir.resolve("jobs/demo-outline-approved"));
        repository.save(job);
        job.waitNodeConfirmation(PptJobNode.OUTLINE_DRAFTED);
        job.requireConfirmation("outline-approve-1", Map.of(
                "stage", "outline_confirmation",
                "contextData", Map.of(
                        "type", "ppt_outline",
                        "slides", List.of(Map.of(
                                "slideNo", 1,
                                "title", "测试页面",
                                "keyMessage", "测试关键信息",
                                "bullets", List.of("测试要点"),
                                "visualSuggestion", "测试视觉方案")))));

        PptJob result = workflowService.submitConfirmation(
                job.id(), "outline-approve-1", true, Map.of(), null,
                PptConfirmationAction.APPROVE, null, List.of());

        assertThat(result.nodeExecution(PptJobNode.OUTLINE_DRAFTED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(result.nodeExecution(PptJobNode.OUTLINE_CONFIRMED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);
    }

    @Test
    void explicitCancelFailsWithoutResumingAgent() {
        PptJob job = new PptJob(
                java.util.UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                tempDir.resolve("jobs/demo-cancel"));
        repository.save(job);
        job.requireConfirmation("cancel-1", Map.of("stage", "plan_confirmation"));

        PptJob result = workflowService.submitConfirmation(
                job.id(), "cancel-1", true, Map.of(), "用户终止",
                PptConfirmationAction.CANCEL, null, List.of());

        assertThat(result.status()).isEqualTo(PptJobStatus.FAILED);
        assertThat(result.confirmation()).isEmpty();
    }

    /**
     * 测试用 Spring 配置类。
     * <p>
     * 提供 {@link AgentScopeWorkflowAgentFactory} 的 Mock Bean，
     * 返回一个始终发出 {@link RequireExternalExecutionEvent} 事件的测试 Agent，
     * 避免在集成测试中实际调用外部 AI Agent，使测试可独立运行。
     */
    @TestConfiguration
    static class TestAgentConfig {

        @Bean
        @Primary
        AgentScopeWorkflowAgentFactory agentFactory() {
            return job -> new AgentScopeWorkflowAgent() {
                @Override
                public Flux<AgentEvent> streamEvents(List<Msg> messages, RuntimeContext runtimeContext) {
                    if (messages.size() == 1 && messages.get(0).getTextContent().contains("从上一个成功节点恢复执行")) {
                        return Flux.just(
                                new AgentStartEvent("reply-2", runtimeContext.getSessionId(), "test-agent"),
                                new io.agentscope.core.event.AgentResultEvent(new io.agentscope.core.message.AssistantMessage(
                                        "checkpoint inconsistency detected")));
                    }
                    return Flux.just(
                            new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                            new RequireExternalExecutionEvent(
                                    "reply-1",
                                    List.of(new ToolUseBlock(
                                            "call-1",
                                            "request_plan_confirmation",
                                            Map.of(
                                                    "stage", "outline_confirmation",
                                                    "planSummary", "import and inspect sources",
                                                    "pendingSteps", "wait for operator approval",
                                                    "contextData", Map.of(
                                                            "type", "ppt_outline",
                                                            "slides", List.of(Map.of(
                                                                    "slideNo", 1,
                                                                    "title", "测试页面",
                                                                    "keyMessage", "测试关键信息",
                                                                    "bullets", List.of("测试要点"),
                                                                    "visualSuggestion", "测试视觉方案"))))))));
                }
            };
        }
    }
}
