package domi.argenticpptmaster.service;

import domi.argenticpptmaster.agent.PptAgentRunner;
import domi.argenticpptmaster.domain.PptConfirmation;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * PPT 工作流的异步执行器。
 * <p>
 * 通过 {@link Async} 注解在独立线程中启动或恢复 AI 代理执行，
 * 避免阻塞 HTTP 请求线程。执行失败时会自动将任务标记为失败并记录事件。
 * </p>
 */
@Component
public class PptWorkflowAsyncRunner {

    private static final Logger log = LoggerFactory.getLogger(PptWorkflowAsyncRunner.class);

    private final PptJobRepository repository;
    private final PptAgentRunner agentRunner;
    private final PptWorkflowEvents events;
    private final BeautifyPptxAnalysisExecutor beautifyAnalysisExecutor;
    private final TaskExecutor agentTaskExecutor;

    public PptWorkflowAsyncRunner(
            PptJobRepository repository,
            PptAgentRunner agentRunner,
            PptWorkflowEvents events,
            BeautifyPptxAnalysisExecutor beautifyAnalysisExecutor,
            @Qualifier("taskExecutor") TaskExecutor agentTaskExecutor) {
        this.repository = repository;
        this.agentRunner = agentRunner;
        this.events = events;
        this.beautifyAnalysisExecutor = beautifyAnalysisExecutor;
        this.agentTaskExecutor = agentTaskExecutor;
    }

    /** 在专用执行器中运行 beautify intake，并且只在分析成功后启动 Agent。 */
    @Async("beautifyAnalysisExecutor")
    public void analyzeBeautifyAndStartAgent(UUID jobId) {
        PptJob job = findJob(jobId);
        try {
            beautifyAnalysisExecutor.execute(job);
            if (job.workflowMode() == PptWorkflowMode.BEAUTIFY_PPTX && job.tryStartBeautifyAgent()) {
                agentTaskExecutor.execute(() -> runAgent(jobId));
            }
        } catch (BeautifyPptxAnalysisException ex) {
            failBeautifyAnalysis(job, ex);
        } catch (RejectedExecutionException ex) {
            failBeautifyAnalysis(job, new BeautifyPptxAnalysisException("admission",
                    domi.argenticpptmaster.domain.BeautifyPptxErrorCode.BEAUTIFY_INTAKE_FAILED,
                    domi.argenticpptmaster.domain.BeautifyPptxFailureKind.RESOURCE_LIMIT,
                    "beautify analysis capacity is unavailable", ex));
        } catch (RuntimeException ex) {
            failBeautifyAnalysis(job, new BeautifyPptxAnalysisException("analysis",
                    domi.argenticpptmaster.domain.BeautifyPptxErrorCode.BEAUTIFY_VALIDATION_FAILED,
                    domi.argenticpptmaster.domain.BeautifyPptxFailureKind.INTERNAL,
                    "beautify analysis failed", ex));
        }
    }

    /** 在专用队列拒绝任务时，将已持久化的任务标记为可恢复的资源限制失败。 */
    public void rejectBeautifyAdmission(UUID jobId) {
        PptJob job = findJob(jobId);
        failBeautifyAnalysis(job, new BeautifyPptxAnalysisException("admission",
                domi.argenticpptmaster.domain.BeautifyPptxErrorCode.BEAUTIFY_INTAKE_FAILED,
                domi.argenticpptmaster.domain.BeautifyPptxFailureKind.RESOURCE_LIMIT,
                "beautify analysis capacity is unavailable"));
    }

    /**
     * 异步启动 AI 代理执行 PPT 生成。
     *
     * @param jobId 任务 ID
     */
    @Async
    public void startAgent(UUID jobId) {
        runAgent(jobId);
    }

