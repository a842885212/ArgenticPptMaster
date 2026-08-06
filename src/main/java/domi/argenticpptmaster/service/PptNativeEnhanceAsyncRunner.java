package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.NativeEnhanceErrorCode;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Native-enhance 托管分析 / notes-only MVP 异步入口。 */
@Component
public class PptNativeEnhanceAsyncRunner {

    private final PptNativeEnhanceCommandExecutor commandExecutor;
    private final PptJobRepository repository;
    private final PptWorkflowEvents events;

    public PptNativeEnhanceAsyncRunner(
            PptNativeEnhanceCommandExecutor commandExecutor,
            PptJobRepository repository,
            PptWorkflowEvents events) {
        this.commandExecutor = commandExecutor;
        this.repository = repository;
        this.events = events;
    }

    @Async("nativeEnhanceExecutor")
    public void runNotesOnlyMvp(UUID jobId) {
        try {
            commandExecutor.runNotesOnlyMvp(jobId);
        } catch (RuntimeException ex) {
            // Job failure is already recorded by the executor; swallow to avoid async uncaught handler noise.
        }
    }

    @Async("nativeEnhanceExecutor")
    public void resumeFromCheckpoint(UUID jobId, PptJobNode checkpoint) {
        try {
            commandExecutor.resumeFromCheckpoint(jobId, checkpoint);
        } catch (RuntimeException ex) {
            // Job failure is already recorded by the executor.
        }
    }

    /** 有界队列拒绝时，将已持久化任务标记为可诊断失败。 */
    public void rejectAdmission(UUID jobId) {
        PptJob job = repository.findById(jobId).orElseThrow();
        String message = NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED.code()
                + ": native-enhance capacity is unavailable";
        job.fail(message);
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.JOB_FAILED, "native-enhance admission rejected",
                Map.of("stage", "ADMISSION", "errorCode", NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED.code())));
    }
}
