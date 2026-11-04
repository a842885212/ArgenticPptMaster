package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaPptxMarkdownReadbackTests {

    private final JavaPptxMarkdownReadback readback = new JavaPptxMarkdownReadback();

    @Test
    void convertsFixtureDeckToMarkdown(@TempDir Path tempDir) throws Exception {
        Path pptx = Path.of("src/test/resources/readback/tables-images.pptx");
        Path out = tempDir.resolve("readback.md");

        var result = readback.toMarkdown(pptx, out, Duration.ofSeconds(30));

        assertThat(result.successful()).isTrue();
        assertThat(result.timedOut()).isFalse();
        assertThat(Files.readString(out))
                .contains("## Slide 1")
                .contains("| Name | Value\\|Escaped |")
                .contains("### Speaker Notes");
        assertThat(Files.isRegularFile(tempDir.resolve("readback_files/image_manifest.json"))).isTrue();
    }

    @Test
    void corruptPptxMapsToUnsuccessfulResultWithoutTimeout(@TempDir Path tempDir) throws Exception {
        Path broken = tempDir.resolve("broken.pptx");
        Files.writeString(broken, "not a zip");
        Path out = tempDir.resolve("out.md");

        var result = readback.toMarkdown(broken, out, Duration.ofSeconds(30));

        assertThat(result.successful()).isFalse();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isNotZero();
    }
}
