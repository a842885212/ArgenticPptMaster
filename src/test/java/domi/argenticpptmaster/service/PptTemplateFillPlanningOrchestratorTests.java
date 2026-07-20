package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import domi.argenticpptmaster.domain.FillPlanStatus;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptTemplateFile;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.repository.InMemoryPptJobRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PptTemplateFillPlanningOrchestratorTests {

    @TempDir
    Path tempDir;

    @Test
    void startsPlanningAgentOnlyAfterTemplateAnalyzedAndIdempotently() {
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        PptWorkflowAsyncRunner asyncRunner = mock(PptWorkflowAsyncRunner.class);
        PptTemplateFillPlanningOrchestrator orchestrator =
                new PptTemplateFillPlanningOrchestrator(repository, asyncRunner);
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, tempDir);
        job.tryStartPrepare();
        job.completeNode(PptJobNode.TEMPLATE_ANALYZED, Map.of());
        repository.save(job);

        orchestrator.startPlanningAgentIfReady(job.id());
        orchestrator.startPlanningAgentIfReady(job.id());

        verify(asyncRunner, times(1)).startAgent(job.id());
        assertThat(repository.findById(job.id()).orElseThrow().status())
                .isEqualTo(PptJobStatus.RUNNING_AGENT);
    }

    @Test
    void doesNotStartPlanningWhenAnalysisFailedOrPlanAlreadyConfirmed() {
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        PptWorkflowAsyncRunner asyncRunner = mock(PptWorkflowAsyncRunner.class);
        PptTemplateFillPlanningOrchestrator orchestrator =
                new PptTemplateFillPlanningOrchestrator(repository, asyncRunner);
        PptJob failed = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, tempDir);
        failed.tryStartPrepare();
        failed.failNode(PptJobNode.TEMPLATE_ANALYZED, "analyze failed");
        repository.save(failed);
        orchestrator.startPlanningAgentIfReady(failed.id());
        verify(asyncRunner, never()).startAgent(failed.id());

        PptJob confirmed = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, tempDir.resolve("c"));
        confirmed.tryStartPrepare();
        confirmed.completeNode(PptJobNode.TEMPLATE_ANALYZED, Map.of());
        confirmed.updateFillPlanStatus(FillPlanStatus.CONFIRMED, 1, 0, 0);
        repository.save(confirmed);
        orchestrator.startPlanningAgentIfReady(confirmed.id());
        verify(asyncRunner, never()).startAgent(confirmed.id());
    }

    @Test
    void revisionStartsNewAttemptWithBoundedFeedbackAndPreservesTemplateRole() throws Exception {
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        PptWorkflowAsyncRunner asyncRunner = mock(PptWorkflowAsyncRunner.class);
        PptTemplateFillPlanningOrchestrator orchestrator =
                new PptTemplateFillPlanningOrchestrator(repository, asyncRunner);
        Path templatePath = tempDir.resolve("uploads/template/0-brand.pptx");
        Files.createDirectories(templatePath.getParent());
        Files.writeString(templatePath, "pptx");
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, tempDir);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 4L, templatePath));
        job.tryStartPrepare();
        job.completeNode(PptJobNode.TEMPLATE_ANALYZED, Map.of());
        job.completeNode(PptJobNode.FILL_PLAN_DRAFTED, Map.of());
        job.requireConfirmation("c1", Map.of("stage", "template_fill_plan"));
        String previousSession = job.activeAttemptSessionId();
        repository.save(job);

        orchestrator.restartPlanningAfterRevision(job.id(), "x".repeat(2500));

        PptJob revised = repository.findById(job.id()).orElseThrow();
        assertThat(revised.resumeCount()).isEqualTo(1);
        assertThat(revised.activeAttemptSessionId()).isNotEqualTo(previousSession);
        assertThat(revised.templateFillRevisionFeedback().orElseThrow()).hasSize(2000);
        assertThat(revised.template().orElseThrow().originalName()).isEqualTo("brand.pptx");
        assertThat(revised.template().orElseThrow().storedPath()).isEqualTo(templatePath);
        assertThat(revised.nodeExecution(PptJobNode.TEMPLATE_ANALYZED).status().name()).isEqualTo("COMPLETED");
        verify(asyncRunner).startAgent(job.id());
    }

    @Test
    void revisionRejectsWhenAttemptLimitReached() {
        InMemoryPptJobRepository repository = new InMemoryPptJobRepository();
        PptTemplateFillPlanningOrchestrator orchestrator =
                new PptTemplateFillPlanningOrchestrator(repository, mock(PptWorkflowAsyncRunner.class));
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, tempDir);
        job.completeNode(PptJobNode.TEMPLATE_ANALYZED, Map.of());
        for (int i = 0; i < 5; i++) {
            job.startNewResumeAttempt();
        }
        job.requireConfirmation("c1", Map.of("stage", "template_fill_plan"));
        repository.save(job);

        assertThatThrownBy(() -> orchestrator.restartPlanningAfterRevision(job.id(), "again"))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("maximum template-fill revision");
    }
}
