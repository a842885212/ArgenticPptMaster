package domi.argenticpptmaster.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务内 Fish Audio 旁白 TTS 配置（{@code ppt.audio.fish.*}）。
 *
 * <p>生产旁白不依赖 {@code notes_to_audio.py}；缺少有效 api-key 时 audio 路径失败封闭。
 */
@ConfigurationProperties(prefix = "ppt.audio.fish")
public record PptAudioFishProperties(
        String apiUrl,
        String apiKey,
        String model,
        String fallbackModel,
        Integer concurrency,
        Duration requestTimeout) {

    public static final String DEFAULT_API_URL = "https://api.fish.audio/v1";
    public static final String DEFAULT_MODEL = "s2.1-pro-free";
    public static final int DEFAULT_CONCURRENCY = 2;
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    /** 与 cornel-script 一致：过短的 key 视为未配置。 */
    public static final int MIN_API_KEY_LENGTH = 11;

    public static final String MISSING_API_KEY_MESSAGE =
            "ppt.audio.fish.api-key is required for native-enhance audio narration";

    public PptAudioFishProperties {
        apiUrl = apiUrl == null || apiUrl.isBlank() ? DEFAULT_API_URL : apiUrl.trim().replaceAll("/+$", "");
        apiKey = trimmed(apiKey);
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        fallbackModel = trimmed(fallbackModel);
        concurrency = concurrency == null || concurrency < 1 ? DEFAULT_CONCURRENCY : concurrency;
        requestTimeout = requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                ? DEFAULT_REQUEST_TIMEOUT
                : requestTimeout;
    }

    public static PptAudioFishProperties defaults() {
        return new PptAudioFishProperties(null, null, null, null, null, null);
    }

    public boolean hasApiKey() {
        return apiKey != null && apiKey.length() >= MIN_API_KEY_LENGTH;
    }

    public String requireApiKey() {
        if (!hasApiKey()) {
            throw new IllegalStateException(MISSING_API_KEY_MESSAGE);
        }
        return apiKey;
    }

    public String requireApiUrl() {
        return apiUrl;
    }

    /** 可选 fallback model；未配置时返回 null。 */
    public String effectiveFallbackModel() {
        return fallbackModel;
    }

    public int effectiveConcurrency() {
        return concurrency;
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
