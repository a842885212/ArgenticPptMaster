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
    private final TemplateFillTelemetry telemetry;

    public PptTemplateFillPlanningOrchestrator(
            PptJobRepository repository,
            PptWorkflowAsyncRunner workflowAsyncRunner) {
        this(repository, workflowAsyncRunner, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PptTemplateFillPlanningOrchestrator(
            PptJobRepository repository,
            PptWorkflowAsyncRunner workflowAsyncRunner,
            TemplateFillTelemetry telemetry) {
        this.repository = repository;
        this.workflowAsyncRunner = workflowAsyncRunner;
        this.telemetry = telemetry;
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
            recordPlanStage(TemplateFillTelemetry.Outcome.REJECTED);
            return;
        }
        repository.save(job);
        recordPlanStage(TemplateFillTelemetry.Outcome.SUCCESS);
        workflowAsyncRunner.startAgent(jobId);
    }

    private static final int MAX_TEMPLATE_FILL_REVISION_ATTEMPTS = 5;

    public void restartPlanningAfterRevision(UUID jobId, String feedback) {
        PptJob job = repository.findById(jobId).orElseThrow();
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            return;
        }
        if (job.resumeCount() >= MAX_TEMPLATE_FILL_REVISION_ATTEMPTS) {
            recordPlanStage(TemplateFillTelemetry.Outcome.FAILURE);
            throw new domi.argenticpptmaster.exception.PptJobStateException(
                    "maximum template-fill revision attempts reached: " + MAX_TEMPLATE_FILL_REVISION_ATTEMPTS);
        }
        job.resetFillPlanDrafting(feedback);
        job.startNewResumeAttempt();
        repository.save(job);
        if (!job.tryStartTemplateFillPlanning()) {
            recordPlanStage(TemplateFillTelemetry.Outcome.FAILURE);
            throw new domi.argenticpptmaster.exception.PptJobStateException(
                    "template-fill planning cannot restart");
        }
        repository.save(job);
        recordPlanStage(TemplateFillTelemetry.Outcome.SUCCESS);
        workflowAsyncRunner.startAgent(jobId);
    }

    private void recordPlanStage(TemplateFillTelemetry.Outcome outcome) {
        if (telemetry != null) {
            telemetry.recordStage(TemplateFillTelemetry.Stage.PLAN, outcome, java.time.Duration.ZERO);
        }
    }

    private static boolean isTemplateAnalyzed(PptJob job) {
        return job.nodeExecution(PptJobNode.TEMPLATE_ANALYZED) != null
                && job.nodeExecution(PptJobNode.TEMPLATE_ANALYZED).status() == PptJobNodeStatus.COMPLETED;
    }
}
