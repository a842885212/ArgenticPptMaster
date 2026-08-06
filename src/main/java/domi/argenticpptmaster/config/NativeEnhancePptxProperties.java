package domi.argenticpptmaster.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * 服务端 native-enhance 托管分析的资源边界与生产发布扁平开关。
 *
 * <p>嵌套在 {@code ppt-master.native-enhance-pptx.*} 下。零值或空值使用安全默认值。
 * 扁平 {@code enabled}/{@code allowed-tenants}/{@code admin-role} 会合并进
 * {@link NativeEnhancePptxProductionProperties}。</p>
 */
public record NativeEnhancePptxProperties(
        long maxSourceSize,
        Duration initTimeout,
        Duration planTimeout,
        Duration validateTimeout,
        Duration applyTimeout,
        Duration audioTimeout,
        int maxConcurrentJobs,
        int queueCapacity,
        Boolean enabled,
        List<String> allowedTenants,
        String adminRole,
        @NestedConfigurationProperty NativeEnhancePptxProductionProperties production) {

    private static final long DEFAULT_MAX_SOURCE_SIZE = 104_857_600L;
    private static final int DEFAULT_MAX_CONCURRENT_JOBS = 2;
    private static final int DEFAULT_QUEUE_CAPACITY = 8;

    public NativeEnhancePptxProperties {
        if (maxSourceSize <= 0) {
            maxSourceSize = DEFAULT_MAX_SOURCE_SIZE;
        }
        if (initTimeout == null || initTimeout.isZero() || initTimeout.isNegative()) {
            initTimeout = Duration.ofMinutes(3);
        }
        if (planTimeout == null || planTimeout.isZero() || planTimeout.isNegative()) {
            planTimeout = Duration.ofMinutes(1);
        }
        if (validateTimeout == null || validateTimeout.isZero() || validateTimeout.isNegative()) {
            validateTimeout = Duration.ofMinutes(2);
        }
        if (applyTimeout == null || applyTimeout.isZero() || applyTimeout.isNegative()) {
            applyTimeout = Duration.ofMinutes(5);
        }
        if (audioTimeout == null || audioTimeout.isZero() || audioTimeout.isNegative()) {
            audioTimeout = Duration.ofMinutes(10);
        }
        if (maxConcurrentJobs <= 0) {
            maxConcurrentJobs = DEFAULT_MAX_CONCURRENT_JOBS;
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

    public static NativeEnhancePptxProperties defaults() {
        return new NativeEnhancePptxProperties(0, null, null, null, null, null, 0, 0, null, null, null, null);
    }

    /** 测试/手工构造：覆盖资源边界。 */
    public static NativeEnhancePptxProperties forLimits(
            long maxSourceSize,
            Duration initTimeout,
            Duration planTimeout,
            Duration validateTimeout,
            Duration applyTimeout,
            int maxConcurrentJobs,
            int queueCapacity) {
        return forLimits(maxSourceSize, initTimeout, planTimeout, validateTimeout, applyTimeout,
                null, maxConcurrentJobs, queueCapacity);
    }

    public static NativeEnhancePptxProperties forLimits(
            long maxSourceSize,
            Duration initTimeout,
            Duration planTimeout,
            Duration validateTimeout,
            Duration applyTimeout,
            Duration audioTimeout,
            int maxConcurrentJobs,
            int queueCapacity) {
        return new NativeEnhancePptxProperties(
                maxSourceSize, initTimeout, planTimeout, validateTimeout, applyTimeout, audioTimeout,
                maxConcurrentJobs, queueCapacity, null, null, null, null);
    }

    /** 测试构造：开启创建并允许租户。 */
    public static NativeEnhancePptxProperties forTestEnabled(String... tenants) {
        return new NativeEnhancePptxProperties(0, null, null, null, null, null, 0, 0, null, null, null,
                NativeEnhancePptxProductionProperties.forTestEnabled(tenants));
    }

    private static NativeEnhancePptxProductionProperties mergeProduction(
            Boolean enabled,
            List<String> allowedTenants,
            String adminRole,
            NativeEnhancePptxProductionProperties production) {
        NativeEnhancePptxProductionProperties base = production == null
                ? NativeEnhancePptxProductionProperties.defaults()
                : production;
        boolean resolvedEnabled = enabled != null ? enabled : base.enabled();
        List<String> resolvedTenants = (allowedTenants != null && !allowedTenants.isEmpty())
                ? allowedTenants
                : base.allowedTenants();
        String resolvedAdmin = adminRole != null && !adminRole.isBlank() ? adminRole : base.adminRole();
        return new NativeEnhancePptxProductionProperties(
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
