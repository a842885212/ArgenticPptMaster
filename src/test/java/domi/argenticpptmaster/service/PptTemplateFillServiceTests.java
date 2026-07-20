package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.config.PptMasterProperties;
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
    void acceptsConfirmedPlanOnlyWithConfiguredTokenAndStartsOnce() {
        UUID jobId = UUID.randomUUID();
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, Path.of("workspace"));
        PptJobRepository repository = mock(PptJobRepository.class);
        PptTemplateFillPlanStore planStore = mock(PptTemplateFillPlanStore.class);
        PptTemplateFillAsyncRunner runner = mock(PptTemplateFillAsyncRunner.class);
        PptWorkflowEvents events = mock(PptWorkflowEvents.class);
        Path planPath = Path.of("workspace/analysis/fill_plan.json");
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(planStore.storeConfirmedPlan(job, "{\"status\":\"confirmed\"}")).thenReturn(planPath);
        PptTemplateFillService service = new PptTemplateFillService(
                new PptMasterProperties(Path.of("repo"), Path.of("workspace"), "python3", Duration.ofSeconds(1),
                        "secret", 1024, 0, 0, 0, 0, null),
                repository, events, planStore, runner);

        PptJob result = service.submitPlan(jobId, "secret", "{\"status\":\"confirmed\"}");

        assertThat(result.status()).isEqualTo(domi.argenticpptmaster.domain.PptJobStatus.PREPARING);
        verify(runner).start(jobId, planPath);
        verify(repository).save(job);
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

        assertThatThrownBy(() -> service.submitPlan(jobId, "secret", "{\"status\":\"confirmed\"}"))
                .isInstanceOf(PptTemplateFillConflictException.class);
    }
}
