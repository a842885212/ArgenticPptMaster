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
                PptAccessContext.user("admin", "t1", Set.of("ADMIN"))))
                .isInstanceOf(PptNativeEnhanceUnavailableException.class);
    }

    @Test
    void allowsAllowedTenantAndAdmin() {
        NativeEnhancePptxRolloutPolicy policy = new NativeEnhancePptxRolloutPolicy(
                PptMasterProperties.forNativeEnhanceTest(tempDir, tempDir, "tenant-a"));

        assertThatCode(() -> policy.assertCreationAllowed(
                PptAccessContext.user("u1", "tenant-a", Set.of()))).doesNotThrowAnyException();
        assertThatCode(() -> policy.assertCreationAllowed(
                PptAccessContext.user("admin", "other", Set.of("ADMIN")))).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingContextInternalAndIneligibleTenant() {
        NativeEnhancePptxRolloutPolicy policy = new NativeEnhancePptxRolloutPolicy(
                PptMasterProperties.forNativeEnhanceTest(tempDir, tempDir, "tenant-a"));

        assertThatThrownBy(() -> policy.assertCreationAllowed(null))
                .isInstanceOf(PptNativeEnhanceAccessException.class);
        assertThatThrownBy(() -> policy.assertCreationAllowed(PptAccessContext.forInternalService()))
                .isInstanceOf(PptNativeEnhanceAccessException.class);
        assertThatThrownBy(() -> policy.assertCreationAllowed(
                PptAccessContext.user("u2", "tenant-b", Set.of())))
                .isInstanceOf(PptNativeEnhanceAccessException.class);
    }
}
