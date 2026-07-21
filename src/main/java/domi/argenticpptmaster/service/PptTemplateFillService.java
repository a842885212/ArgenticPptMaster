package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobNotFoundException;
import domi.argenticpptmaster.exception.PptTemplateFillAccessException;
import domi.argenticpptmaster.exception.PptTemplateFillConflictException;
import domi.argenticpptmaster.repository.PptJobRepository;
import domi.argenticpptmaster.security.PptJobAccessAuthorizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 模板填充 confirmed plan 的门禁与异步启动服务。 */
@Service
public class PptTemplateFillService {

    private final PptMasterProperties properties;
    private final PptJobRepository repository;
    private final PptWorkflowEvents events;
    private final PptTemplateFillPlanStore planStore;
    private final PptTemplateFillAsyncRunner asyncRunner;
    private final PptJobAccessAuthorizer jobAccessAuthorizer;

    public PptTemplateFillService(
            PptMasterProperties properties,
            PptJobRepository repository,
            PptWorkflowEvents events,
            PptTemplateFillPlanStore planStore,
            PptTemplateFillAsyncRunner asyncRunner,
            PptJobAccessAuthorizer jobAccessAuthorizer) {
        this.properties = properties;
        this.repository = repository;
        this.events = events;
        this.planStore = planStore;
        this.asyncRunner = asyncRunner;
        this.jobAccessAuthorizer = jobAccessAuthorizer;
    }

    /** 调试入口：仅执行已由服务端确认的计划，不接受请求体自声明 confirmed。 */
    public PptJob submitPlan(UUID jobId, String accessToken, String jsonPlan) {
        verifyDebugToken(accessToken);
        PptJob job = repository.findById(jobId).orElseThrow(() -> new PptJobNotFoundException(jobId));
        jobAccessAuthorizer.assertCanAccess(job);
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            throw new PptTemplateFillConflictException("job is not a template-fill workflow");
        }
        if (!planStore.hasApprovedRecord(job)) {
            throw new PptTemplateFillConflictException(
                    "template-fill execution requires a server-approved fill plan");
        }
        Path planPath = planStore.findConfirmedPlan(job)
                .orElseThrow(() -> new PptTemplateFillConflictException("confirmed fill plan is missing"));
        if (jsonPlan != null && !jsonPlan.isBlank()) {
            throw new PptTemplateFillConflictException(
                    "request body plan cannot establish approval; use /confirm to approve the current draft");
        }
        if (job.status() != domi.argenticpptmaster.domain.PptJobStatus.ACCEPTED
                && job.status() != domi.argenticpptmaster.domain.PptJobStatus.PREPARING
                && job.status() != domi.argenticpptmaster.domain.PptJobStatus.RUNNING_AGENT) {
            throw new PptTemplateFillConflictException("template-fill execution cannot start in status: " + job.status());
        }
        if (!job.markTemplateFillExecutionStarted() && !job.tryStartTemplateFill()) {
            throw new PptTemplateFillConflictException("template-fill execution has already started");
        }
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.TEMPLATE_FILL_PLAN_ACCEPTED,
                "template fill plan accepted", Map.of("plan", "confirmed")));
        asyncRunner.start(job.id(), planPath);
        return job;
    }

    /** 异步运行工作区准备与分析至 {@code TEMPLATE_ANALYZED}。 */
    public PptJob prepareWorkspace(UUID jobId, String accessToken) {
        verifyDebugToken(accessToken);
        PptJob job = repository.findById(jobId).orElseThrow(() -> new PptJobNotFoundException(jobId));
        jobAccessAuthorizer.assertCanAccess(job);
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            throw new PptTemplateFillConflictException("job is not a template-fill workflow");
        }
        if (!job.tryStartPrepare()) {
            throw new PptTemplateFillConflictException("template-fill prepare cannot start in status: " + job.status());
        }
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.TEMPLATE_FILL_STAGE_STARTED,
                "template-fill prepare started", Map.of("stage", "PREPARE")));
        asyncRunner.prepareAndAnalyze(job.id());
        return job;
    }

    private void verifyDebugToken(String accessToken) {
        String configured = properties.templateFillDebugToken();
        if (configured == null || configured.isBlank() || accessToken == null) {
            throw new PptTemplateFillAccessException();
        }
        boolean matches = MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8), accessToken.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new PptTemplateFillAccessException();
        }
    }
}
