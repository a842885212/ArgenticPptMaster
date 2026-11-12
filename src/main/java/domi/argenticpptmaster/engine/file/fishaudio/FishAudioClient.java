package domi.argenticpptmaster.engine.file.fishaudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.config.PptAudioFishProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Fish Audio TTS 客户端（JDK {@link HttpClient}，无 SDK）。
 *
 * <p>{@code POST {api-url}/tts}，header {@code Authorization} + {@code model}，
 * body {@code text}/{@code reference_id}/{@code format=mp3}。</p>
 */
public final class FishAudioClient {

    private static final String TTS_PATH = "/tts";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ERROR_BODY_CHARS = 300;

    private final PptAudioFishProperties properties;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public FishAudioClient(PptAudioFishProperties properties, String apiKey) {
        this(properties, apiKey, defaultHttpClient());
    }

    public FishAudioClient(PptAudioFishProperties properties, String apiKey, HttpClient httpClient) {
        if (properties == null) {
            throw new IllegalArgumentException("properties is required");
        }
        this.properties = properties;
        this.apiKey = apiKey;
        this.httpClient = httpClient == null ? defaultHttpClient() : httpClient;
    }

    public static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public URI endpoint(String apiUrl) {
        String base = apiUrl == null ? "" : apiUrl.replaceAll("/+$", "");
        return URI.create(base + TTS_PATH);
    }

    /**
     * 合成单段旁白，返回 mp3 字节。
     *
     * @throws FishAudioException 非 2xx 或网络失败；{@link FishAudioException#recoverable()} 标明是否可重试
     */
    public byte[] synthesize(String text, String referenceId, String model) {
        if (text == null || text.isBlank()) {
            throw new FishAudioException("fish audio text is blank", 400, false);
        }
        if (referenceId == null || referenceId.isBlank()) {
            throw new FishAudioException("fish audio reference_id is blank", 400, false);
        }
        String effectiveModel = model == null || model.isBlank() ? properties.model() : model.trim();
        URI endpoint = endpoint(properties.requireApiUrl());
        byte[] payload = requestBody(text, referenceId);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "audio/mpeg, application/octet-stream, */*")
                .header("model", effectiveModel)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<byte[]> response = send(builder.build());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new FishAudioException("fish audio returned empty body", status, false);
            }
            return body;
        }
        String detail = truncate(new String(response.body() == null ? new byte[0] : response.body(), StandardCharsets.UTF_8));
        boolean recoverable = status == 429 || status >= 500;
        throw new FishAudioException(
                "fish audio TTS HTTP " + status + (detail.isBlank() ? "" : ": " + detail),
                status,
                recoverable);
    }

    private byte[] requestBody(String text, String referenceId) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("text", text);
            root.put("reference_id", referenceId);
            root.put("format", "mp3");
            return mapper.writeValueAsBytes(root);
        } catch (IOException ex) {
            throw new FishAudioException("failed to encode fish audio request", ex, false);
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new FishAudioException("fish audio request interrupted", ex, true);
        } catch (IOException ex) {
            throw new FishAudioException(
                    "fish audio request failed: " + ex.getClass().getSimpleName(), ex, true);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        return trimmed.length() <= MAX_ERROR_BODY_CHARS
                ? trimmed
                : trimmed.substring(0, MAX_ERROR_BODY_CHARS);
    }
}
