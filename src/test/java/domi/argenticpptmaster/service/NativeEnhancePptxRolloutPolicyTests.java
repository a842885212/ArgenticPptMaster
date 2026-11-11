package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.exception.PptNativeEnhanceAccessException;
import domi.argenticpptmaster.exception.PptNativeEnhanceUnavailableException;
import domi.argenticpptmaster.security.PptAccessContext;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeEnhancePptxRolloutPolicyTests {

    @TempDir
    Path tempDir;

    @Test
    void rejectsWhenDisabledEvenForAdmin() {
        NativeEnhancePptxRolloutPolicy policy = new NativeEnhancePptxRolloutPolicy(
                PptMasterProperties.forTest(tempDir, tempDir));

        assertThatThrownBy(() -> policy.assertCreationAllowed(
                PptAccessContext.user("admin", "admin", Set.of("ADMIN"))))
                .isInstanceOf(PptNativeEnhanceUnavailableException.class);
    }

    @Test
    void allowsAllowlistedClientAndRejectsAdminBypass() {
        NativeEnhancePptxRolloutPolicy policy = new NativeEnhancePptxRolloutPolicy(
                PptMasterProperties.forNativeEnhanceTest(tempDir, tempDir, "tenant-a"));

        assertThatCode(() -> policy.assertCreationAllowed(
                PptAccessContext.user("tenant-a", "tenant-a", Set.of()))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.assertCreationAllowed(
                PptAccessContext.user("admin", "other", Set.of("ADMIN"))))
                .isInstanceOf(PptNativeEnhanceAccessException.class);
    }

    @Test
    void rejectsMissingContextInternalAndIneligibleClient() {
        NativeEnhancePptxRolloutPolicy policy = new NativeEnhancePptxRolloutPolicy(
                PptMasterProperties.forNativeEnhanceTest(tempDir, tempDir, "tenant-a"));

        assertThatThrownBy(() -> policy.assertCreationAllowed(null))
                .isInstanceOf(PptNativeEnhanceAccessException.class);
        assertThatThrownBy(() -> policy.assertCreationAllowed(PptAccessContext.forInternalService()))
                .isInstanceOf(PptNativeEnhanceAccessException.class);
        assertThatThrownBy(() -> policy.assertCreationAllowed(
                PptAccessContext.user("tenant-b", "tenant-b", Set.of())))
                .isInstanceOf(PptNativeEnhanceAccessException.class);
    }
}
