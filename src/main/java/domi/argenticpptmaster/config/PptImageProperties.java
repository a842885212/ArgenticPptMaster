package domi.argenticpptmaster.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务内 OpenAI 兼容出图网关配置（{@code ppt.image.*}）。
 * <p>
 * 出图端点与对话端点分离：{@code ppt.image.base-url} 必须显式配置，
 * 不会回落到 {@code agentscope.base-url}（对话代理通常不提供 {@code /images/generations}）。
 * 只有 {@code ppt.image.api-key} 允许回落到对话用的 {@code agentscope.api-key}。
 * </p>
 *
 * @param baseUrl        OpenAI 兼容出图服务根地址，例如 {@code https://api.openai.com/v1}；必填
 * @param apiKey         出图鉴权密钥，未配置时可回落到对话密钥
 * @param model          出图模型名称，默认 {@code gpt-image-2}
 * @param concurrency    单次批处理的并发条目数，默认 5
 * @param itemTimeout    单个条目一次 HTTP 请求的超时，默认 180 秒
 * @param pythonFallback 为 true 时改用 ppt-master 的 {@code image_gen.py} 适配器
 */
@ConfigurationProperties(prefix = "ppt.image")
public record PptImageProperties(
        String baseUrl,
        String apiKey,
        String model,
        Integer concurrency,
        Duration itemTimeout,
        Boolean pythonFallback) {

    /** 默认出图模型，与 ppt-master openai 后端的默认值一致。 */
    public static final String DEFAULT_MODEL = "gpt-image-2";
    /** 默认并发条目数。 */
    public static final int DEFAULT_CONCURRENCY = 5;
    /** 默认单条目 HTTP 超时。 */
    public static final Duration DEFAULT_ITEM_TIMEOUT = Duration.ofSeconds(180);

    /** base-url 缺失时的统一报错文案，供启动前检查与生成调用共用。 */
    public static final String MISSING_BASE_URL_MESSAGE =
            "ppt.image.base-url is required for image generation; agentscope.base-url is only used for chat";

    public PptImageProperties {
        baseUrl = trimmed(baseUrl);
        apiKey = trimmed(apiKey);
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        concurrency = concurrency == null || concurrency < 1 ? DEFAULT_CONCURRENCY : concurrency;
        itemTimeout = itemTimeout == null || itemTimeout.isZero() || itemTimeout.isNegative()
                ? DEFAULT_ITEM_TIMEOUT
                : itemTimeout;
        pythonFallback = pythonFallback == null ? Boolean.FALSE : pythonFallback;
    }

    /** 返回一份仅含默认值的配置，供测试与本地装配使用。 */
    public static PptImageProperties defaults() {
        return new PptImageProperties(null, null, null, null, null, null);
    }

    /** base-url 是否已显式配置。 */
    public boolean hasBaseUrl() {
        return baseUrl != null;
    }

    /**
     * 返回可用于拼接请求路径的 base-url。
     *
     * @throws IllegalStateException 未配置 base-url 时抛出，调用方 MUST NOT 继续发起 HTTP 请求
     */
    public String requireBaseUrl() {
        if (!hasBaseUrl()) {
            throw new IllegalStateException(MISSING_BASE_URL_MESSAGE);
        }
        return baseUrl;
    }

    /**
     * 解析出图密钥：优先 {@code ppt.image.api-key}，缺失时使用调用方传入的对话密钥兜底。
     *
     * @param chatFallbackKey 对话侧密钥，允许为 null
     * @return 生效密钥，可能为 null（此时请求不带 Authorization）
     */
    public String effectiveApiKey(String chatFallbackKey) {
        return apiKey != null ? apiKey : trimmed(chatFallbackKey);
    }

    /** 是否显式要求回落到 Python {@code image_gen.py} 适配器。 */
    public boolean isPythonFallback() {
        return Boolean.TRUE.equals(pythonFallback);
    }

    /** 生效并发度，至少为 1。 */
    public int effectiveConcurrency() {
        return concurrency;
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
