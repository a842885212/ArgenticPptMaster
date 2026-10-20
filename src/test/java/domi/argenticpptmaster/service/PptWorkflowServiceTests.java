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
import domi.argenticpptmaster.security.FixedPptAccessContextResolver;
import domi.argenticpptmaster.security.PptAccessContext;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "ppt-master.beautify-pptx.enabled=true",
        "ppt-master.beautify-pptx.allowed-tenants[0]=test-tenant",
        "ppt-master.native-enhance-pptx.enabled=true",
        "ppt-master.native-enhance-pptx.allowed-tenants[0]=test-tenant"
})
class PptWorkflowServiceTests {

    @TempDir
    static Path tempDir;

    private static ThreadPoolTaskExecutor workflowTestExecutor;

    @Autowired
    private PptWorkflowService workflowService;

    @Autowired
    private PptJobRepository repository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ppt-master.workspace-path", () -> tempDir.resolve("workspace").toString());
        registry.add("ppt-master.repo-path", () -> tempDir.resolve("repo").toString());
        registry.add("ppt-master.template-fill-enabled", () -> "true");
        registry.add("ppt-master.template-fill-allowed-tenants[0]", () -> "test-tenant");
        registry.add("ppt-master.local-access.enabled", () -> "false");
        registry.add("ppt-master.native-enhance-pptx.max-source-size", () -> "16");
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

