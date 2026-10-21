package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import domi.argenticpptmaster.config.PptImageProperties;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.file.imagegen.OpenAiImageClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link JavaImageGenerationGateway} 的契约测试。
 * <p>
 * 用 JDK 自带 {@link HttpServer} 在回环地址上搭 OpenAI 兼容桩，验证真实
 * {@link HttpClient} 路径：请求体字段、b64/url 两种响应、429 重试、其它错误不重试、
 * 单条超时、并发回写清单的完整性。CI 不访问外网。
 * </p>
 */
class JavaImageGenerationGatewayTests {

    private static final byte[] IMAGE_BYTES = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03, 0x04
    };
    private static final String IMAGE_BASE64 = Base64.getEncoder().encodeToString(IMAGE_BYTES);
    private static final String DOWNLOAD_PATH = "/files/saved-image.png";

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicInteger generationHits = new AtomicInteger();
    private final AtomicInteger downloadHits = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();
    private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authorizationHeaders = Collections.synchronizedList(new ArrayList<>());
    private Responder responder;

    @BeforeEach
    void startServer() throws IOException {
        responder = (exchange, body) -> respondJson(exchange, 200,
                "{\"data\":[{\"b64_json\":\"" + IMAGE_BASE64 + "\"}]}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (exchange.getRequestURI().getPath().endsWith("/images/generations")) {
                    int current = inFlight.incrementAndGet();
                    peakInFlight.accumulateAndGet(current, Math::max);
                    generationHits.incrementAndGet();
                    requestBodies.add(body);
                    authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    responder.respond(exchange, body);
                } else if (exchange.getRequestURI().getPath().equals(DOWNLOAD_PATH)) {
                    downloadHits.incrementAndGet();
                    respondBytes(exchange, 200, IMAGE_BYTES);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            } catch (RuntimeException | IOException ex) {
                // 客户端可能已超时断开，桩侧异常无需上抛。
            } finally {
                inFlight.decrementAndGet();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void generatesPendingAndFailedItemsAndKeepsTerminalItemsUntouched() throws Exception {
        Path manifest = manifestWith(
                item("cover.png", "4:3", "Generated", Map.of("purpose", "keep-me")),
                item("pending.png", "16:9", "Pending"),
                item("broken.png", "1:1", "Failed", Map.of("last_error", "earlier failure")),
                item("manual.png", "1:1", "Needs-Manual"));

        EngineResult result = gateway(properties(5, Duration.ofSeconds(5)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).contains("2 item(s) to generate, 2 already done. concurrency=2");
        assertThat(Files.readAllBytes(outputDir().resolve("pending.png"))).isEqualTo(IMAGE_BYTES);
        assertThat(Files.exists(outputDir().resolve("manual.png"))).isFalse();

        assertThat(itemOf(manifest, "pending.png"))
                .containsEntry("status", "Generated")
                .doesNotContainKey("last_error");
        assertThat(itemOf(manifest, "broken.png"))
                .containsEntry("status", "Generated")
                .doesNotContainKey("last_error");
        assertThat(itemOf(manifest, "cover.png"))
                .containsEntry("status", "Generated")
                .containsEntry("purpose", "keep-me");
        assertThat(itemOf(manifest, "manual.png")).containsEntry("status", "Needs-Manual");
        assertThat(readManifest(manifest)).containsEntry("outlineVersion", 1);

        assertThat(generationHits.get()).isEqualTo(2);
        assertThat(authorizationHeaders).containsOnly("Bearer image-key");
        String joinedRequests = String.join("\n", requestBodies);
        assertThat(joinedRequests).contains("\"size\":\"1280x720\"").contains("\"size\":\"1024x1024\"");
        assertThat(joinedRequests).contains("\"quality\":\"medium\"")
                .contains("\"model\":\"gpt-image-2\"")
                .doesNotContain("aspect_ratio")
                // gpt-image 系固定返回 base64，发送 response_format 会被 OpenAI 拒绝
                .doesNotContain("response_format");
    }

    @Test
    void downloadsImageWhenResponseOnlyProvidesUrl() throws Exception {
        responder = (exchange, body) -> respondJson(exchange, 200,
                "{\"data\":[{\"url\":\"" + URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + DOWNLOAD_PATH) + "\"}]}");
        Path manifest = manifestWith(item("slide.png", "3:4", "Pending"));

        EngineResult result = gateway(properties(5, Duration.ofSeconds(5)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.successful()).isTrue();
        assertThat(Files.readAllBytes(outputDir().resolve("slide.png"))).isEqualTo(IMAGE_BYTES);
        assertThat(downloadHits.get()).isEqualTo(1);
        assertThat(itemOf(manifest, "slide.png")).containsEntry("status", "Generated");
    }

    @Test
    void sendsResponseFormatOnlyForModelsThatNeedIt() throws Exception {
        PptImageProperties legacyModel = new PptImageProperties(
                baseUrl(), "image-key", "dall-e-3", 5, Duration.ofSeconds(5), null);
        Path manifest = manifestWith(item("legacy.png", "1:1", "Pending"));

        EngineResult result = gateway(legacyModel)
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.successful()).isTrue();
        assertThat(String.join("\n", requestBodies))
                .contains("\"model\":\"dall-e-3\"")
                .contains("\"response_format\":\"b64_json\"")
                .contains("\"size\":\"1024x1024\"");
    }

    @Test
    void unsupportedAspectRatioFailsItemWithoutAnyHttpRequest() throws Exception {
        Path manifest = manifestWith(item("wide.png", "21:9", "Pending"));

        EngineResult result = gateway(properties(5, Duration.ofSeconds(5)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(generationHits.get()).isZero();
        Map<String, Object> item = itemOf(manifest, "wide.png");
        assertThat(item).containsEntry("status", "Failed");
        assertThat(String.valueOf(item.get("last_error"))).contains("unsupported aspect_ratio: 21:9");
    }

    @Test
    void partialFailureWritesSuccessesAndReturnsNonZero() throws Exception {
        responder = (exchange, body) -> {
            if (body.contains("explode")) {
                respondJson(exchange, 500, "{\"error\":{\"message\":\"backend exploded\"}}");
                return;
            }
            respondJson(exchange, 200, "{\"data\":[{\"b64_json\":\"" + IMAGE_BASE64 + "\"}]}");
        };
        Path manifest = manifestWith(
                item("good.png", "1:1", "Pending", "a calm lake"),
                item("bad.png", "1:1", "Pending", "explode now"));

        EngineResult result = gateway(properties(5, Duration.ofSeconds(5)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.successful()).isFalse();
        assertThat(Files.exists(outputDir().resolve("good.png"))).isTrue();
        assertThat(itemOf(manifest, "good.png")).containsEntry("status", "Generated");
        Map<String, Object> failed = itemOf(manifest, "bad.png");
        assertThat(failed).containsEntry("status", "Failed");
        assertThat(String.valueOf(failed.get("last_error"))).contains("HTTP 500")
                .contains("backend exploded");
    }

    @Test
    void rateLimitIsRetriedWithBackoffAndThenSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        responder = (exchange, body) -> {
            if (attempts.incrementAndGet() == 1) {
                respondJson(exchange, 429, "{\"error\":{\"message\":\"slow down\"}}");
                return;
            }
            respondJson(exchange, 200, "{\"data\":[{\"b64_json\":\"" + IMAGE_BASE64 + "\"}]}");
        };
        Path manifest = manifestWith(item("throttled.png", "16:9", "Pending"));

        EngineResult result = gateway(properties(5, Duration.ofSeconds(5)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.successful()).isTrue();
        assertThat(generationHits.get()).isEqualTo(2);
        assertThat(itemOf(manifest, "throttled.png")).containsEntry("status", "Generated");
        assertThat(Files.exists(outputDir().resolve("throttled.png"))).isTrue();
    }

    @Test
    void rateLimitRetriesAreCappedAndRecordedAsFailure() throws Exception {
        responder = (exchange, body) -> respondJson(exchange, 429, "{\"error\":{\"message\":\"slow down\"}}");
        Path manifest = manifestWith(item("throttled.png", "16:9", "Pending"));

        EngineResult result = gateway(properties(5, Duration.ofSeconds(5)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(generationHits.get()).isEqualTo(4);
        assertThat(itemOf(manifest, "throttled.png")).containsEntry("status", "Failed");
    }

    @Test
    void nonRateLimitHttpErrorsAreNotRetried() throws Exception {
        responder = (exchange, body) -> respondJson(exchange, 400, "{\"error\":{\"message\":\"bad size\"}}");
        Path manifest = manifestWith(item("rejected.png", "1:1", "Pending"));

        EngineResult result = gateway(properties(5, Duration.ofSeconds(5)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(generationHits.get()).isEqualTo(1);
        assertThat(String.valueOf(itemOf(manifest, "rejected.png").get("last_error"))).contains("HTTP 400");
    }

    @Test
    void itemTimeoutFailsTheItemWithoutFailingWholeRun() throws Exception {
        responder = (exchange, body) -> {
            sleep(400);
            respondJson(exchange, 200, "{\"data\":[{\"b64_json\":\"" + IMAGE_BASE64 + "\"}]}");
        };
        Path manifest = manifestWith(item("slow.png", "1:1", "Pending"));

        EngineResult result = gateway(properties(5, Duration.ofMillis(120)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.exitCode()).isEqualTo(1);
        Map<String, Object> item = itemOf(manifest, "slow.png");
        assertThat(item).containsEntry("status", "Failed");
        assertThat(String.valueOf(item.get("last_error"))).containsIgnoringCase("timeout");
    }

    @Test
    void commandTimeoutStopsBatchAndReportsNonZero() throws Exception {
        responder = (exchange, body) -> {
            sleep(600);
            respondJson(exchange, 200, "{\"data\":[{\"b64_json\":\"" + IMAGE_BASE64 + "\"}]}");
        };
        Path manifest = manifestWith(item("slow.png", "1:1", "Pending"));

        EngineResult result = gateway(properties(5, Duration.ofSeconds(5)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMillis(150));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("command timeout");
        // 被中断的 worker 在结果返回后才补写清单，等它落盘：既验证语义，也保证临时目录可清理。
        assertThat(awaitSettledStatus(manifest, "slow.png")).isIn("Failed", "Generated");
    }

    @Test
    void defaultConcurrencyRunsItemsInParallel() throws Exception {
        responder = (exchange, body) -> {
            sleep(80);
            respondJson(exchange, 200, "{\"data\":[{\"b64_json\":\"" + IMAGE_BASE64 + "\"}]}");
        };
        Path manifest = manifestWith(
                item("a.png", "1:1", "Pending"), item("b.png", "1:1", "Pending"),
                item("c.png", "1:1", "Pending"), item("d.png", "1:1", "Pending"),
                item("e.png", "1:1", "Pending"), item("f.png", "1:1", "Pending"),
                item("g.png", "1:1", "Pending"));

        EngineResult result = gateway(new PptImageProperties(baseUrl(), "image-key", null, null, null, null))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).contains("concurrency=5");
        assertThat(peakInFlight.get()).isGreaterThanOrEqualTo(2).isLessThanOrEqualTo(5);
    }

    @Test
    void concurrentWriteBackKeepsManifestValidAndConsistent() throws Exception {
        responder = (exchange, body) -> {
            sleep(30);
            respondJson(exchange, 200, "{\"data\":[{\"b64_json\":\"" + IMAGE_BASE64 + "\"}]}");
        };
        List<String> items = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            items.add(item("slide-%02d.png".formatted(index), "16:9", "Pending"));
        }
        Path manifest = manifestWith(items.toArray(String[]::new));

        EngineResult result = gateway(properties(5, Duration.ofSeconds(5)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.successful()).isTrue();
        assertThat(readManifest(manifest)).hasSize(2);
        for (int index = 1; index <= 12; index++) {
            String filename = "slide-%02d.png".formatted(index);
            assertThat(itemOf(manifest, filename)).containsEntry("status", "Generated");
            assertThat(Files.exists(outputDir().resolve(filename))).isTrue();
        }
    }

    @Test
    void skipsGenerationWhenNothingIsPending() throws Exception {
        Path manifest = manifestWith(
                item("done.png", "1:1", "Generated"),
                item("manual.png", "1:1", "Needs-Manual"));

        EngineResult result = gateway(properties(5, Duration.ofSeconds(5)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).contains("Nothing to do");
        assertThat(generationHits.get()).isZero();
    }

    @Test
    void missingBaseUrlFailsBeforeAnyHttpRequest() throws Exception {
        Path manifest = manifestWith(item("cover.png", "16:9", "Pending"));
        PptImageProperties withoutBaseUrl = new PptImageProperties(null, "image-key", null, 5, null, null);

        EngineResult result = gateway(withoutBaseUrl)
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("ppt.image.base-url");
        assertThat(generationHits.get()).isZero();
        assertThat(itemOf(manifest, "cover.png")).containsEntry("status", "Pending");
    }

    @Test
    void filenameOutsideImagesDirectoryIsRejected() throws Exception {
        Path manifest = manifestWith(item("../escaped.png", "1:1", "Pending"));

        EngineResult result = gateway(properties(5, Duration.ofSeconds(5)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(generationHits.get()).isZero();
        assertThat(Files.exists(tempDir.resolve("escaped.png"))).isFalse();
        assertThat(String.valueOf(itemOf(manifest, "../escaped.png").get("last_error")))
                .contains("must stay inside the images directory");
    }

    @Test
    void duplicateFilenamesAbortRunBeforeAnyGeneration() throws Exception {
        Path manifest = manifestWith(
                item("twin.png", "1:1", "Pending"),
                item("twin.png", "16:9", "Pending"));

        EngineResult result = gateway(properties(5, Duration.ofSeconds(5)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("duplicate filename");
        assertThat(generationHits.get()).isZero();
        assertThat(Files.exists(outputDir().resolve("twin.png"))).isFalse();
    }

    @Test
    void invalidManifestReturnsFailureWithoutTouchingFiles() throws Exception {
        Path dir = tempDir.resolve("images");
        Files.createDirectories(dir);
        Path manifest = dir.resolve("image_prompts.json");
        Files.writeString(manifest, "{\"items\": [", StandardCharsets.UTF_8);

        EngineResult result = gateway(properties(5, Duration.ofSeconds(5)))
                .generateFromManifest(manifest, outputDir(), Duration.ofMinutes(1));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("image manifest is invalid");
        assertThat(generationHits.get()).isZero();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private interface Responder {
        void respond(HttpExchange exchange, String body) throws IOException;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private PptImageProperties properties(int concurrency, Duration itemTimeout) {
        return new PptImageProperties(baseUrl(), "image-key", null, concurrency, itemTimeout, null);
    }

    private JavaImageGenerationGateway gateway(PptImageProperties properties) {
        OpenAiImageClient client = new OpenAiImageClient(
                properties,
                properties.effectiveApiKey("chat-key"),
                OpenAiImageClient.DEFAULT_QUALITY,
                3,
                Duration.ofMillis(2),
                OpenAiImageClient.defaultHttpClient());
        return new JavaImageGenerationGateway(properties, client);
    }

    private Path outputDir() {
        return tempDir.resolve("images");
    }

    private Path manifestWith(String... items) throws IOException {
        Files.createDirectories(outputDir());
        Path manifest = outputDir().resolve("image_prompts.json");
        Files.writeString(manifest, """
                {"outlineVersion": 1, "items": [%s]}
                """.formatted(String.join(",", items)), StandardCharsets.UTF_8);
        return manifest;
    }

    private String item(String filename, String aspectRatio, String status) {
        return item(filename, aspectRatio, status, "a prompt for " + filename, Map.of());
    }

    private String item(String filename, String aspectRatio, String status, Map<String, Object> extraFields) {
        return item(filename, aspectRatio, status, "a prompt for " + filename, extraFields);
    }

    private String item(String filename, String aspectRatio, String status, String prompt) {
        return item(filename, aspectRatio, status, prompt, Map.of());
    }

    private String item(
            String filename,
            String aspectRatio,
            String status,
            String prompt,
            Map<String, Object> extraFields) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("filename", filename);
        item.put("prompt", prompt);
        item.put("aspect_ratio", aspectRatio);
        item.put("status", status);
        if ("Failed".equals(status)) {
            item.put("last_error", "earlier failure");
        }
        item.putAll(extraFields);
        try {
            return mapper.writeValueAsString(item);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("test fixture is invalid", ex);
        }
    }

    /** 轮询等待条目状态离开 {@code Pending}（超时分支下由被中断的 worker 补写）。 */
    private String awaitSettledStatus(Path manifest, String filename) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        String status = "Pending";
        while (System.nanoTime() < deadline && "Pending".equals(status)) {
            status = String.valueOf(itemOf(manifest, filename).get("status"));
            if ("Pending".equals(status)) {
                sleep(20);
            }
        }
        return status;
    }

    private Map<String, Object> readManifest(Path manifest) throws IOException {
        return mapper.readValue(manifest.toFile(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> itemOf(Path manifest, String filename) throws IOException {
        List<Map<String, Object>> items = (List<Map<String, Object>>) readManifest(manifest).get("items");
        return items.stream()
                .filter(item -> filename.equals(item.get("filename")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("manifest item not found: " + filename));
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        respondBytes(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respondBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", status == 200 ? "application/json" : "text/plain");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
