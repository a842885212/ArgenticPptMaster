package domi.argenticpptmaster.engine.file.imagegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.config.PptImageProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * OpenAI 兼容文生图客户端（JDK {@link HttpClient}，无第三方 SDK）。
 * <p>
 * 只走一个端点：{@code POST {ppt.image.base-url}/images/generations}，请求风格为 OpenAI
 * 官方格式（发送 {@code size}，不发送 {@code aspect_ratio}）。响应优先取
 * {@code data[0].b64_json}，仅返回 {@code url} 时再下载该 URL。
 * {@code response_format} 与 ppt-master 后端规则一致：仅在非 gpt-image 系模型上发送，
 * 因为 gpt-image 系固定返回 base64，多余参数会被 OpenAI 拒绝。
 * </p>
 * <p>
 * 限流语义：仅 HTTP 429 按指数退避重试（默认最多 3 次），其它错误立即抛出，由调用方记为
 * {@code Failed}。不做全局并发降级。
 * </p>
 */
public final class OpenAiImageClient {

    /** 默认请求画质档位，与 ppt-master 1K 档 {@code IMAGE_SIZE_TO_QUALITY} 一致。 */
    public static final String DEFAULT_QUALITY = "medium";

    private static final String GENERATIONS_PATH = "/images/generations";
    private static final String RESPONSE_FORMAT = "b64_json";
    private static final String BASE64_MARKER = "base64,";
    private static final int DEFAULT_MAX_RATE_LIMIT_RETRIES = 3;
    private static final Duration DEFAULT_RATE_LIMIT_BACKOFF = Duration.ofSeconds(2);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ERROR_BODY_CHARS = 300;

    private final PptImageProperties properties;
    private final String apiKey;
    private final String quality;
    private final int maxRateLimitRetries;
    private final Duration rateLimitBackoff;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 生产装配入口：使用默认画质、限流重试次数与退避基数。
     *
     * @param properties 出图配置（base-url 必填）
     * @param apiKey     生效密钥，允许为 null（此时不发送 Authorization 头）
     */
    public OpenAiImageClient(PptImageProperties properties, String apiKey) {
        this(properties, apiKey, DEFAULT_QUALITY, DEFAULT_MAX_RATE_LIMIT_RETRIES, DEFAULT_RATE_LIMIT_BACKOFF,
                defaultHttpClient());
    }

    /**
     * 默认 HTTP 客户端：跟随重定向，兼容会把 {@code /v1} 或签名图片地址做 3xx 跳转的代理，
     * 行为与 Python 侧 {@code urlopen} 一致。
     */
    public static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 完整装配入口，便于测试注入极短退避与自定义 {@link HttpClient}。
     */
    public OpenAiImageClient(
            PptImageProperties properties,
            String apiKey,
            String quality,
            int maxRateLimitRetries,
            Duration rateLimitBackoff,
            HttpClient httpClient) {
        if (properties == null) {
            throw new IllegalArgumentException("properties is required");
        }
        this.properties = properties;
        this.apiKey = apiKey;
        this.quality = quality == null || quality.isBlank() ? null : quality.trim();
        this.maxRateLimitRetries = Math.max(0, maxRateLimitRetries);
        this.rateLimitBackoff = rateLimitBackoff == null || rateLimitBackoff.isNegative()
                ? Duration.ZERO
                : rateLimitBackoff;
        this.httpClient = httpClient == null ? defaultHttpClient() : httpClient;
    }

