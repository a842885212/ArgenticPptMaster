package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.FillPlanStatus;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptNodeExecution;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 模板填充专用异步执行器。具体命令编排由 {@link PptTemplateFillCommandExecutor} 承担。 */
@Component
public class PptTemplateFillAsyncRunner {

    private final PptTemplateFillCommandExecutor commandExecutor;
    private final PptTemplateFillPlanStore planStore;
    private final PptJobRepository repository;
    private final PptWorkflowAsyncRunner workflowAsyncRunner;

    public PptTemplateFillAsyncRunner(
            PptTemplateFillCommandExecutor commandExecutor,
            PptTemplateFillPlanStore planStore,
            PptJobRepository repository,
            PptWorkflowAsyncRunner workflowAsyncRunner) {
        this.commandExecutor = commandExecutor;
        this.planStore = planStore;
        this.repository = repository;
        this.workflowAsyncRunner = workflowAsyncRunner;
    }

    @Async
    public void start(UUID jobId, Path planPath) {
        commandExecutor.execute(jobId, planPath);
    }

    @Async
    public void prepareAndAnalyze(UUID jobId) {
        commandExecutor.prepareAndAnalyze(jobId);
        startPlanningAgentIfReady(jobId);
    }

    @Async
    public void resumeFromCheckpoint(UUID jobId, PptJobNode checkpoint) {
        Path planPath = planStore.findConfirmedPlan(repository.findById(jobId).orElseThrow())
                .orElse(null);
        commandExecutor.resumeFromCheckpoint(jobId, checkpoint, planPath);
    }

    private void startPlanningAgentIfReady(UUID jobId) {
        PptJob job = repository.findById(jobId).orElseThrow();
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            return;
        }
        PptNodeExecution analyzed = job.nodeExecution(PptJobNode.TEMPLATE_ANALYZED);
        if (analyzed == null || analyzed.status() != PptJobNodeStatus.COMPLETED) {
            return;
        }
        if (job.status() == PptJobStatus.WAITING_CONFIRMATION
                || job.status() == PptJobStatus.RUNNING_AGENT
                || job.status() == PptJobStatus.EXPORTING
                || job.status() == PptJobStatus.COMPLETED) {
            return;
        }
        if (job.fillPlanStatus() == FillPlanStatus.CONFIRMED
                || job.fillPlanStatus() == FillPlanStatus.VALIDATED) {
            return;
        }
        if (!job.tryStartTemplateFillPlanning()) {
            return;
        }
        repository.save(job);
        workflowAsyncRunner.startAgent(jobId);
    }
}
