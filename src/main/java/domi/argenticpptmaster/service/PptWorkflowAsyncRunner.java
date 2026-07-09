package domi.argenticpptmaster.service;

import domi.argenticpptmaster.agent.PptAgentRunner;
import domi.argenticpptmaster.domain.PptConfirmation;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.util.Map;
import java.util.UUID;
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

    public PptWorkflowAsyncRunner(
            PptJobRepository repository,
            PptAgentRunner agentRunner,
            PptWorkflowEvents events) {
        this.repository = repository;
        this.agentRunner = agentRunner;
        this.events = events;
    }

    /**
     * 异步启动 AI 代理执行 PPT 生成。
     *
     * @param jobId 任务 ID
     */
    @Async
    public void startAgent(UUID jobId) {
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
}
