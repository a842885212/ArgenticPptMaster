package domi.argenticpptmaster.engine;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.PptEngineProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutingPptxMarkdownReadbackTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void dualRunDisabledSkipsPythonEntirely(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("readback.md");
        AtomicBoolean pythonCalled = new AtomicBoolean();
        RoutingPptxMarkdownReadback routing = new RoutingPptxMarkdownReadback(
                fixedJava("java-md"), (pptx, markdownOut, timeout) -> {
                    pythonCalled.set(true);
                    return new EngineResult(0, "python", false);
                }, PptEngineProperties.defaults());

        EngineResult result = routing.toMarkdown(Path.of("in.pptx"), out, TIMEOUT);

        assertThat(result.successful()).isTrue();
        assertThat(Files.readString(out)).isEqualTo("java-md");
        assertThat(pythonCalled).isFalse();
        assertThat(Files.exists(tempDir.resolve("readback.md.dual-run.json"))).isFalse();
    }

    @Test
    void dualRunMatchedWritesMatchedReport(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("readback.md");
        String md = "# deck\n\n- Source: `in.pptx`\n- Total slides: 1\n\n## Slide 1\n\nhello\n";
        RoutingPptxMarkdownReadback routing = new RoutingPptxMarkdownReadback(
                fixedJava(md), fixedPython(md), dualRunProperties());

        EngineResult result = routing.toMarkdown(Path.of("in.pptx"), out, TIMEOUT);

        assertThat(result.successful()).isTrue();
        assertThat(Files.readString(tempDir.resolve("readback.md.dual-run.json")))
                .contains("\"comparisonStatus\" : \"MATCHED\"");
    }

    @Test
    void dualRunDivergedKeepsJavaAuthorityAndWarns(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("readback.md");
        RoutingPptxMarkdownReadback routing = new RoutingPptxMarkdownReadback(
                fixedJava("# d\n\n## Slide 1\n\njava only\n"),
                fixedPython("# d\n\n## Slide 1\n\npython different\n\n## Slide 2\n\nextra\n"),
                dualRunProperties());

        EngineResult result = routing.toMarkdown(Path.of("in.pptx"), out, TIMEOUT);

        assertThat(result.successful()).isTrue();
        assertThat(Files.readString(out)).contains("java only");
        assertThat(Files.readString(tempDir.resolve("readback.md.dual-run.json")))
                .contains("\"comparisonStatus\" : \"DIVERGED\"");
    }

    @Test
    void dualRunPythonFailureDoesNotBlockJavaResult(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("readback.md");
        RoutingPptxMarkdownReadback routing = new RoutingPptxMarkdownReadback(
                fixedJava("java-md"),
                (pptx, markdownOut, timeout) -> new EngineResult(2, "script missing", false),
                dualRunProperties());

        EngineResult result = routing.toMarkdown(Path.of("in.pptx"), out, TIMEOUT);

        assertThat(result.successful()).isTrue();
        assertThat(Files.readString(out)).isEqualTo("java-md");
        assertThat(Files.readString(tempDir.resolve("readback.md.dual-run.json")))
                .contains("\"comparisonStatus\" : \"FAILED\"")
                .contains("\"pythonRan\" : false");
    }

    private static PptxMarkdownReadback fixedJava(String markdown) {
        return (pptx, markdownOut, timeout) -> writeAndSucceed(markdownOut, markdown);
    }

    private static PptxMarkdownReadback fixedPython(String markdown) {
        return (pptx, markdownOut, timeout) -> writeAndSucceed(markdownOut, markdown);
    }

    private static EngineResult writeAndSucceed(Path out, String markdown) {
        try {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, markdown, StandardCharsets.UTF_8);
            return new EngineResult(0, "ok", false);
        } catch (IOException ex) {
            return new EngineResult(1, ex.getMessage(), false);
        }
    }

    private static PptEngineProperties dualRunProperties() {
        return new PptEngineProperties(true, false, null);
    }
}
