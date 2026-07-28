package domi.argenticpptmaster.config;

import java.time.Duration;

/**
 * 服务端 beautify 分析的资源边界。
 *
 * <p>该配置嵌套在 {@code ppt-master.beautify-pptx.*} 下。零值或空值使用安全默认值，
 * 以便在未显式配置的环境中仍保持受限执行。</p>
 */
public record BeautifyPptxProperties(
        long maxSourceSize,
        Duration identityTimeout,
        Duration projectTimeout,
        Duration intakeTimeout,
        Duration inventoryTimeout,
        int maxConcurrentAnalyses,
        int queueCapacity) {

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
    }

    public static BeautifyPptxProperties defaults() {
        return new BeautifyPptxProperties(0, null, null, null, null, 0, 0);
    }
}