    /**
     * 生成一张图片并返回字节内容。
     *
     * @param prompt 提示词
     * @param size   {@link ImageSizeTable} 解析出的尺寸，例如 {@code 1280x720}
     * @return 图片字节（PNG 等取决于服务端）
     * @throws ImageGenerationException 请求失败、响应无法解析或服务端返回错误
     */
    public byte[] generate(String prompt, String size) {
        URI endpoint = endpoint(properties.requireBaseUrl());
        byte[] payload = requestBody(prompt, size);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(properties.itemTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (apiKey != null) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        for (int attempt = 0; ; attempt++) {
            HttpResponse<byte[]> response = send(builder.build());
            if (response.statusCode() == 429 && attempt < maxRateLimitRetries) {
                awaitBeforeRetry(attempt);
                continue;
            }
            if (!isSuccess(response.statusCode())) {
                throw new ImageGenerationException("images/generations returned HTTP " + response.statusCode()
                        + ": " + snippet(response.body()));
            }
            return readImage(response.body());
        }
    }

    /** 返回出图端点 URI，供测试断言与错误信息复用。 */
    public URI endpoint(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(trimmed + GENERATIONS_PATH);
    }

    private byte[] requestBody(String prompt, String size) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", properties.model());
        body.put("prompt", prompt);
        if (size != null && !size.isBlank()) {
            body.put("size", size);
        }
        if (quality != null) {
            body.put("quality", quality);
        }
        if (supportsResponseFormat(properties.model())) {
            body.put("response_format", RESPONSE_FORMAT);
        }
        try {
            return mapper.writeValueAsBytes(body);
        } catch (IOException ex) {
            throw new ImageGenerationException("failed to encode image request", ex);
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw new ImageGenerationException("image request failed: " + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ImageGenerationException("image request interrupted", ex);
        }
    }

    private void awaitBeforeRetry(int attempt) {
        long delayMillis = rateLimitBackoff.toMillis() * (1L << attempt);
        if (delayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ImageGenerationException("rate-limit backoff interrupted", ex);
        }
    }

    private byte[] readImage(byte[] responseBody) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (IOException ex) {
            throw new ImageGenerationException("images/generations response is not JSON: "
                    + snippet(responseBody), ex);
        }
        JsonNode first = root.path("data").isArray() ? root.path("data").path(0) : mapper.createObjectNode();
        String encoded = text(first, "b64_json");
        if (encoded != null) {
            return decode(encoded);
        }
        String url = text(first, "url");
        if (url != null) {
            return download(url);
        }
        throw new ImageGenerationException("images/generations response has no image data: "
                + snippet(responseBody));
    }

    private byte[] decode(String encoded) {
        String payload = encoded;
        int dataUriStart = payload.indexOf("data:");
        if (dataUriStart == 0) {
            int marker = payload.indexOf(BASE64_MARKER);
            if (marker > 0) {
                payload = payload.substring(marker + BASE64_MARKER.length());
            }
        }
        byte[] image;
        try {
            // MIME decoder 容忍服务端返回的换行分组，避免长 base64 被硬换行截断。
            image = Base64.getMimeDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            throw new ImageGenerationException("images/generations returned invalid base64 payload", ex);
        }
        if (image.length == 0) {
            throw new ImageGenerationException("images/generations returned an empty image payload");
        }
        return image;
    }

    private byte[] download(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.itemTimeout())
                .GET()
                .build();
        // 不向图片 URL 所在主机透传出图密钥：返回的地址通常是服务端签发的临时地址。
        HttpResponse<byte[]> response = send(request);
        if (!isSuccess(response.statusCode())) {
            throw new ImageGenerationException("image download returned HTTP " + response.statusCode());
        }
        byte[] image = response.body();
        if (image == null || image.length == 0) {
            throw new ImageGenerationException("image download returned an empty body");
        }
        return image;
    }

    /**
     * gpt-image 系模型固定返回 base64，发送 {@code response_format} 会被 OpenAI 拒绝；
     * 其它 OpenAI 兼容后端（如 DALL-E 3）则需要显式要求 b64_json。
     */
    static boolean supportsResponseFormat(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return !normalized.startsWith("gpt-image-");
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private static String snippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "<empty response>";
        }
        int limit = Math.min(body.length, MAX_ERROR_BODY_CHARS * 2);
        String text = new String(body, 0, limit, java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() <= MAX_ERROR_BODY_CHARS ? text : text.substring(0, MAX_ERROR_BODY_CHARS);
    }
}
