package domi.argenticpptmaster.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Native-enhance 生产发布、保留和诊断配置。 */
public record NativeEnhancePptxProductionProperties(
        boolean enabled,
        List<String> allowedTenants,
        String adminRole,
        Duration retentionCompleted,
        Duration retentionFailed,
        Duration retentionDiagnostic,
        Duration retentionMin,
        Duration retentionMax,
        boolean cleanupDryRunEnabled,
        boolean cleanupDeletionEnabled,
        boolean diagnosticsEnabled,
        boolean executionStopEnabled) {

    private static final Duration DEFAULT_RETENTION_COMPLETED = Duration.ofDays(7);
    private static final Duration DEFAULT_RETENTION_FAILED = Duration.ofDays(14);
    private static final Duration DEFAULT_RETENTION_DIAGNOSTIC = Duration.ofDays(7);
    private static final Duration DEFAULT_RETENTION_MIN = Duration.ofHours(1);
    private static final Duration DEFAULT_RETENTION_MAX = Duration.ofDays(90);
    private static final String DEFAULT_ADMIN_ROLE = "ADMIN";

    public NativeEnhancePptxProductionProperties {
        if (allowedTenants == null) {
            allowedTenants = List.of();
        } else {
            List<String> normalized = new ArrayList<>();
            for (String tenant : allowedTenants) {
                if (tenant != null && !tenant.isBlank()) {
                    normalized.add(tenant.trim());
                }
            }
            allowedTenants = List.copyOf(normalized);
        }
        adminRole = adminRole == null || adminRole.isBlank() ? DEFAULT_ADMIN_ROLE : adminRole.trim();
        retentionCompleted = retentionCompleted == null ? DEFAULT_RETENTION_COMPLETED : retentionCompleted;
        retentionFailed = retentionFailed == null ? DEFAULT_RETENTION_FAILED : retentionFailed;
        retentionDiagnostic = retentionDiagnostic == null ? DEFAULT_RETENTION_DIAGNOSTIC : retentionDiagnostic;
        retentionMin = retentionMin == null ? DEFAULT_RETENTION_MIN : retentionMin;
        retentionMax = retentionMax == null ? DEFAULT_RETENTION_MAX : retentionMax;
        validateBounds(retentionCompleted, retentionFailed, retentionDiagnostic, retentionMin, retentionMax);
    }

    public static NativeEnhancePptxProductionProperties defaults() {
        return new NativeEnhancePptxProductionProperties(false, List.of(), DEFAULT_ADMIN_ROLE,
                DEFAULT_RETENTION_COMPLETED, DEFAULT_RETENTION_FAILED, DEFAULT_RETENTION_DIAGNOSTIC,
                DEFAULT_RETENTION_MIN, DEFAULT_RETENTION_MAX, true, false, false, false);
    }

    /** 测试场景：开启创建并允许指定租户。 */
    public static NativeEnhancePptxProductionProperties forTestEnabled(String... tenants) {
        return new NativeEnhancePptxProductionProperties(
                true, List.of(tenants), DEFAULT_ADMIN_ROLE,
                DEFAULT_RETENTION_COMPLETED, DEFAULT_RETENTION_FAILED, DEFAULT_RETENTION_DIAGNOSTIC,
                DEFAULT_RETENTION_MIN, DEFAULT_RETENTION_MAX,
                true, false, true, false);
    }

    public NativeEnhancePptxProductionProperties withEnabled(boolean value) {
        return new NativeEnhancePptxProductionProperties(
                value, allowedTenants, adminRole,
                retentionCompleted, retentionFailed, retentionDiagnostic,
                retentionMin, retentionMax,
                cleanupDryRunEnabled, cleanupDeletionEnabled, diagnosticsEnabled, executionStopEnabled);
    }

    private static void validateBounds(
            Duration completed, Duration failed, Duration diagnostic, Duration min, Duration max) {
        if (min.isNegative() || min.isZero() || max.compareTo(min) < 0) {
            throw new IllegalArgumentException("native-enhance retention bounds are invalid");
        }
        assertWithin(completed, min, max, "retentionCompleted");
        assertWithin(failed, min, max, "retentionFailed");
        assertWithin(diagnostic, min, max, "retentionDiagnostic");
    }

    private static void assertWithin(Duration value, Duration min, Duration max, String name) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " must be within configured retention min/max");
        }
    }
}
