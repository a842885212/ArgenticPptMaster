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

    public PptTemplateFillService(
            PptMasterProperties properties,
            PptJobRepository repository,
            PptWorkflowEvents events,
            PptTemplateFillPlanStore planStore,
            PptTemplateFillAsyncRunner asyncRunner) {
        this.properties = properties;
        this.repository = repository;
        this.events = events;
        this.planStore = planStore;
        this.asyncRunner = asyncRunner;
    }

    public PptJob submitPlan(UUID jobId, String accessToken, String jsonPlan) {
        verifyAccess(accessToken);
        PptJob job = repository.findById(jobId).orElseThrow(() -> new PptJobNotFoundException(jobId));
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            throw new PptTemplateFillConflictException("job is not a template-fill workflow");
        }
        if (job.status() != domi.argenticpptmaster.domain.PptJobStatus.ACCEPTED) {
            throw new PptTemplateFillConflictException("template-fill execution has already started");
        }
        Path planPath = planStore.storeConfirmedPlan(job, jsonPlan);
        if (!job.tryStartTemplateFill()) {
            throw new PptTemplateFillConflictException("template-fill execution has already started");
        }
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.TEMPLATE_FILL_PLAN_ACCEPTED,
                "template fill plan accepted", Map.of("plan", "confirmed")));
        asyncRunner.start(job.id(), planPath);
        return job;
    }

    private void verifyAccess(String accessToken) {
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
