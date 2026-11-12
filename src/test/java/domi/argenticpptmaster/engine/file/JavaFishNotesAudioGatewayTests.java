package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import domi.argenticpptmaster.config.PptAudioFishProperties;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.file.fishaudio.FishAudioClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaFishNotesAudioGatewayTests {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile byte[] body = "fake-mp3".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tts", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void synthesizesStemAlignedMp3AndSkipsEmptyNotes() throws Exception {
        Path project = tempDir.resolve("proj");
        Files.createDirectories(project.resolve("notes"));
        Files.writeString(project.resolve("notes/001.md"), "# 标题\n你好世界\n");
        Files.writeString(project.resolve("notes/002.md"), "# 空\n");
        Files.writeString(project.resolve("notes/total.md"), "ignored");

        JavaFishNotesAudioGateway gateway = gateway();
        EngineResult result = gateway.synthesize(project, "female_mandarin", Duration.ofSeconds(30));

        assertThat(result.successful()).isTrue();
        assertThat(project.resolve("audio/001.mp3")).exists();
        assertThat(Files.readAllBytes(project.resolve("audio/001.mp3"))).isEqualTo(body);
        assertThat(project.resolve("audio/002.mp3")).doesNotExist();
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void allEmptyNotesStillSucceed() throws Exception {
        Path project = tempDir.resolve("empty");
        Files.createDirectories(project.resolve("notes"));
        Files.writeString(project.resolve("notes/001.md"), "# only heading\n");

        EngineResult result = gateway().synthesize(project, "male_mandarin", Duration.ofSeconds(10));
        assertThat(result.successful()).isTrue();
        assertThat(result.output()).contains("Generated 0/");
        assertThat(hits.get()).isZero();
    }

    @Test
    void missingNotesFails() {
        Path project = tempDir.resolve("none");
        EngineResult result = gateway().synthesize(project, "female_mandarin", Duration.ofSeconds(5));
        assertThat(result.successful()).isFalse();
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void recoverableErrorUsesFallbackModel() throws Exception {
        Path project = tempDir.resolve("fallback");
        Files.createDirectories(project.resolve("notes"));
        Files.writeString(project.resolve("notes/001.md"), "fallback please");
        status = 503;

        PptAudioFishProperties properties = new PptAudioFishProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "sk-fish-audio-key-123456",
                "s2.1-pro-free",
                "s2.1-pro",
                1,
                Duration.ofSeconds(5));
        AtomicInteger attempt = new AtomicInteger();
        server.removeContext("/tts");
        server.createContext("/tts", exchange -> {
            int n = attempt.incrementAndGet();
            hits.incrementAndGet();
            if (n <= 2) {
                respond(exchange, 503, "busy");
            } else {
                respond(exchange, 200, body);
            }
        });

        EngineResult result = new JavaFishNotesAudioGateway(
                properties, new FishAudioClient(properties, properties.apiKey()))
                .synthesize(project, "female_mandarin", Duration.ofSeconds(30));

        assertThat(result.successful()).isTrue();
        assertThat(attempt.get()).isGreaterThanOrEqualTo(3);
        assertThat(project.resolve("audio/001.mp3")).exists();
    }

    @Test
    void clientFourXxDoesNotUseFallback() throws Exception {
        Path project = tempDir.resolve("fourxx");
        Files.createDirectories(project.resolve("notes"));
        Files.writeString(project.resolve("notes/001.md"), "bad request");
        status = 400;

        PptAudioFishProperties properties = new PptAudioFishProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "sk-fish-audio-key-123456",
                "s2.1-pro-free",
                "s2.1-pro",
                1,
                Duration.ofSeconds(5));
        EngineResult result = new JavaFishNotesAudioGateway(
                properties, new FishAudioClient(properties, properties.apiKey()))
                .synthesize(project, "female_mandarin", Duration.ofSeconds(20));

        assertThat(result.successful()).isFalse();
        assertThat(hits.get()).isEqualTo(1);
    }

    private JavaFishNotesAudioGateway gateway() {
        PptAudioFishProperties properties = new PptAudioFishProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "sk-fish-audio-key-123456",
                null,
                null,
                2,
                Duration.ofSeconds(5));
        return new JavaFishNotesAudioGateway(properties, new FishAudioClient(properties, properties.apiKey()));
    }

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        respond(exchange, status, body);
    }

    private static void respond(HttpExchange exchange, int code, byte[] payload) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
        exchange.sendResponseHeaders(code, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static void respond(HttpExchange exchange, int code, String text) throws IOException {
        respond(exchange, code, text.getBytes(StandardCharsets.UTF_8));
    }
}
