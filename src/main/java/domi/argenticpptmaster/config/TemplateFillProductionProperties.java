package domi.argenticpptmaster.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 模板填充生产化配置（灰度、保留、清理、诊断）。
 * <p>
 * 绑定前缀 {@code ppt-master.template-fill-*} 对应的扁平字段经
 * {@link PptMasterProperties} 聚合后传入；默认关闭创建与实际删除。
 * </p>
 */
public record TemplateFillProductionProperties(
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

    public TemplateFillProductionProperties {
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
        if (adminRole == null || adminRole.isBlank()) {
            adminRole = DEFAULT_ADMIN_ROLE;
        } else {
            adminRole = adminRole.trim();
        }
        if (retentionCompleted == null) {
            retentionCompleted = DEFAULT_RETENTION_COMPLETED;
        }
        if (retentionFailed == null) {
            retentionFailed = DEFAULT_RETENTION_FAILED;
        }
        if (retentionDiagnostic == null) {
            retentionDiagnostic = DEFAULT_RETENTION_DIAGNOSTIC;
        }
        if (retentionMin == null) {
            retentionMin = DEFAULT_RETENTION_MIN;
        }
        if (retentionMax == null) {
            retentionMax = DEFAULT_RETENTION_MAX;
        }
        validateRetentionBounds(retentionCompleted, retentionFailed, retentionDiagnostic, retentionMin, retentionMax);
        if (cleanupDeletionEnabled && !cleanupDryRunEnabled) {
            // Actual deletion may run without dry-run, but dry-run is independently toggleable.
        }
    }

    public static TemplateFillProductionProperties defaults() {
        return new TemplateFillProductionProperties(
                false, List.of(), DEFAULT_ADMIN_ROLE,
                DEFAULT_RETENTION_COMPLETED, DEFAULT_RETENTION_FAILED, DEFAULT_RETENTION_DIAGNOSTIC,
                DEFAULT_RETENTION_MIN, DEFAULT_RETENTION_MAX,
                true, false, false, false);
    }

    /** 测试场景：开启创建并允许指定租户。 */
    public static TemplateFillProductionProperties forTestEnabled(String... tenants) {
        return new TemplateFillProductionProperties(
                true, List.of(tenants), DEFAULT_ADMIN_ROLE,
                DEFAULT_RETENTION_COMPLETED, DEFAULT_RETENTION_FAILED, DEFAULT_RETENTION_DIAGNOSTIC,
                DEFAULT_RETENTION_MIN, DEFAULT_RETENTION_MAX,
                true, false, true, false);
    }

    public TemplateFillProductionProperties withEnabled(boolean value) {
        return new TemplateFillProductionProperties(
                value, allowedTenants, adminRole,
                retentionCompleted, retentionFailed, retentionDiagnostic,
                retentionMin, retentionMax,
                cleanupDryRunEnabled, cleanupDeletionEnabled, diagnosticsEnabled, executionStopEnabled);
    }

    private static void validateRetentionBounds(
            Duration completed, Duration failed, Duration diagnostic, Duration min, Duration max) {
        if (min.isNegative() || min.isZero() || max.compareTo(min) < 0) {
            throw new IllegalArgumentException("template-fill retention bounds are invalid");
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
