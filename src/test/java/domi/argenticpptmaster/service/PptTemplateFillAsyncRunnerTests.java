package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PptTemplateFillAsyncRunnerTests {

    @Test
    void delegatesExecutionWithJobAndPlanPath() {
        PptTemplateFillCommandExecutor executor = org.mockito.Mockito.mock(PptTemplateFillCommandExecutor.class);
        PptTemplateFillPlanStore planStore = org.mockito.Mockito.mock(PptTemplateFillPlanStore.class);
        PptJobRepository repository = org.mockito.Mockito.mock(PptJobRepository.class);
        PptTemplateFillAsyncRunner runner = new PptTemplateFillAsyncRunner(
                executor, planStore, repository, org.mockito.Mockito.mock(PptWorkflowAsyncRunner.class));
        UUID jobId = UUID.randomUUID();
        Path plan = Path.of("workspace/analysis/fill_plan.json");

        runner.start(jobId, plan);

        verify(executor).execute(jobId, plan);
    }

    @Test
    void delegatesResumeFromCheckpointWithStoredPlan() {
        PptTemplateFillCommandExecutor executor = org.mockito.Mockito.mock(PptTemplateFillCommandExecutor.class);
        PptTemplateFillPlanStore planStore = org.mockito.Mockito.mock(PptTemplateFillPlanStore.class);
        PptJobRepository repository = org.mockito.Mockito.mock(PptJobRepository.class);
        PptTemplateFillAsyncRunner runner = new PptTemplateFillAsyncRunner(
                executor, planStore, repository, org.mockito.Mockito.mock(PptWorkflowAsyncRunner.class));
        UUID jobId = UUID.randomUUID();
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, Path.of("workspace"));
        Path plan = Path.of("workspace/analysis/fill_plan.json");
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(planStore.findConfirmedPlan(job)).thenReturn(Optional.of(plan));

        runner.resumeFromCheckpoint(jobId, PptJobNode.TEMPLATE_ANALYZED);

        verify(executor).resumeFromCheckpoint(jobId, PptJobNode.TEMPLATE_ANALYZED, plan);
    }

    @Test
    void prepareAndAnalyzeStartsPlanningAgentWhenTemplateAnalyzed() {
        PptTemplateFillCommandExecutor executor = org.mockito.Mockito.mock(PptTemplateFillCommandExecutor.class);
        PptTemplateFillPlanStore planStore = org.mockito.Mockito.mock(PptTemplateFillPlanStore.class);
        PptJobRepository repository = org.mockito.Mockito.mock(PptJobRepository.class);
        PptWorkflowAsyncRunner asyncRunner = org.mockito.Mockito.mock(PptWorkflowAsyncRunner.class);
        PptTemplateFillAsyncRunner runner = new PptTemplateFillAsyncRunner(
                executor, planStore, repository, asyncRunner);
        UUID jobId = UUID.randomUUID();
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, Path.of("workspace"));
        job.tryStartPrepare();
        org.mockito.Mockito.doAnswer(invocation -> {
            job.completeNode(PptJobNode.TEMPLATE_ANALYZED, java.util.Map.of());
            return null;
        }).when(executor).prepareAndAnalyze(jobId);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));

        runner.prepareAndAnalyze(jobId);

        verify(executor).prepareAndAnalyze(jobId);
        verify(asyncRunner).startAgent(jobId);
        assertThat(job.status()).isEqualTo(domi.argenticpptmaster.domain.PptJobStatus.RUNNING_AGENT);
    }

    @Test
    void prepareAndAnalyzeDoesNotStartAgentWhenAnalyzeDidNotComplete() {
        PptTemplateFillCommandExecutor executor = org.mockito.Mockito.mock(PptTemplateFillCommandExecutor.class);
        PptJobRepository repository = org.mockito.Mockito.mock(PptJobRepository.class);
        PptWorkflowAsyncRunner asyncRunner = org.mockito.Mockito.mock(PptWorkflowAsyncRunner.class);
        PptTemplateFillAsyncRunner runner = new PptTemplateFillAsyncRunner(
                executor, org.mockito.Mockito.mock(PptTemplateFillPlanStore.class), repository, asyncRunner);
        UUID jobId = UUID.randomUUID();
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, Path.of("workspace"));
        job.tryStartPrepare();
        when(repository.findById(jobId)).thenReturn(Optional.of(job));

        runner.prepareAndAnalyze(jobId);

        verify(executor).prepareAndAnalyze(jobId);
        verify(asyncRunner, org.mockito.Mockito.never()).startAgent(jobId);
    }
}