    private void runAgent(UUID jobId) {
        log.info("ppt_agent_start_async: jobId={}", jobId);
        PptJob job = findJob(jobId);
        try {
            agentRunner.start(job);
            log.info("ppt_agent_start_completed: jobId={}, status={}", jobId, job.status());
        } catch (RuntimeException ex) {
            log.error("ppt_agent_start_failed: jobId={}", jobId, ex);
            job.fail(ex.getMessage());
            events.record(job, PptJobEvent.of(PptJobEventType.JOB_FAILED, "agent start failed",
                    Map.of("error", ex.getMessage())));
        }
    }

    /**
     * 异步恢复 AI 代理执行（收到人工确认后）。
     *
     * @param jobId 任务 ID
     */
    @Async
    public void resumeAgent(UUID jobId) {
        log.info("ppt_agent_resume_async: jobId={}", jobId);
        PptJob job = findJob(jobId);
        try {
            PptConfirmation confirmation = job.confirmation()
                    .orElseThrow(() -> new PptJobStateException("job has no confirmation to resume"));
            log.info("ppt_agent_resume_running: jobId={}, confirmationId={}",
                    jobId, confirmation.confirmationId());
            agentRunner.resume(job, confirmation);
            log.info("ppt_agent_resume_completed: jobId={}, status={}", jobId, job.status());
        } catch (RuntimeException ex) {
            log.error("ppt_agent_resume_failed: jobId={}", jobId, ex);
            job.fail(ex.getMessage());
            events.record(job, PptJobEvent.of(PptJobEventType.JOB_FAILED, "agent resume failed",
                    Map.of("error", ex.getMessage())));
        }
    }

    /**
     * 异步从 checkpoint 恢复失败任务的执行。
     * <p>
     * 该方法会启用新的 attempt session，避免旧失败上下文污染新执行。
     * </p>
     *
     * @param jobId     任务 ID
     * @param checkpoint 恢复起点，即最近成功完成的节点
     */
    @Async
    public void resumeFromCheckpoint(UUID jobId, PptJobNode checkpoint) {
        log.info("ppt_agent_resume_from_checkpoint_async: jobId={}, checkpoint={}", jobId, checkpoint.name());
        PptJob job = findJob(jobId);
        events.record(job, PptJobEvent.of(
                PptJobEventType.JOB_RESUME_STARTED,
                "job resume from checkpoint started",
                Map.of(
                        "checkpoint", checkpoint.name(),
                        "attemptSessionId", job.activeAttemptSessionId(),
                        "resumeCount", job.resumeCount())));
        try {
            agentRunner.resumeFromCheckpoint(job, checkpoint);
            log.info("ppt_agent_resume_from_checkpoint_completed: jobId={}, status={}", jobId, job.status());
        } catch (RuntimeException ex) {
            log.error("ppt_agent_resume_from_checkpoint_failed: jobId={}", jobId, ex);
            job.fail(ex.getMessage());
            events.record(job, PptJobEvent.of(
                    PptJobEventType.JOB_FAILED,
                    "agent resume from checkpoint failed",
                    Map.of("error", ex.getMessage(), "checkpoint", checkpoint.name())));
        }
    }

    /**
     * 根据 ID 查找任务，不存在则抛出异常。
     *
     * @param jobId 任务 ID
     * @return 任务实例
     * @throws PptJobStateException 如果任务不存在
     */
    private PptJob findJob(UUID jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new PptJobStateException("job not found for async execution: " + jobId));
    }

    private void failBeautifyAnalysis(PptJob job, BeautifyPptxAnalysisException ex) {
        PptJobNode node = "identity".equals(ex.stage()) || "init".equals(ex.stage()) || "import".equals(ex.stage())
                ? PptJobNode.PROJECT_READY : PptJobNode.BEAUTIFY_ANALYZED;
        job.startNode(node);
        job.failNode(node, ex.errorCode().code() + ": " + ex.getMessage());
        events.record(job, PptJobEvent.of(PptJobEventType.JOB_FAILED, "beautify analysis failed", Map.of(
                "stage", ex.stage(), "errorCode", ex.errorCode().code(),
                "failureKind", ex.failureKind().name())));
    }
}
