package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.exception.PptTemplateFillAccessException;
import domi.argenticpptmaster.exception.PptTemplateFillUnavailableException;
import domi.argenticpptmaster.security.PptAccessContext;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateFillRolloutPolicyTests {

    @TempDir
    Path tempDir;

    @Test
    void rejectsWhenDisabledEvenForAdmin() {
        TemplateFillRolloutPolicy policy = new TemplateFillRolloutPolicy(
                PptMasterProperties.forTest(tempDir, tempDir));

        assertThatThrownBy(() -> policy.assertCreationAllowed(
                PptAccessContext.user("admin", "admin", Set.of("ADMIN"))))
                .isInstanceOf(PptTemplateFillUnavailableException.class);
    }

    @Test
    void allowsAllowlistedClientAndRejectsAdminBypass() {
        TemplateFillRolloutPolicy policy = new TemplateFillRolloutPolicy(
                PptMasterProperties.forTemplateFillTest(tempDir, tempDir, "tenant-a"));

        assertThatCode(() -> policy.assertCreationAllowed(
                PptAccessContext.user("tenant-a", "tenant-a", Set.of()))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.assertCreationAllowed(
                PptAccessContext.user("admin", "other", Set.of("ADMIN"))))
                .isInstanceOf(PptTemplateFillAccessException.class);
    }

    @Test
    void rejectsMissingContextInternalAndIneligibleClient() {
        TemplateFillRolloutPolicy policy = new TemplateFillRolloutPolicy(
                PptMasterProperties.forTemplateFillTest(tempDir, tempDir, "tenant-a"));

        assertThatThrownBy(() -> policy.assertCreationAllowed(null))
                .isInstanceOf(PptTemplateFillAccessException.class);
        assertThatThrownBy(() -> policy.assertCreationAllowed(PptAccessContext.forInternalService()))
                .isInstanceOf(PptTemplateFillAccessException.class);
        assertThatThrownBy(() -> policy.assertCreationAllowed(
                PptAccessContext.user("tenant-b", "tenant-b", Set.of())))
                .isInstanceOf(PptTemplateFillAccessException.class);
    }
}
