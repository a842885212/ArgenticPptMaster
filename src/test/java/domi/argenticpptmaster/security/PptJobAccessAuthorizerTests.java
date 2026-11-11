package domi.argenticpptmaster.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptBeautifyAccessException;
import domi.argenticpptmaster.exception.PptCallerAccessException;
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
    void allowsSameClientAndRejectsCrossClient() {
        PptMasterProperties properties = PptMasterProperties.forTemplateFillTest(tempDir, tempDir, "studio");
        PptJob job = ownedJob(PptWorkflowMode.TEMPLATE_FILL, "studio");
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.user("studio", "studio", Set.of()));
        PptJobAccessAuthorizer authorizer = new PptJobAccessAuthorizer(properties, access);

        assertThatCode(() -> authorizer.assertCanAccess(job)).doesNotThrowAnyException();

        access.set(PptAccessContext.user("script", "script", Set.of()));
        assertThatThrownBy(() -> authorizer.assertCanAccess(job))
                .isInstanceOf(PptTemplateFillAccessException.class);

        access.set(PptAccessContext.user("admin", "other", Set.of("ADMIN")));
        assertThatThrownBy(() -> authorizer.assertCanAccess(job))
                .isInstanceOf(PptTemplateFillAccessException.class);
    }

    @Test
    void allowsInternalServiceAndEnforcesBasicOwnership() {
        PptMasterProperties properties = PptMasterProperties.forTest(tempDir, tempDir);
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver(
                PptAccessContext.forInternalService());
        PptJobAccessAuthorizer authorizer = new PptJobAccessAuthorizer(properties, access);
        PptJob ownedBasic = ownedJob(PptWorkflowMode.BASIC, "studio");
        PptJob unownedBasic = new PptJob(UUID.randomUUID(), "demo", "ppt169", null, PptWorkflowMode.BASIC, tempDir);

        assertThatCode(() -> authorizer.assertCanAccess(ownedBasic)).doesNotThrowAnyException();

        access.set(PptAccessContext.user("studio", "studio", Set.of()));
        assertThatCode(() -> authorizer.assertCanAccess(ownedBasic)).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.assertCanAccess(unownedBasic))
                .isInstanceOf(PptCallerAccessException.class);

        access.set(PptAccessContext.user("script", "script", Set.of()));
        assertThatThrownBy(() -> authorizer.assertCanAccess(ownedBasic))
                .isInstanceOf(PptCallerAccessException.class);
    }

    @Test
    void rejectsBeautifyMissingAuthAndCrossClient() {
        PptMasterProperties properties = PptMasterProperties.forBeautifyTest(tempDir, tempDir, "studio");
        PptJob job = ownedJob(PptWorkflowMode.BEAUTIFY_PPTX, "studio");
        FixedPptAccessContextResolver access = new FixedPptAccessContextResolver();
        PptJobAccessAuthorizer authorizer = new PptJobAccessAuthorizer(properties, access);

        assertThatThrownBy(() -> authorizer.assertCanAccess(job))
                .isInstanceOf(PptBeautifyAccessException.class);

        access.set(PptAccessContext.user("script", "script", Set.of()));
        assertThatThrownBy(() -> authorizer.assertCanAccess(job))
                .isInstanceOf(PptBeautifyAccessException.class);
    }

    private PptJob ownedJob(PptWorkflowMode mode, String clientId) {
        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", null, mode, tempDir);
        job.assignOwnership(clientId, clientId);
        return job;
    }
}
