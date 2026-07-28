package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.agent.PptAgentRunner;
import domi.argenticpptmaster.domain.BeautifyPptxFailureKind;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

class PptWorkflowAsyncRunnerBeautifyTests {

    @Test
    void rejectBeautifyAdmissionRecordsRecoverableResourceLimitFailure() {
        UUID jobId = UUID.randomUUID();
        PptJob job = new PptJob(jobId, "beautify", "ppt169", "layout", PptWorkflowMode.BEAUTIFY_PPTX,
                Path.of("var/ppt-master/jobs/" + jobId));
        PptJobRepository repository = mock(PptJobRepository.class);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        PptWorkflowEvents events = mock(PptWorkflowEvents.class);
        AtomicReference<PptJobEvent> recorded = new AtomicReference<>();
        doAnswer(invocation -> {
            recorded.set(invocation.getArgument(1));
            return null;
        }).when(events).record(eq(job), any(PptJobEvent.class));

        new PptWorkflowAsyncRunner(repository, mock(PptAgentRunner.class), events,
                mock(BeautifyPptxAnalysisExecutor.class), new SyncTaskExecutor())
                .rejectBeautifyAdmission(jobId);

        assertThat(job.status()).isEqualTo(PptJobStatus.FAILED);
        assertThat(job.nodeExecution(PptJobNode.BEAUTIFY_ANALYZED).status()).isEqualTo(PptJobNodeStatus.FAILED);
        assertThat(job.resumable()).isTrue();
        assertThat(recorded.get().type()).isEqualTo(PptJobEventType.JOB_FAILED);
        assertThat(recorded.get().data())
                .containsEntry("failureKind", BeautifyPptxFailureKind.RESOURCE_LIMIT.name())
                .containsEntry("errorCode", "BEAUTIFY_INTAKE_FAILED")
                .doesNotContainKeys("command", "stdout", "stderr", "workspace");
    }

    @Test
    void analyzeBeautifyStartsAgentOnlyOnceAfterSuccessfulAnalysis() {
        UUID jobId = UUID.randomUUID();
        PptJob job = new PptJob(jobId, "beautify", "ppt169", "layout", PptWorkflowMode.BEAUTIFY_PPTX,
                Path.of("var/ppt-master/jobs/" + jobId));
        PptJobRepository repository = mock(PptJobRepository.class);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        BeautifyPptxAnalysisExecutor executor = mock(BeautifyPptxAnalysisExecutor.class);
        doAnswer(invocation -> {
            job.completeNode(PptJobNode.PROJECT_READY, Map.of("resolvedFormat", "ppt169"));
            job.completeNode(PptJobNode.BEAUTIFY_ANALYZED, Map.of("sourcePageCount", 1));
            return null;
        }).when(executor).execute(job);
        PptAgentRunner agentRunner = mock(PptAgentRunner.class);
        AtomicInteger agentStarts = new AtomicInteger();
        doAnswer(invocation -> {
            agentStarts.incrementAndGet();
            return null;
        }).when(agentRunner).start(job);

        PptWorkflowAsyncRunner runner = new PptWorkflowAsyncRunner(
                repository, agentRunner, mock(PptWorkflowEvents.class), executor, new SyncTaskExecutor());
        runner.analyzeBeautifyAndStartAgent(jobId);
        runner.analyzeBeautifyAndStartAgent(jobId);

        assertThat(agentStarts.get()).isEqualTo(1);
        verify(executor, times(2)).execute(job);
    }
}