    @AfterAll
    static void stopWorkflowTestExecutor() {
        if (workflowTestExecutor != null) {
            workflowTestExecutor.shutdown();
        }
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
        assertThat(job.status()).isIn(PptJobStatus.ACCEPTED, PptJobStatus.PREPARING, PptJobStatus.RUNNING_AGENT,
                PptJobStatus.WAITING_CONFIRMATION);
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

    @Test
    void createsBeautifyJobForExactlyOnePptxSource() {
        MockMultipartFile source = new MockMultipartFile(
                "files", "source.PPTX", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());

        PptJob job = workflowService.createJob(List.of(source), "demo", "ppt169", "layout only", "beautify_pptx");

        assertThat(job.workflowMode()).isEqualTo(PptWorkflowMode.BEAUTIFY_PPTX);
        assertThat(job.sourceFiles()).singleElement().satisfies(file ->
                assertThat(file.storedPath().getParent().getFileName().toString()).isEqualTo("uploads"));
        assertThat(job.hasOwnership()).isTrue();
        assertThat(Files.isRegularFile(job.workspacePath().resolve("lifecycle/manifest.json"))).isTrue();
    }

    @Test
    void rejectsInvalidBeautifySourcesBeforeCreatingJob() {
        MockMultipartFile first = new MockMultipartFile(
                "files", "first.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());
        MockMultipartFile second = new MockMultipartFile(
                "files", "second.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());
        MockMultipartFile legacy = new MockMultipartFile(
                "files", "legacy.ppt", MediaType.APPLICATION_OCTET_STREAM_VALUE, "ppt".getBytes());

        assertThatThrownBy(() -> workflowService.createJob(List.of(first, second), "demo", "ppt169", null, "beautify-pptx"))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> workflowService.createJob(List.of(legacy), "demo", "ppt169", null, "beautify-pptx"))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining(".pptx");
    }

    @Test
    void rejectsBeautifyTemplateFields() {
        MockMultipartFile source = new MockMultipartFile(
                "files", "source.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "template.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());

        assertThatThrownBy(() -> workflowService.createJob(new PptJobCreateCommand(
                List.of(source), template, "demo", "ppt169", null, "beautify-pptx", null)))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("templateFile");
        assertThatThrownBy(() -> workflowService.createJob(new PptJobCreateCommand(
                List.of(source), null, "demo", "ppt169", null, "beautify-pptx", "{}")))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("templateConstraints");
    }

    @Test
    void createsNativeEnhanceJobForExactlyOnePptxSource() {
        MockMultipartFile source = new MockMultipartFile(
                "files", "source.PPTX", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());

        PptJob job = workflowService.createJob(
                List.of(source), "demo", "ppt169", "notes only", "native_enhance_pptx");

        assertThat(job.workflowMode()).isEqualTo(PptWorkflowMode.NATIVE_ENHANCE_PPTX);
        assertThat(job.sourceFiles()).singleElement().satisfies(file ->
                assertThat(file.storedPath().getParent().getFileName().toString()).isEqualTo("uploads"));
    }

    @Test
    void rejectsInvalidNativeEnhanceSourcesBeforeCreatingJob() {
        MockMultipartFile first = new MockMultipartFile(
                "files", "first.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());
        MockMultipartFile second = new MockMultipartFile(
                "files", "second.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());
        MockMultipartFile legacy = new MockMultipartFile(
                "files", "legacy.ppt", MediaType.APPLICATION_OCTET_STREAM_VALUE, "ppt".getBytes());

        assertThatThrownBy(() -> workflowService.createJob(
                List.of(first, second), "demo", "ppt169", null, "native-enhance-pptx"))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> workflowService.createJob(
                List.of(legacy), "demo", "ppt169", null, "native-enhance-pptx"))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining(".pptx");
    }

    @Test
    void rejectsNativeEnhanceTemplateFields() {
        MockMultipartFile source = new MockMultipartFile(
                "files", "source.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "template.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());

        assertThatThrownBy(() -> workflowService.createJob(new PptJobCreateCommand(
                List.of(source), template, "demo", "ppt169", null, "native-enhance-pptx", null)))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("templateFile");
        assertThatThrownBy(() -> workflowService.createJob(new PptJobCreateCommand(
                List.of(source), null, "demo", "ppt169", null, "native-enhance-pptx", "{}")))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("templateConstraints");
    }

    @Test
    void rejectsOversizedNativeEnhanceSourceBeforeCreatingJob() {
        byte[] payload = new byte[20];
        MockMultipartFile source = new MockMultipartFile(
                "files", "source.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, payload);

        assertThatThrownBy(() -> workflowService.createJob(
                List.of(source), "demo", "ppt169", null, "native-enhance-pptx"))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("NATIVE_ENHANCE_UPLOAD_TOO_LARGE");
    }

    @Test
    void createsTemplateFillJobWithRoleIsolatedUploadsAndStartsPrepareFlow() {
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "brand.PPTX", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());
        MockMultipartFile source = new MockMultipartFile(
                "files", "source.md", MediaType.TEXT_MARKDOWN_VALUE, "# Title".getBytes());

        PptJob job = workflowService.createJob(new PptJobCreateCommand(
                List.of(source), template, "demo", "ppt169", "fill", "template-fill"));

        assertThat(job.workflowMode()).isEqualTo(PptWorkflowMode.TEMPLATE_FILL);
        assertThat(job.status()).isEqualTo(PptJobStatus.PREPARING);
        assertThat(job.template()).isPresent();
        assertThat(job.template().orElseThrow().storedPath().getParent().getFileName().toString())
                .isEqualTo("template");
        assertThat(job.sourceFiles().get(0).storedPath().getParent().getFileName().toString())
                .isEqualTo("content");
    }

    @Test
    void rejectsTemplateFillWithoutValidTemplate() {
        MockMultipartFile source = new MockMultipartFile(
                "files", "source.md", MediaType.TEXT_MARKDOWN_VALUE, "# Title".getBytes());
        MockMultipartFile invalidTemplate = new MockMultipartFile(
                "templateFile", "brand.pdf", MediaType.APPLICATION_PDF_VALUE, "pdf".getBytes());

        assertThatThrownBy(() -> workflowService.createJob(new PptJobCreateCommand(
                List.of(source), invalidTemplate, "demo", "ppt169", null, "template-fill")))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("template");
    }

    @Test
    void rejectsTemplateFileForExistingWorkflow() {
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "brand.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());
        MockMultipartFile source = new MockMultipartFile(
                "files", "source.md", MediaType.TEXT_MARKDOWN_VALUE, "# Title".getBytes());

        assertThatThrownBy(() -> workflowService.createJob(new PptJobCreateCommand(
                List.of(source), template, "demo", "ppt169", null, "basic")))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("templateFile");
    }

    @Test
    void rejectsUnknownWorkflowMode() {
        MockMultipartFile source = new MockMultipartFile(
                "files", "source.md", MediaType.TEXT_MARKDOWN_VALUE, "# Title".getBytes());

        assertThatThrownBy(() -> workflowService.createJob(
                List.of(source), "demo", "ppt169", null, "unknown-mode"))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("unsupported workflow mode");
    }

    @Test
    void keepsContentPptxInContentRole() {
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "brand.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());
        MockMultipartFile contentPptx = new MockMultipartFile(
                "files", "reference.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());

        PptJob job = workflowService.createJob(new PptJobCreateCommand(
                List.of(contentPptx), template, "demo", "ppt169", null, "template-fill"));

        assertThat(job.sourceFiles()).singleElement().satisfies(source -> {
            assertThat(source.originalName()).isEqualTo("reference.pptx");
            assertThat(source.storedPath().getParent().getFileName().toString()).isEqualTo("content");
        });
    }

    @Test
    void rejectsTraversalInUploadName() {
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "brand.pptx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "pptx".getBytes());
        MockMultipartFile source = new MockMultipartFile(
                "files", "../outside.md", MediaType.TEXT_MARKDOWN_VALUE, "# Title".getBytes());

        assertThatThrownBy(() -> workflowService.createJob(new PptJobCreateCommand(
                List.of(source), template, "demo", "ppt169", null, "template-fill")))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("invalid file name");
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

    @Test
    void rejectsCompletedExportOutsideJobWorkspace() {
        PptJob job = new PptJob(
                java.util.UUID.randomUUID(), "demo", "ppt169", null, PptWorkflowMode.TEMPLATE_FILL,
                tempDir.resolve("jobs/export-guard"));
        job.assignOwnership("user-1", "test-tenant");
        job.complete(tempDir.resolve("outside.pptx"));
        repository.save(job);

        assertThatThrownBy(() -> workflowService.exportPath(job.id()))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("export path");
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
    void beautifyConfirmationApprovesCurrentDraftAndSupportsRevision() {
        Path project = tempDir.resolve("jobs/beautify-confirmation");
        PptJob job = new PptJob(java.util.UUID.randomUUID(), "beautify", "ppt169", null,
                PptWorkflowMode.BEAUTIFY_PPTX, project);
        job.assignOwnership("user-1", "test-tenant");
        job.prepareProject(project);
        job.completeNode(PptJobNode.BEAUTIFY_ANALYZED, Map.of("sourcePageCount", 1));
        BeautifyPptxPlanStore.PlanMetadata draft = BeautifyPptxPlanStore.storeDraft(job, """
                {"version":1,"status":"draft","sourcePageCount":1,"hardConstraints":{"textVerbatim":true,
                "dataVerbatim":true,"pageMapping":"1:1"},"identityRecommendation":{"source":"theme",
                "candidates":[{"id":"theme"},{"id":"observed"}],"paletteCandidateId":"theme",
                "fontCandidateId":"theme","bodySizeCandidateId":"theme","rationale":"theme"},
                "ignored":[],"needsConfirmation":[],"validationStrictness":"strict"}
                """);
        job.completeNode(PptJobNode.BEAUTIFY_PLAN_DRAFTED, Map.of());
        job.markBeautifyPlanDrafted();
        job.requireConfirmation("beautify-1", Map.of("stage", "beautify_plan_confirmation", "contextData", Map.of(
                "type", "beautify_pptx_plan", "version", 1, "digest", draft.digest())));
        repository.save(job);

        PptJob revised = workflowService.submitConfirmation(job.id(), "beautify-1", true, Map.of(), "修改配色",
                PptConfirmationAction.REQUEST_REVISION, null, List.of());
        assertThat(revised.beautifyPlanStatus()).isEqualTo("rejected");
        assertThat(revised.beautifyRevisionFeedback()).contains("修改配色");
        assertThat(revised.nodeExecution(PptJobNode.BEAUTIFY_PLAN_DRAFTED).status())
                .isEqualTo(PptJobNodeStatus.PENDING);
        assertThat(revised.nodeExecution(PptJobNode.BEAUTIFY_ANALYZED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);

        BeautifyPptxPlanStore.PlanMetadata draft2 = BeautifyPptxPlanStore.storeDraft(revised, """
                {"version":2,"status":"draft","sourcePageCount":1,"hardConstraints":{"textVerbatim":true,
                "dataVerbatim":true,"pageMapping":"1:1"},"identityRecommendation":{"source":"observed",
                "candidates":[{"id":"theme"},{"id":"observed"}],"paletteCandidateId":"observed",
                "fontCandidateId":"observed","bodySizeCandidateId":"observed","rationale":"observed"},
                "ignored":[],"needsConfirmation":[],"validationStrictness":"strict"}
                """);
        revised.completeNode(PptJobNode.BEAUTIFY_PLAN_DRAFTED, Map.of());
        revised.markBeautifyPlanDrafted();
        revised.requireConfirmation("beautify-2", Map.of("stage", "beautify_plan_confirmation", "contextData", Map.of(
                "type", "beautify_pptx_plan", "version", 2, "digest", draft2.digest())));
        repository.save(revised);

        PptJob approved = workflowService.submitConfirmation(revised.id(), "beautify-2", true,
                Map.of("identitySource", "observed"), null,
                PptConfirmationAction.APPROVE, null, List.of());
        assertThat(approved.nodeExecution(PptJobNode.BEAUTIFY_PLAN_CONFIRMED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(approved.beautifyPlanStatus()).isEqualTo("confirmed");
        assertThat(BeautifyPptxPlanStore.read(approved).orElseThrow().status()).isEqualTo("confirmed");
        assertThat(BeautifyPptxPlanStore.hasIdentityLock(approved)).isTrue();
    }

    @Test
    void beautifyCancelRejectsLaterApprovalAndRevisionHitsAttemptLimit() {
        Path project = tempDir.resolve("jobs/beautify-cancel");
        PptJob job = new PptJob(java.util.UUID.randomUUID(), "beautify", "ppt169", null,
                PptWorkflowMode.BEAUTIFY_PPTX, project);
        job.assignOwnership("user-1", "test-tenant");
        job.prepareProject(project);
        job.completeNode(PptJobNode.BEAUTIFY_ANALYZED, Map.of("sourcePageCount", 1));
        BeautifyPptxPlanStore.PlanMetadata draft = BeautifyPptxPlanStore.storeDraft(job, """
                {"version":1,"status":"draft","sourcePageCount":1,"hardConstraints":{"textVerbatim":true,
                "dataVerbatim":true,"pageMapping":"1:1"},"identityRecommendation":{"source":"theme",
                "candidates":[{"id":"theme"},{"id":"observed"}],"paletteCandidateId":"theme",
                "fontCandidateId":"theme","bodySizeCandidateId":"theme","rationale":"theme"},
                "ignored":[],"needsConfirmation":[],"validationStrictness":"strict"}
                """);
        job.completeNode(PptJobNode.BEAUTIFY_PLAN_DRAFTED, Map.of());
        job.requireConfirmation("beautify-cancel", Map.of("stage", "beautify_plan_confirmation", "contextData", Map.of(
                "type", "beautify_pptx_plan", "version", 1, "digest", draft.digest())));
        repository.save(job);

        PptJob cancelled = workflowService.submitConfirmation(job.id(), "beautify-cancel", false, Map.of(), "stop",
                PptConfirmationAction.CANCEL, null, List.of());
        assertThat(cancelled.status()).isEqualTo(PptJobStatus.FAILED);
        assertThatThrownBy(() -> workflowService.submitConfirmation(job.id(), "beautify-cancel", true, Map.of(), null,
                PptConfirmationAction.APPROVE, null, List.of()))
                .isInstanceOf(PptJobStateException.class);

        PptJob revising = new PptJob(java.util.UUID.randomUUID(), "beautify", "ppt169", null,
                PptWorkflowMode.BEAUTIFY_PPTX, tempDir.resolve("jobs/beautify-limit"));
        revising.assignOwnership("user-1", "test-tenant");
        revising.prepareProject(revising.workspacePath());
        revising.completeNode(PptJobNode.BEAUTIFY_ANALYZED, Map.of("sourcePageCount", 1));
        for (int i = 0; i < 5; i++) {
            revising.startNewResumeAttempt();
        }
        BeautifyPptxPlanStore.PlanMetadata limitDraft = BeautifyPptxPlanStore.storeDraft(revising, """
                {"version":1,"status":"draft","sourcePageCount":1,"hardConstraints":{"textVerbatim":true,
                "dataVerbatim":true,"pageMapping":"1:1"},"identityRecommendation":{"source":"theme",
                "candidates":[{"id":"theme"},{"id":"observed"}],"paletteCandidateId":"theme",
                "fontCandidateId":"theme","bodySizeCandidateId":"theme","rationale":"theme"},
                "ignored":[],"needsConfirmation":[],"validationStrictness":"strict"}
                """);
        revising.completeNode(PptJobNode.BEAUTIFY_PLAN_DRAFTED, Map.of());
        revising.requireConfirmation("beautify-limit", Map.of("stage", "beautify_plan_confirmation", "contextData", Map.of(
                "type", "beautify_pptx_plan", "version", 1, "digest", limitDraft.digest())));
        repository.save(revising);

        assertThatThrownBy(() -> workflowService.submitConfirmation(revising.id(), "beautify-limit", true, Map.of(),
                "again", PptConfirmationAction.REQUEST_REVISION, null, List.of()))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("maximum beautify revision attempts");
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
        FixedPptAccessContextResolver pptAccessContextResolver() {
            return new FixedPptAccessContextResolver(
                    PptAccessContext.user("user-1", "test-tenant", java.util.Set.of()));
        }

        /** 为工作流提供可在测试结束前确定性关闭的执行器。 */
        @Bean(name = "taskExecutor")
        TaskExecutor taskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(32);
            executor.setThreadNamePrefix("ppt-workflow-test-");
            executor.setWaitForTasksToCompleteOnShutdown(true);
            executor.setAwaitTerminationSeconds(5);
            executor.initialize();
            workflowTestExecutor = executor;
            return executor;
        }

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
