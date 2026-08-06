package domi.argenticpptmaster.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptBeautifyAccessException;
import domi.argenticpptmaster.exception.PptTemplateFillAccessException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PptJobAccessAuthorizerTests {

    @TempDir
    Path tempDir;

    @Test
    void allowsOwnerSameTenantAndAdministrator() {
        PptMasterProperties properties = PptMasterProperties.forTemplateFillTest(tempDir, tempDir, "t1");
        PptJob job = ownedTemplateJob();
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.user("user-1", "t1", Set.of()));
        PptJobAccessAuthorizer authorizer = new PptJobAccessAuthorizer(properties, access);

        assertThatCode(() -> authorizer.assertCanAccess(job)).doesNotThrowAnyException();

        access.set(PptAccessContext.user("admin", "other", Set.of("ADMIN")));
        assertThatCode(() -> authorizer.assertCanAccess(job)).doesNotThrowAnyException();
    }

    @Test
    void rejectsCrossTenantCrossOwnerAndMissingAuth() {
        PptMasterProperties properties = PptMasterProperties.forTemplateFillTest(tempDir, tempDir, "t1");
        PptJob job = ownedTemplateJob();
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.user("user-2", "t1", Set.of()));
        PptJobAccessAuthorizer authorizer = new PptJobAccessAuthorizer(properties, access);

        assertThatThrownBy(() -> authorizer.assertCanAccess(job))
                .isInstanceOf(PptTemplateFillAccessException.class);

        access.set(PptAccessContext.user("user-1", "t2", Set.of()));
        assertThatThrownBy(() -> authorizer.assertCanAccess(job))
                .isInstanceOf(PptTemplateFillAccessException.class);

        access.clear();
        assertThatThrownBy(() -> authorizer.assertCanAccess(job))
                .isInstanceOf(PptTemplateFillAccessException.class);
    }

    @Test
    void allowsInternalServiceAndIgnoresNonSensitiveJobs() {
        PptMasterProperties properties = PptMasterProperties.forTest(tempDir, tempDir);
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.forInternalService());
        PptJobAccessAuthorizer authorizer = new PptJobAccessAuthorizer(properties, access);
        PptJob templateJob = ownedTemplateJob();
        PptJob basic = new PptJob(UUID.randomUUID(), "demo", "ppt169", null, PptWorkflowMode.BASIC, tempDir);

        assertThatCode(() -> authorizer.assertCanAccess(templateJob)).doesNotThrowAnyException();
        access.clear();
        assertThatCode(() -> authorizer.assertCanAccess(basic)).doesNotThrowAnyException();
    }

    @Test
    void allowsBeautifyOwnerSameTenantAndAdministrator() {
        PptMasterProperties properties = PptMasterProperties.forBeautifyTest(tempDir, tempDir, "t1");
        PptJob job = ownedBeautifyJob();
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.user("user-1", "t1", Set.of()));
        PptJobAccessAuthorizer authorizer = new PptJobAccessAuthorizer(properties, access);

        assertThatCode(() -> authorizer.assertCanAccess(job)).doesNotThrowAnyException();

        access.set(PptAccessContext.user("admin", "other", Set.of("ADMIN")));
        assertThatCode(() -> authorizer.assertCanAccess(job)).doesNotThrowAnyException();
    }

    @Test
    void rejectsBeautifyCrossTenantCrossOwnerMissingAuthAndUnowned() {
        PptMasterProperties properties = PptMasterProperties.forBeautifyTest(tempDir, tempDir, "t1");
        PptJob job = ownedBeautifyJob();
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.user("user-2", "t1", Set.of()));
        PptJobAccessAuthorizer authorizer = new PptJobAccessAuthorizer(properties, access);

        assertThatThrownBy(() -> authorizer.assertCanAccess(job))
                .isInstanceOf(PptBeautifyAccessException.class);

        access.set(PptAccessContext.user("user-1", "t2", Set.of()));
        assertThatThrownBy(() -> authorizer.assertCanAccess(job))
                .isInstanceOf(PptBeautifyAccessException.class);

        access.clear();
        assertThatThrownBy(() -> authorizer.assertCanAccess(job))
                .isInstanceOf(PptBeautifyAccessException.class);

        PptJob unowned = new PptJob(UUID.randomUUID(), "demo", "ppt169", null,
                PptWorkflowMode.BEAUTIFY_PPTX, tempDir);
        access.set(PptAccessContext.user("user-1", "t1", Set.of()));
        assertThatThrownBy(() -> authorizer.assertCanAccess(unowned))
                .isInstanceOf(PptBeautifyAccessException.class);
    }

    @Test
    void allowsBeautifyInternalService() {
        PptMasterProperties properties = PptMasterProperties.forBeautifyTest(tempDir, tempDir, "t1");
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.forInternalService());
        PptJobAccessAuthorizer authorizer = new PptJobAccessAuthorizer(properties, access);

        assertThatCode(() -> authorizer.assertCanAccess(ownedBeautifyJob())).doesNotThrowAnyException();
    }

    private PptJob ownedTemplateJob() {
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill",
                PptWorkflowMode.TEMPLATE_FILL, tempDir);
        job.assignOwnership("user-1", "t1");
        return job;
    }

    private PptJob ownedBeautifyJob() {
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", null,
                PptWorkflowMode.BEAUTIFY_PPTX, tempDir);
        job.assignOwnership("user-1", "t1");
        return job;
    }
}
