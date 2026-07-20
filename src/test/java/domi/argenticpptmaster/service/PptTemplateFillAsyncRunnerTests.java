package domi.argenticpptmaster.service;

import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PptTemplateFillAsyncRunnerTests {

    @Test
    void delegatesExecutionWithJobAndPlanPath() {
        PptTemplateFillCommandExecutor executor = org.mockito.Mockito.mock(PptTemplateFillCommandExecutor.class);
        PptTemplateFillAsyncRunner runner = new PptTemplateFillAsyncRunner(executor);
        UUID jobId = UUID.randomUUID();
        Path plan = Path.of("workspace/analysis/fill_plan.json");

        runner.start(jobId, plan);

        verify(executor).execute(jobId, plan);
    }
}
