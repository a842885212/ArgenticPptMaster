package domi.argenticpptmaster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 无人值守确认模式配置（{@code ppt.auto.*}）。
 *
 * @param imageRetryRounds IMAGE_ENHANCED AUTO 任务在首次出图失败后的额外工作流重试轮次；默认 2
 */
@ConfigurationProperties(prefix = "ppt.auto")
public record PptAutoProperties(Integer imageRetryRounds) {

    public static final int DEFAULT_IMAGE_RETRY_ROUNDS = 2;

    @ConstructorBinding
    public PptAutoProperties {
        if (imageRetryRounds == null) {
            imageRetryRounds = DEFAULT_IMAGE_RETRY_ROUNDS;
        }
        if (imageRetryRounds < 0) {
            throw new IllegalArgumentException("ppt.auto.image-retry-rounds must be >= 0");
        }
    }

    public static PptAutoProperties defaults() {
        return new PptAutoProperties(DEFAULT_IMAGE_RETRY_ROUNDS);
    }

    public int resolvedImageRetryRounds() {
        return imageRetryRounds == null ? DEFAULT_IMAGE_RETRY_ROUNDS : imageRetryRounds;
    }
}
