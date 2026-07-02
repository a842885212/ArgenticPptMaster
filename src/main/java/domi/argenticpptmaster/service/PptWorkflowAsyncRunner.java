package domi.argenticpptmaster.service;

import domi.argenticpptmaster.agent.PptAgentRunner;
import domi.argenticpptmaster.domain.PptConfirmation;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class PptWorkflowAsyncRunner {

    private final PptJobRepository repository;
    private final PptAgentRunner agentRunner;
    private final PptWorkflowEvents events;

    public PptWorkflowAsyncRunner(
            PptJobRepository repository,
            PptAgentRunner agentRunner,
            PptWorkflowEvents events) {
        this.repository = repository;
        this.agentRunner = agentRunner;
        this.events = events;
    }

    @Async
    public void startAgent(UUID jobId) {
        PptJob job = findJob(jobId);
        try {
            agentRunner.start(job);
        } catch (RuntimeException ex) {
            job.fail(ex.getMessage());
            events.record(job, PptJobEvent.of(PptJobEventType.JOB_FAILED, "agent start failed",
                    Map.of("error", ex.getMessage())));
        }
    }

    @Async
    public void resumeAgent(UUID jobId) {
        PptJob job = findJob(jobId);
        try {
            PptConfirmation confirmation = job.confirmation()
                    .orElseThrow(() -> new PptJobStateException("job has no confirmation to resume"));
            agentRunner.resume(job, confirmation);
        } catch (RuntimeException ex) {
            job.fail(ex.getMessage());
            events.record(job, PptJobEvent.of(PptJobEventType.JOB_FAILED, "agent resume failed",
                    Map.of("error", ex.getMessage())));
        }
    }

    private PptJob findJob(UUID jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new PptJobStateException("job not found for async execution: " + jobId));
    }
}
