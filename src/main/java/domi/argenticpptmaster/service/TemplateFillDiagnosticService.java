package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobNotFoundException;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.exception.PptTemplateFillAccessException;
import domi.argenticpptmaster.exception.PptTemplateFillUnavailableException;
import domi.argenticpptmaster.repository.PptJobRepository;
import domi.argenticpptmaster.security.PptAccessContext;
import domi.argenticpptmaster.security.PptAccessContextResolver;
import domi.argenticpptmaster.security.PptJobAccessAuthorizer;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Owner/admin authorized diagnostic bundle export for template-fill jobs. */
@Service
public class TemplateFillDiagnosticService {

    private final PptMasterProperties properties;
    private final PptJobRepository repository;
    private final PptJobAccessAuthorizer jobAccessAuthorizer;
    private final PptAccessContextResolver accessContextResolver;
    private final TemplateFillDiagnosticBundleBuilder bundleBuilder;
    private final TemplateFillLifecycleStore lifecycleStore;
    private final TemplateFillAuditSink auditSink;

    public TemplateFillDiagnosticService(
            PptMasterProperties properties,
            PptJobRepository repository,
            PptJobAccessAuthorizer jobAccessAuthorizer,
            PptAccessContextResolver accessContextResolver,
            TemplateFillDiagnosticBundleBuilder bundleBuilder,
            TemplateFillLifecycleStore lifecycleStore,
            TemplateFillAuditSink auditSink) {
        this.properties = properties;
        this.repository = repository;
        this.jobAccessAuthorizer = jobAccessAuthorizer;
        this.accessContextResolver = accessContextResolver;
        this.bundleBuilder = bundleBuilder;
        this.lifecycleStore = lifecycleStore;
        this.auditSink = auditSink;
    }

    public TemplateFillDiagnosticBundleBuilder.DiagnosticBundle exportDiagnostics(UUID jobId) {
        PptJob job = repository.findById(jobId).orElseThrow(() -> new PptJobNotFoundException(jobId));
        PptAccessContext access = accessContextResolver.resolve().orElse(null);
        if (!properties.templateFillProduction().diagnosticsEnabled()) {
            audit(job, access, "DENIED", "DISABLED");
            throw new PptTemplateFillUnavailableException("template-fill diagnostics are disabled");
        }
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            throw new PptJobStateException("job is not a template-fill workflow");
        }
        try {
            jobAccessAuthorizer.assertCanAccess(job, access);
        } catch (PptTemplateFillAccessException ex) {
            audit(job, access, "DENIED", "ACCESS_DENIED");
            throw ex;
        }
        try {
            TemplateFillDiagnosticBundleBuilder.DiagnosticBundle bundle =
                    bundleBuilder.build(job, properties);
            lifecycleStore.recordDiagnostic(job);
            audit(job, access, "SUCCESS", "OK");
            return bundle;
        } catch (PptJobStateException ex) {
            audit(job, access, "DENIED", "UNSAFE_ENTRY");
            throw ex;
        }
    }

    public Path bundlePath(UUID jobId) {
        return exportDiagnostics(jobId).path();
    }

    private void audit(PptJob job, PptAccessContext access, String outcome, String reasonCode) {
        String subjectDigest = access == null
                ? null
                : TemplateFillLifecycleStore.digestSubject(access.subjectId());
        String tenantDigest = access == null
                ? null
                : TemplateFillLifecycleStore.digestTenant(access.tenantId());
        auditSink.record("diagnostics", job.id(), subjectDigest, tenantDigest, outcome, reasonCode);
    }
}
