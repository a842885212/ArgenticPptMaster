package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.exception.PptBeautifyAccessException;
import domi.argenticpptmaster.exception.PptBeautifyUnavailableException;
import domi.argenticpptmaster.security.PptAccessContext;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BeautifyPptxRolloutPolicyTests {

    @TempDir
    Path tempDir;

    @Test
    void rejectsWhenDisabledEvenForAdmin() {
        BeautifyPptxRolloutPolicy policy = new BeautifyPptxRolloutPolicy(
                PptMasterProperties.forTest(tempDir, tempDir));

        assertThatThrownBy(() -> policy.assertCreationAllowed(
                PptAccessContext.user("admin", "admin", Set.of("ADMIN"))))
                .isInstanceOf(PptBeautifyUnavailableException.class);
    }

    @Test
    void allowsAllowlistedClientAndRejectsAdminBypass() {
        BeautifyPptxRolloutPolicy policy = new BeautifyPptxRolloutPolicy(
                PptMasterProperties.forBeautifyTest(tempDir, tempDir, "tenant-a"));

        assertThatCode(() -> policy.assertCreationAllowed(
                PptAccessContext.user("tenant-a", "tenant-a", Set.of()))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.assertCreationAllowed(
                PptAccessContext.user("admin", "other", Set.of("ADMIN"))))
                .isInstanceOf(PptBeautifyAccessException.class);
    }

    @Test
    void rejectsMissingContextInternalAndIneligibleClient() {
        BeautifyPptxRolloutPolicy policy = new BeautifyPptxRolloutPolicy(
                PptMasterProperties.forBeautifyTest(tempDir, tempDir, "tenant-a"));

        assertThatThrownBy(() -> policy.assertCreationAllowed(null))
                .isInstanceOf(PptBeautifyAccessException.class);
        assertThatThrownBy(() -> policy.assertCreationAllowed(PptAccessContext.forInternalService()))
                .isInstanceOf(PptBeautifyAccessException.class);
        assertThatThrownBy(() -> policy.assertCreationAllowed(
                PptAccessContext.user("tenant-b", "tenant-b", Set.of())))
                .isInstanceOf(PptBeautifyAccessException.class);
    }
}
