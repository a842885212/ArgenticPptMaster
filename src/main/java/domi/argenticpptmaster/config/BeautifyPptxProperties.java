package domi.argenticpptmaster.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * 服务端 beautify 分析的资源边界与生产发布扁平开关。
 *
 * <p>该配置嵌套在 {@code ppt-master.beautify-pptx.*} 下。零值或空值使用安全默认值。
 * 扁平 {@code enabled}/{@code allowed-tenants}/{@code admin-role} 会合并进
 * {@link BeautifyPptxProductionProperties}。仅保留唯一规范构造器，避免 Spring 绑定歧义。</p>
 */
public record BeautifyPptxProperties(
        long maxSourceSize,
        Duration identityTimeout,
        Duration projectTimeout,
        Duration intakeTimeout,
        Duration inventoryTimeout,
        int maxConcurrentAnalyses,
        int queueCapacity,
        Boolean enabled,
        List<String> allowedTenants,
        String adminRole,
        @NestedConfigurationProperty BeautifyPptxProductionProperties production) {

    private static final long DEFAULT_MAX_SOURCE_SIZE = 104_857_600L;
    private static final int DEFAULT_MAX_CONCURRENT_ANALYSES = 2;
    private static final int DEFAULT_QUEUE_CAPACITY = 8;

    public BeautifyPptxProperties {
        if (maxSourceSize <= 0) {
            maxSourceSize = DEFAULT_MAX_SOURCE_SIZE;
        }
        if (identityTimeout == null || identityTimeout.isZero() || identityTimeout.isNegative()) {
            identityTimeout = Duration.ofMinutes(1);
        }
        if (projectTimeout == null || projectTimeout.isZero() || projectTimeout.isNegative()) {
            projectTimeout = Duration.ofMinutes(2);
        }
        if (intakeTimeout == null || intakeTimeout.isZero() || intakeTimeout.isNegative()) {
            intakeTimeout = Duration.ofMinutes(2);
        }
        if (inventoryTimeout == null || inventoryTimeout.isZero() || inventoryTimeout.isNegative()) {
            inventoryTimeout = Duration.ofMinutes(2);
        }
        if (maxConcurrentAnalyses <= 0) {
            maxConcurrentAnalyses = DEFAULT_MAX_CONCURRENT_ANALYSES;
        }
        if (queueCapacity <= 0) {
            queueCapacity = DEFAULT_QUEUE_CAPACITY;
        }
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
        production = mergeProduction(enabled, allowedTenants, adminRole, production);
        enabled = production.enabled();
        allowedTenants = production.allowedTenants();
        adminRole = production.adminRole();
    }

    public static BeautifyPptxProperties defaults() {
        return new BeautifyPptxProperties(0, null, null, null, null, 0, 0, null, null, null, null);
    }

    /** 测试/手工构造：仅覆盖分析资源边界。 */
    public static BeautifyPptxProperties forAnalysisLimits(
            long maxSourceSize, Duration identityTimeout, Duration projectTimeout, Duration intakeTimeout,
            Duration inventoryTimeout, int maxConcurrentAnalyses, int queueCapacity) {
        return new BeautifyPptxProperties(maxSourceSize, identityTimeout, projectTimeout, intakeTimeout,
                inventoryTimeout, maxConcurrentAnalyses, queueCapacity, null, null, null, null);
    }

    /** 测试构造：开启创建并允许租户。 */
    public static BeautifyPptxProperties forTestEnabled(String... tenants) {
        return new BeautifyPptxProperties(0, null, null, null, null, 0, 0, null, null, null,
                BeautifyPptxProductionProperties.forTestEnabled(tenants));
    }

    private static BeautifyPptxProductionProperties mergeProduction(
            Boolean enabled,
            List<String> allowedTenants,
            String adminRole,
            BeautifyPptxProductionProperties production) {
        BeautifyPptxProductionProperties base = production == null
                ? BeautifyPptxProductionProperties.defaults()
                : production;
        boolean resolvedEnabled = enabled != null ? enabled : base.enabled();
        List<String> resolvedTenants = (allowedTenants != null && !allowedTenants.isEmpty())
                ? allowedTenants
                : base.allowedTenants();
        String resolvedAdmin = adminRole != null && !adminRole.isBlank() ? adminRole : base.adminRole();
        return new BeautifyPptxProductionProperties(
                resolvedEnabled,
                resolvedTenants,
                resolvedAdmin,
                base.retentionCompleted(),
                base.retentionFailed(),
                base.retentionDiagnostic(),
                base.retentionMin(),
                base.retentionMax(),
                base.cleanupDryRunEnabled(),
                base.cleanupDeletionEnabled(),
                base.diagnosticsEnabled(),
                base.executionStopEnabled());
    }
}
