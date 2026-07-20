package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.PptJobNode;
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

    public PptTemplateFillAsyncRunner(
            PptTemplateFillCommandExecutor commandExecutor,
            PptTemplateFillPlanStore planStore,
            PptJobRepository repository) {
        this.commandExecutor = commandExecutor;
        this.planStore = planStore;
        this.repository = repository;
    }

    @Async
    public void start(UUID jobId, Path planPath) {
        commandExecutor.execute(jobId, planPath);
    }

    @Async
    public void prepareAndAnalyze(UUID jobId) {
        commandExecutor.prepareAndAnalyze(jobId);
    }

    @Async
    public void resumeFromCheckpoint(UUID jobId, PptJobNode checkpoint) {
        Path planPath = planStore.findConfirmedPlan(repository.findById(jobId).orElseThrow())
                .orElse(null);
        commandExecutor.resumeFromCheckpoint(jobId, checkpoint, planPath);
    }
}
