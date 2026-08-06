package domi.argenticpptmaster.service;

import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Native-enhance 异步执行入口。
 */
@Component
public class PptNativeEnhanceAsyncRunner {

    private final PptNativeEnhanceCommandExecutor commandExecutor;

    public PptNativeEnhanceAsyncRunner(PptNativeEnhanceCommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    @Async
    public void runNotesOnlyMvp(UUID jobId) {
        try {
            commandExecutor.runNotesOnlyMvp(jobId);
        } catch (RuntimeException ex) {
            // Job failure is already recorded by the executor; swallow to avoid async uncaught handler noise.
        }
    }
}
