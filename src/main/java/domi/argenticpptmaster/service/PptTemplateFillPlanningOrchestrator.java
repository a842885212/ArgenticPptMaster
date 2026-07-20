package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.FillPlanStatus;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 模板填充自动路径：prepare/analyze 完成后启动计划 Agent。 */
@Component
public class PptTemplateFillPlanningOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PptTemplateFillPlanningOrchestrator.class);

    private final PptJobRepository repository;
    private final PptWorkflowAsyncRunner workflowAsyncRunner;

    public PptTemplateFillPlanningOrchestrator(
            PptJobRepository repository,
            PptWorkflowAsyncRunner workflowAsyncRunner) {
        this.repository = repository;
        this.workflowAsyncRunner = workflowAsyncRunner;
    }

    public void startAfterCreate(UUID jobId) {
        PptJob job = repository.findById(jobId).orElseThrow();
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            return;
        }
        if (!job.tryStartPrepare()) {
            log.warn("template_fill_prepare_skipped: jobId={}, status={}", jobId, job.status());
            return;
        }
        repository.save(job);
    }

    public void startPlanningAgentIfReady(UUID jobId) {
        PptJob job = repository.findById(jobId).orElseThrow();
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            return;
        }
        if (!isTemplateAnalyzed(job)) {
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
            log.warn("template_fill_planning_skipped: jobId={}, status={}", jobId, job.status());
            return;
        }
        repository.save(job);
        workflowAsyncRunner.startAgent(jobId);
    }

    private static final int MAX_TEMPLATE_FILL_REVISION_ATTEMPTS = 5;

    public void restartPlanningAfterRevision(UUID jobId, String feedback) {
        PptJob job = repository.findById(jobId).orElseThrow();
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            return;
        }
        if (job.resumeCount() >= MAX_TEMPLATE_FILL_REVISION_ATTEMPTS) {
            throw new domi.argenticpptmaster.exception.PptJobStateException(
                    "maximum template-fill revision attempts reached: " + MAX_TEMPLATE_FILL_REVISION_ATTEMPTS);
        }
        job.resetFillPlanDrafting(feedback);
        job.startNewResumeAttempt();
        repository.save(job);
        if (!job.tryStartTemplateFillPlanning()) {
            throw new domi.argenticpptmaster.exception.PptJobStateException(
                    "template-fill planning cannot restart");
        }
        repository.save(job);
        workflowAsyncRunner.startAgent(jobId);
    }

    private static boolean isTemplateAnalyzed(PptJob job) {
        return job.nodeExecution(PptJobNode.TEMPLATE_ANALYZED) != null
                && job.nodeExecution(PptJobNode.TEMPLATE_ANALYZED).status() == PptJobNodeStatus.COMPLETED;
    }
}
