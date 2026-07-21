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
import domi.argenticpptmaster.security.FailClosedPptAccessContextResolver;
import domi.argenticpptmaster.security.FixedPptAccessContextResolver;
import domi.argenticpptmaster.security.PptAccessContext;
import domi.argenticpptmaster.security.PptJobAccessAuthorizer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PptTemplateFillServiceTests {

    @Test
    void executesOnlyServerApprovedPlanWithConfiguredToken() {
        UUID jobId = UUID.randomUUID();
        PptJob job = ownedTemplateFillJob(jobId);
        job.updateFillPlanStatus(FillPlanStatus.CONFIRMED, 2, 0, 0);
        PptJobRepository repository = mock(PptJobRepository.class);
        PptTemplateFillPlanStore planStore = mock(PptTemplateFillPlanStore.class);
        PptTemplateFillAsyncRunner runner = mock(PptTemplateFillAsyncRunner.class);
        PptWorkflowEvents events = mock(PptWorkflowEvents.class);
        Path planPath = Path.of("workspace/analysis/fill_plan.json");
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(planStore.hasApprovedRecord(job)).thenReturn(true);
        when(planStore.findConfirmedPlan(job)).thenReturn(Optional.of(planPath));
        PptTemplateFillService service = service(repository, events, planStore, runner, ownerAccess());

        PptJob result = service.submitPlan(jobId, "secret", null);

        assertThat(result.status()).isEqualTo(domi.argenticpptmaster.domain.PptJobStatus.PREPARING);
        verify(runner).start(jobId, planPath);
        verify(planStore, never()).storeConfirmedPlan(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(repository).save(job);
    }

    @Test
    void rejectsRequestBodyPlanWithoutServerApproval() {
        UUID jobId = UUID.randomUUID();
        PptJob job = ownedTemplateFillJob(jobId);
        PptJobRepository repository = mock(PptJobRepository.class);
        PptTemplateFillPlanStore planStore = mock(PptTemplateFillPlanStore.class);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(planStore.hasApprovedRecord(job)).thenReturn(false);
        PptTemplateFillService service = service(
                repository, mock(PptWorkflowEvents.class), planStore, mock(PptTemplateFillAsyncRunner.class),
                ownerAccess());

        assertThatThrownBy(() -> service.submitPlan(jobId, "secret", "{\"status\":\"confirmed\"}"))
                .isInstanceOf(PptTemplateFillConflictException.class)
                .hasMessageContaining("server-approved");
    }

    @Test
    void rejectsMissingOrWrongTokenBeforeReadingJob() {
        PptJobRepository repository = mock(PptJobRepository.class);
        PptTemplateFillService service = service(
                repository, mock(PptWorkflowEvents.class), mock(PptTemplateFillPlanStore.class),
                mock(PptTemplateFillAsyncRunner.class), new FailClosedPptAccessContextResolver());

        assertThatThrownBy(() -> service.submitPlan(UUID.randomUUID(), "wrong", "{}"))
                .isInstanceOf(PptTemplateFillAccessException.class);
        verify(repository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsTokenWithoutJobAuthorization() {
        UUID jobId = UUID.randomUUID();
        PptJob job = ownedTemplateFillJob(jobId);
        PptJobRepository repository = mock(PptJobRepository.class);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        PptTemplateFillService service = service(
                repository, mock(PptWorkflowEvents.class), mock(PptTemplateFillPlanStore.class),
                mock(PptTemplateFillAsyncRunner.class),
                new FixedPptAccessContextResolver(PptAccessContext.user("other", "other-tenant", Set.of())));

        assertThatThrownBy(() -> service.submitPlan(jobId, "secret", null))
                .isInstanceOf(PptTemplateFillAccessException.class);
    }

    @Test
    void rejectsAlreadyStartedOrWrongWorkflowWithConflict() {
        UUID jobId = UUID.randomUUID();
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.BASIC, Path.of("workspace"));
        PptJobRepository repository = mock(PptJobRepository.class);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        PptTemplateFillService service = service(
                repository, mock(PptWorkflowEvents.class), mock(PptTemplateFillPlanStore.class),
                mock(PptTemplateFillAsyncRunner.class), ownerAccess());

        assertThatThrownBy(() -> service.submitPlan(jobId, "secret", null))
                .isInstanceOf(PptTemplateFillConflictException.class);
    }

    @Test
    void rejectsRequestBodyEvenWhenServerApprovalExists() {
        UUID jobId = UUID.randomUUID();
        PptJob job = ownedTemplateFillJob(jobId);
        PptJobRepository repository = mock(PptJobRepository.class);
        PptTemplateFillPlanStore planStore = mock(PptTemplateFillPlanStore.class);
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(planStore.hasApprovedRecord(job)).thenReturn(true);
        when(planStore.findConfirmedPlan(job)).thenReturn(Optional.of(Path.of("workspace/analysis/fill_plan.json")));
        PptTemplateFillService service = service(
                repository, mock(PptWorkflowEvents.class), planStore, mock(PptTemplateFillAsyncRunner.class),
                ownerAccess());

        assertThatThrownBy(() -> service.submitPlan(jobId, "secret", "{\"status\":\"confirmed\"}"))
                .isInstanceOf(PptTemplateFillConflictException.class)
                .hasMessageContaining("request body plan cannot establish approval");
    }

    private static PptJob ownedTemplateFillJob(UUID jobId) {
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, Path.of("workspace"));
        job.assignOwnership("user-1", "test-tenant");
        return job;
    }

    private static FixedPptAccessContextResolver ownerAccess() {
        return new FixedPptAccessContextResolver(PptAccessContext.user("user-1", "test-tenant", Set.of()));
    }

    private static PptTemplateFillService service(
            PptJobRepository repository,
            PptWorkflowEvents events,
            PptTemplateFillPlanStore planStore,
            PptTemplateFillAsyncRunner runner,
            domi.argenticpptmaster.security.PptAccessContextResolver access) {
        PptMasterProperties properties = new PptMasterProperties(
                Path.of("repo"), Path.of("workspace"), "python3", Duration.ofSeconds(1),
                "secret", 1024, 0, 0, 0, 0, null, null, null, null, null);
        return new PptTemplateFillService(
                properties, repository, events, planStore, runner,
                new PptJobAccessAuthorizer(properties, access));
    }
}
