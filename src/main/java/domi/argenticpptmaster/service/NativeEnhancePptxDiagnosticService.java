package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobNotFoundException;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.exception.PptNativeEnhanceAccessException;
import domi.argenticpptmaster.exception.PptNativeEnhanceUnavailableException;
import domi.argenticpptmaster.repository.PptJobRepository;
import domi.argenticpptmaster.security.PptAccessContext;
import domi.argenticpptmaster.security.PptAccessContextResolver;
import domi.argenticpptmaster.security.PptJobAccessAuthorizer;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Owner/admin authorized diagnostic bundle export for native-enhance-pptx jobs. */
@Service
public class NativeEnhancePptxDiagnosticService {

    private final PptMasterProperties properties;
    private final PptJobRepository repository;
    private final PptJobAccessAuthorizer jobAccessAuthorizer;
    private final PptAccessContextResolver accessContextResolver;
    private final NativeEnhancePptxDiagnosticBundleBuilder bundleBuilder;
    private final NativeEnhancePptxLifecycleStore lifecycleStore;
    private final NativeEnhancePptxAuditSink auditSink;
    private final NativeEnhancePptxTelemetry telemetry;

    public NativeEnhancePptxDiagnosticService(
            PptMasterProperties properties,
            PptJobRepository repository,
            PptJobAccessAuthorizer jobAccessAuthorizer,
            PptAccessContextResolver accessContextResolver,
            NativeEnhancePptxDiagnosticBundleBuilder bundleBuilder,
            NativeEnhancePptxLifecycleStore lifecycleStore,
            NativeEnhancePptxAuditSink auditSink,
            NativeEnhancePptxTelemetry telemetry) {
        this.properties = properties;
        this.repository = repository;
        this.jobAccessAuthorizer = jobAccessAuthorizer;
        this.accessContextResolver = accessContextResolver;
        this.bundleBuilder = bundleBuilder;
        this.lifecycleStore = lifecycleStore;
        this.auditSink = auditSink;
        this.telemetry = telemetry;
    }

    public NativeEnhancePptxDiagnosticBundleBuilder.DiagnosticBundle exportDiagnostics(UUID jobId) {
        java.time.Instant started = java.time.Instant.now();
        PptJob job = repository.findById(jobId).orElseThrow(() -> new PptJobNotFoundException(jobId));
        PptAccessContext access = accessContextResolver.resolve().orElse(null);
        if (!properties.nativeEnhancePptx().production().diagnosticsEnabled()) {
            audit(job, access, "DENIED", "DISABLED");
            throw new PptNativeEnhanceUnavailableException("native-enhance-pptx diagnostics are disabled");
        }
        if (job.workflowMode() != PptWorkflowMode.NATIVE_ENHANCE_PPTX) {
            throw new PptJobStateException("job is not a native-enhance-pptx workflow");
        }
        try {
            jobAccessAuthorizer.assertCanAccess(job, access);
        } catch (PptNativeEnhanceAccessException ex) {
            audit(job, access, "DENIED", "ACCESS_DENIED");
            throw ex;
        }
        try {
            NativeEnhancePptxDiagnosticBundleBuilder.DiagnosticBundle bundle =
                    bundleBuilder.build(job, properties);
            lifecycleStore.recordDiagnostic(job);
            audit(job, access, "SUCCESS", "OK");
            if (telemetry != null) {
                telemetry.recordStage(
                        NativeEnhancePptxTelemetry.Stage.DIAGNOSTICS,
                        NativeEnhancePptxTelemetry.Outcome.SUCCESS,
                        java.time.Duration.between(started, java.time.Instant.now()));
            }
            return bundle;
        } catch (PptJobStateException ex) {
            audit(job, access, "DENIED", "UNSAFE_ENTRY");
            if (telemetry != null) {
                telemetry.recordStage(
                        NativeEnhancePptxTelemetry.Stage.DIAGNOSTICS,
                        NativeEnhancePptxTelemetry.Outcome.FAILURE,
                        java.time.Duration.between(started, java.time.Instant.now()));
            }
            throw ex;
        }
    }

    public Path bundlePath(UUID jobId) {
        return exportDiagnostics(jobId).path();
    }

    private void audit(PptJob job, PptAccessContext access, String outcome, String reasonCode) {
        String subjectDigest = access == null
                ? null
                : NativeEnhancePptxLifecycleStore.digestSubject(access.subjectId());
        String tenantDigest = access == null
                ? null
                : NativeEnhancePptxLifecycleStore.digestTenant(access.tenantId());
        auditSink.record("diagnostics", job.id(), subjectDigest, tenantDigest, outcome, reasonCode);
    }
}
