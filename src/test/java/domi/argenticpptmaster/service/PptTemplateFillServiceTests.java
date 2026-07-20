package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.FillPlanStatus;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptTemplateFillAccessException;
import domi.argenticpptmaster.exception.PptTemplateFillConflictException;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PptTemplateFillServiceTests {

    @Test
    void executesOnlyServerApprovedPlanWithConfiguredToken() {
        UUID jobId = UUID.randomUUID();
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, Path.of("workspace"));
        job.updateFillPlanStatus(FillPlanStatus.CONFIRMED, 2, 0, 0);
        PptJobRepository repository = mock(PptJobRepository.class);
        PptTemplateFillPlanStore planStore = mock(PptTemplateFillPlanStore.class);
        PptTemplateFillAsyncRunner runner = mock(PptTemplateFillAsyncRunner.class);
        PptWorkflowEvents events = mock(PptWorkflowEvents.class);
        Path planPath = Path.of("workspace/analysis/fill_plan.json");
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(planStore.hasApprovedRecord(job)).thenReturn(true);
        when(planStore.findConfirmedPlan(job)).thenReturn(Optional.of(planPath));
        PptTemplateFillService service = new PptTemplateFillService(
                new PptMasterProperties(Path.of("repo"), Path.of("workspace"), "python3", Duration.ofSeconds(1),
                        "secret", 1024, 0, 0, 0, 0, null),
                repository, events, planStore, runner);

        PptJob result = service.submitPlan(jobId, "secret", null);

        assertThat(result.status()).isEqualTo(domi.argenticpptmaster.domain.PptJobStatus.PREPARING);
        verify(runner).start(jobId, planPath);
        verify(planStore, never()).storeConfirmedPlan(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(repository).save(job);
    }

    @Test
    void rejectsRequestBodyPlanWithoutServerApproval() {
        UUID jobId = UUID.randomUUID();
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, Path.of("workspace"));
        PptJobRepository repository = mock(PptJobRepository.class);
        PptTemplateFillPlanStore planStore = mock(PptTemplateFillPlanStore.class);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(planStore.hasApprovedRecord(job)).thenReturn(false);
        PptTemplateFillService service = new PptTemplateFillService(
                new PptMasterProperties(Path.of("repo"), Path.of("workspace"), "python3", Duration.ofSeconds(1),
                        "secret", 1024, 0, 0, 0, 0, null),
                repository, mock(PptWorkflowEvents.class), planStore, mock(PptTemplateFillAsyncRunner.class));

        assertThatThrownBy(() -> service.submitPlan(jobId, "secret", "{\"status\":\"confirmed\"}"))
                .isInstanceOf(PptTemplateFillConflictException.class)
                .hasMessageContaining("server-approved");
    }

    @Test
    void rejectsMissingOrWrongTokenBeforeReadingJob() {
        PptJobRepository repository = mock(PptJobRepository.class);
        PptTemplateFillService service = new PptTemplateFillService(
                new PptMasterProperties(Path.of("repo"), Path.of("workspace"), "python3", Duration.ofSeconds(1),
                        "secret", 1024, 0, 0, 0, 0, null),
                repository, mock(PptWorkflowEvents.class), mock(PptTemplateFillPlanStore.class), mock(PptTemplateFillAsyncRunner.class));

        assertThatThrownBy(() -> service.submitPlan(UUID.randomUUID(), "wrong", "{}"))
                .isInstanceOf(PptTemplateFillAccessException.class);
        verify(repository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsAlreadyStartedOrWrongWorkflowWithConflict() {
        UUID jobId = UUID.randomUUID();
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.BASIC, Path.of("workspace"));
        PptJobRepository repository = mock(PptJobRepository.class);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        PptTemplateFillService service = new PptTemplateFillService(
                new PptMasterProperties(Path.of("repo"), Path.of("workspace"), "python3", Duration.ofSeconds(1),
                        "secret", 1024, 0, 0, 0, 0, null),
                repository, mock(PptWorkflowEvents.class), mock(PptTemplateFillPlanStore.class), mock(PptTemplateFillAsyncRunner.class));

        assertThatThrownBy(() -> service.submitPlan(jobId, "secret", null))
                .isInstanceOf(PptTemplateFillConflictException.class);
    }

    @Test
    void rejectsRequestBodyEvenWhenServerApprovalExists() {
        UUID jobId = UUID.randomUUID();
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, Path.of("workspace"));
        PptJobRepository repository = mock(PptJobRepository.class);
        PptTemplateFillPlanStore planStore = mock(PptTemplateFillPlanStore.class);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(planStore.hasApprovedRecord(job)).thenReturn(true);
        when(planStore.findConfirmedPlan(job)).thenReturn(Optional.of(Path.of("workspace/analysis/fill_plan.json")));
        PptTemplateFillService service = new PptTemplateFillService(
                new PptMasterProperties(Path.of("repo"), Path.of("workspace"), "python3", Duration.ofSeconds(1),
                        "secret", 1024, 0, 0, 0, 0, null),
                repository, mock(PptWorkflowEvents.class), planStore, mock(PptTemplateFillAsyncRunner.class));

        assertThatThrownBy(() -> service.submitPlan(jobId, "secret", "{\"status\":\"confirmed\"}"))
                .isInstanceOf(PptTemplateFillConflictException.class)
                .hasMessageContaining("request body plan cannot establish approval");
    }
}
