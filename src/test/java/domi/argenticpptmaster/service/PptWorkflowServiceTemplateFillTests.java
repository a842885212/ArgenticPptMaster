package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.FillPlanStatus;
import domi.argenticpptmaster.domain.PptConfirmationAction;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptTemplateFile;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.domain.TemplateFillPlanMetadata;
import domi.argenticpptmaster.exception.PptJobResumeException;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.exception.PptTemplateFillConflictException;
import domi.argenticpptmaster.repository.InMemoryPptJobRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class PptWorkflowServiceTemplateFillTests {

    @TempDir
    Path tempDir;

    @Test
    void rejectsOversizedTemplateUpload() {
        PptWorkflowService service = serviceWithLimits(100, 100, 200);
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "big.pptx", "application/octet-stream", new byte[150]);
        MockMultipartFile content = new MockMultipartFile(
                "files", "source.md", "text/markdown", "# ok".getBytes());

        assertThatThrownBy(() -> service.createJob(new PptJobCreateCommand(
                List.of(content), template, "demo", "ppt169", null, "template-fill")))
                .hasMessageContaining("TEMPLATE_FILL_UPLOAD_TOO_LARGE");
    }

    @Test
    void rejectsTotalUploadOverflow() {
        PptWorkflowService service = serviceWithLimits(100, 100, 150);
        MockMultipartFile template = new MockMultipartFile(
                "templateFile", "t.pptx", "application/octet-stream", new byte[80]);
        MockMultipartFile content = new MockMultipartFile(
                "files", "source.md", "text/markdown", new byte[80]);

        assertThatThrownBy(() -> service.createJob(new PptJobCreateCommand(
                List.of(content), template, "demo", "ppt169", null, "template-fill")))
                .hasMessageContaining("TEMPLATE_FILL_UPLOAD_TOO_LARGE");
    }

    @Test
    void routesTemplateFillResumeToDedicatedRunner() throws Exception {
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        PptWorkflowAsyncRunner agentRunner = mock(PptWorkflowAsyncRunner.class);
        PptTemplateFillAsyncRunner templateFillRunner = mock(PptTemplateFillAsyncRunner.class);
        PptWorkflowService service = new PptWorkflowService(
                PptMasterProperties.forTest(tempDir, tempDir),
                repository, mock(PptWorkflowEvents.class), agentRunner, templateFillRunner,
                mock(PptTemplateFillPlanningOrchestrator.class), mock(PptTemplateFillPlanStore.class));
        Path workspace = tempDir.resolve("resume-route");
        Path project = workspace.resolve("projects/demo");
        Files.createDirectories(project.resolve("analysis"));
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"),
                project.resolve("analysis/template.slide_library.json"));
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.prepareProject(project);
        job.completeNode(PptJobNode.TEMPLATE_ANALYZED, Map.of());
        job.failNode(PptJobNode.FILL_PLAN_VALIDATED, "check failed");
        repository.save(job);

        PptJob resumed = service.resumeJob(job.id());

        verify(templateFillRunner).resumeFromCheckpoint(job.id(), PptJobNode.TEMPLATE_ANALYZED);
        verify(agentRunner, never()).resumeFromCheckpoint(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(resumed.status()).isEqualTo(PptJobStatus.PREPARING);
    }

    @Test
    void approvesTemplateFillPlanAndSchedulesNativeExecutionOnce() throws Exception {
        TestContext context = waitingTemplateFillContext();
        TemplateFillPlanMetadata draft = context.planStore.storeDraftPlan(
                context.job, context.validDraft, context.slideLibrary);
        context.job.requireConfirmation("tf-1", confirmationPayload(draft));

        PptJob approved = context.service.submitConfirmation(
                context.job.id(), "tf-1", true, Map.of(), null,
                PptConfirmationAction.APPROVE, null, List.of(), null, List.of(), null);

        assertThat(approved.nodeExecution(PptJobNode.FILL_PLAN_CONFIRMED).status().name()).isEqualTo("COMPLETED");
        assertThat(approved.fillPlanStatus()).isEqualTo(FillPlanStatus.CONFIRMED);
        verify(context.templateFillRunner).start(context.job.id(), context.workspace.resolve("analysis/fill_plan.json"));
        verify(context.planningOrchestrator, never()).restartPlanningAfterRevision(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsStaleVersionDigestMismatchWrongStatusAndRepeatApprove() throws Exception {
        TestContext context = waitingTemplateFillContext();
        TemplateFillPlanMetadata draft = context.planStore.storeDraftPlan(
                context.job, context.validDraft, context.slideLibrary);
        Map<String, Object> payload = confirmationPayload(draft);
        context.job.requireConfirmation("tf-1", payload);

        @SuppressWarnings("unchecked")
        Map<String, Object> contextData = new java.util.LinkedHashMap<>(
                (Map<String, Object>) payload.get("contextData"));
        contextData.put("version", draft.version() + 1);
        context.job.requireConfirmation("tf-1", Map.of("stage", "template_fill_plan", "contextData", contextData));
        assertThatThrownBy(() -> context.service.submitConfirmation(
                context.job.id(), "tf-1", true, Map.of(), null,
                PptConfirmationAction.APPROVE, null, List.of(), null, List.of(), null))
                .isInstanceOf(PptTemplateFillConflictException.class)
                .hasMessageContaining("version");

        contextData.put("version", draft.version());
        contextData.put("digest", "deadbeef");
        context.job.requireConfirmation("tf-1", Map.of("stage", "template_fill_plan", "contextData", contextData));
        assertThatThrownBy(() -> context.service.submitConfirmation(
                context.job.id(), "tf-1", true, Map.of(), null,
                PptConfirmationAction.APPROVE, null, List.of(), null, List.of(), null))
                .isInstanceOf(PptTemplateFillConflictException.class)
                .hasMessageContaining("digest");

        context.job.requireConfirmation("tf-1", confirmationPayload(draft));
        context.service.submitConfirmation(
                context.job.id(), "tf-1", true, Map.of(), null,
                PptConfirmationAction.APPROVE, null, List.of(), null, List.of(), null);
        assertThatThrownBy(() -> context.service.submitConfirmation(
                context.job.id(), "tf-1", true, Map.of(), null,
                PptConfirmationAction.APPROVE, null, List.of(), null, List.of(), null))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("waiting for confirmation");
        verify(context.templateFillRunner, times(1)).start(
                org.mockito.ArgumentMatchers.eq(context.job.id()), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void concurrentApproveAllowsOnlyOneExecutionSchedule() throws Exception {
        TestContext context = waitingTemplateFillContext();
        TemplateFillPlanMetadata draft = context.planStore.storeDraftPlan(
                context.job, context.validDraft, context.slideLibrary);
        context.job.requireConfirmation("tf-1", confirmationPayload(draft));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            Future<?> first = pool.submit(() -> {
                ready.countDown();
                ready.await();
                try {
                    context.service.submitConfirmation(
                            context.job.id(), "tf-1", true, Map.of(), null,
                            PptConfirmationAction.APPROVE, null, List.of(), null, List.of(), null);
                    successes.incrementAndGet();
                } catch (RuntimeException ex) {
                    conflicts.incrementAndGet();
                }
                return null;
            });
            Future<?> second = pool.submit(() -> {
                ready.countDown();
                ready.await();
                try {
                    context.service.submitConfirmation(
                            context.job.id(), "tf-1", true, Map.of(), null,
                            PptConfirmationAction.APPROVE, null, List.of(), null, List.of(), null);
                    successes.incrementAndGet();
                } catch (RuntimeException ex) {
                    conflicts.incrementAndGet();
                }
                return null;
            });
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        verify(context.templateFillRunner, times(1)).start(
                org.mockito.ArgumentMatchers.eq(context.job.id()), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void revisionRestartsPlanningWithBoundedFeedbackAndNewAttempt() throws Exception {
        TestContext context = waitingTemplateFillContext();
        TemplateFillPlanMetadata draft = context.planStore.storeDraftPlan(
                context.job, context.validDraft, context.slideLibrary);
        context.job.requireConfirmation("tf-1", confirmationPayload(draft));
        String longFeedback = "x".repeat(2500);

        context.service.submitConfirmation(
                context.job.id(), "tf-1", true, Map.of(), longFeedback,
                PptConfirmationAction.REQUEST_REVISION, longFeedback, List.of(), null, List.of(), null);

        verify(context.planningOrchestrator).restartPlanningAfterRevision(context.job.id(), longFeedback);
    }

    @Test
    void cancelRejectsWithoutRestartingOrConfirmingPlan() throws Exception {
        TestContext context = waitingTemplateFillContext();
        TemplateFillPlanMetadata draft = context.planStore.storeDraftPlan(
                context.job, context.validDraft, context.slideLibrary);
        context.job.requireConfirmation("tf-1", confirmationPayload(draft));

        PptJob rejected = context.service.submitConfirmation(
                context.job.id(), "tf-1", false, Map.of(), "nope",
                PptConfirmationAction.CANCEL, null, List.of(), null, List.of(), null);

        assertThat(rejected.status()).isEqualTo(PptJobStatus.FAILED);
        assertThat(rejected.fillPlanStatus()).isEqualTo(FillPlanStatus.DRAFT);
        verify(context.templateFillRunner, never()).start(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(context.planningOrchestrator, never()).restartPlanningAfterRevision(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> context.service.submitConfirmation(
                context.job.id(), "tf-1", true, Map.of(), null,
                PptConfirmationAction.APPROVE, null, List.of(), null, List.of(), null))
                .isInstanceOf(PptJobStateException.class);
    }

    @Test
    void resumeFallsBackWhenAnalysisArtifactsMissing() throws Exception {
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        PptTemplateFillAsyncRunner templateFillRunner = mock(PptTemplateFillAsyncRunner.class);
        PptWorkflowService service = new PptWorkflowService(
                PptMasterProperties.forTest(tempDir, tempDir),
                repository, mock(PptWorkflowEvents.class), mock(PptWorkflowAsyncRunner.class),
                templateFillRunner, mock(PptTemplateFillPlanningOrchestrator.class),
                mock(PptTemplateFillPlanStore.class));
        Path workspace = tempDir.resolve("resume-job");
        Path project = workspace.resolve("projects/demo");
        Files.createDirectories(project.resolve("analysis"));
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.prepareProject(project);
        job.completeNode(PptJobNode.PROJECT_READY, Map.of());
        job.completeNode(PptJobNode.TEMPLATE_ANALYZED, Map.of());
        job.failNode(PptJobNode.FILL_PLAN_VALIDATED, "later failure");
        repository.save(job);

        service.resumeJob(job.id());

        verify(templateFillRunner).resumeFromCheckpoint(job.id(), PptJobNode.PROJECT_READY);
    }

    @Test
    void resumeRejectsWhenProjectMissingEntirely() {
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        PptWorkflowService service = new PptWorkflowService(
                PptMasterProperties.forTest(tempDir, tempDir),
                repository, mock(PptWorkflowEvents.class), mock(PptWorkflowAsyncRunner.class),
                mock(PptTemplateFillAsyncRunner.class), mock(PptTemplateFillPlanningOrchestrator.class),
                mock(PptTemplateFillPlanStore.class));
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, tempDir.resolve("missing"));
        job.completeNode(PptJobNode.TEMPLATE_ANALYZED, Map.of());
        job.failNode(PptJobNode.FILL_PLAN_VALIDATED, "later failure");
        repository.save(job);

        assertThatThrownBy(() -> service.resumeJob(job.id()))
                .isInstanceOf(PptJobResumeException.class)
                .hasMessageContaining("re-prepare");
    }

    private TestContext waitingTemplateFillContext() throws Exception {
        Path workspace = tempDir.resolve("tf-" + UUID.randomUUID());
        Path project = workspace.resolve("projects/demo");
        Files.createDirectories(project.resolve("analysis"));
        Path slideLibrary = project.resolve("analysis/template.slide_library.json");
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"), slideLibrary);
        String validDraft = Files.readString(Path.of(
                getClass().getResource("/template-fill/fill-plan-draft-valid.json").toURI()));

        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        PptTemplateFillAsyncRunner templateFillRunner = mock(PptTemplateFillAsyncRunner.class);
        PptTemplateFillPlanningOrchestrator planningOrchestrator = mock(PptTemplateFillPlanningOrchestrator.class);
        PptTemplateFillPlanStore planStore = new PptTemplateFillPlanStore(
                PptMasterProperties.forTest(tempDir, tempDir), new TemplateFillPlanValidator());
        PptWorkflowService service = new PptWorkflowService(
                PptMasterProperties.forTest(tempDir, tempDir),
                repository, mock(PptWorkflowEvents.class), mock(PptWorkflowAsyncRunner.class),
                templateFillRunner, planningOrchestrator, planStore);
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.prepareProject(project);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L,
                workspace.resolve("uploads/template/0-brand.pptx")));
        job.completeNode(PptJobNode.TEMPLATE_ANALYZED, Map.of());
        job.completeNode(PptJobNode.FILL_PLAN_DRAFTED, Map.of());
        repository.save(job);
        return new TestContext(service, job, planStore, templateFillRunner, planningOrchestrator,
                workspace, slideLibrary, validDraft);
    }

    private static Map<String, Object> confirmationPayload(TemplateFillPlanMetadata draft) {
        return Map.of(
                "stage", "template_fill_plan",
                "contextData", Map.of(
                        "type", "template_fill_plan",
                        "version", draft.version(),
                        "digest", draft.digest(),
                        "pages", List.of(Map.of(
                                "outputOrder", 1,
                                "templateSlideIndex", 1,
                                "layoutNote", "cover",
                                "slotMappings", List.of(Map.of(
                                        "slotId", "s01_sh1",
                                        "sourceRef", "content:content.md",
                                        "preview", "模板填充")),
                                "omittedContent", List.of("附录"),
                                "capacityRisks", List.of("接近上限"),
                                "tableChartHandling", List.of(Map.of("targetId", "s02_tbl1", "strategy", "摘要")),
                                "acceptedWarnings", List.of("warn:overflow")))));
    }

    private static PptWorkflowService serviceWithLimits(long templateMax, long contentMax, long totalMax) {
        PptMasterProperties properties = new PptMasterProperties(
                Path.of("repo"), Path.of("workspace"), "python3", java.time.Duration.ofMinutes(1), null, 1024,
                templateMax, contentMax, totalMax, 2, null);
        return new PptWorkflowService(
                properties,
                new InMemoryPptJobRepository(),
                mock(PptWorkflowEvents.class),
                mock(PptWorkflowAsyncRunner.class),
                mock(PptTemplateFillAsyncRunner.class),
                mock(PptTemplateFillPlanningOrchestrator.class),
                mock(PptTemplateFillPlanStore.class));
    }

    private record TestContext(
            PptWorkflowService service,
            PptJob job,
            PptTemplateFillPlanStore planStore,
            PptTemplateFillAsyncRunner templateFillRunner,
            PptTemplateFillPlanningOrchestrator planningOrchestrator,
            Path workspace,
            Path slideLibrary,
            String validDraft) {
    }
}
