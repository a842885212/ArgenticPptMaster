package domi.argenticpptmaster.service;

import java.nio.file.Path;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 模板填充专用异步执行器。具体命令编排由 {@link PptTemplateFillCommandExecutor} 承担。 */
@Component
public class PptTemplateFillAsyncRunner {

    private final PptTemplateFillCommandExecutor commandExecutor;

    public PptTemplateFillAsyncRunner(PptTemplateFillCommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    @Async
    public void start(UUID jobId, Path planPath) {
        commandExecutor.execute(jobId, planPath);
    }
}
