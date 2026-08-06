package domi.argenticpptmaster.config;

import java.time.Duration;

/**
 * 服务端 native-enhance 托管分析的资源边界。
 *
 * <p>嵌套在 {@code ppt-master.native-enhance-pptx.*} 下。零值或空值使用安全默认值。</p>
 */
public record NativeEnhancePptxProperties(
        long maxSourceSize,
        Duration initTimeout,
        Duration planTimeout,
        Duration validateTimeout,
        Duration applyTimeout,
        int maxConcurrentJobs,
        int queueCapacity) {

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
        if (maxConcurrentJobs <= 0) {
            maxConcurrentJobs = DEFAULT_MAX_CONCURRENT_JOBS;
        }
        if (queueCapacity <= 0) {
            queueCapacity = DEFAULT_QUEUE_CAPACITY;
        }
    }

    public static NativeEnhancePptxProperties defaults() {
        return new NativeEnhancePptxProperties(0, null, null, null, null, 0, 0);
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
        return new NativeEnhancePptxProperties(
                maxSourceSize, initTimeout, planTimeout, validateTimeout, applyTimeout,
                maxConcurrentJobs, queueCapacity);
    }
}
