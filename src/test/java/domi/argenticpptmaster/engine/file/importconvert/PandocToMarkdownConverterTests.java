package domi.argenticpptmaster.engine.file.importconvert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

class PandocToMarkdownConverterTests {

    @TempDir
    Path tempDir;

    @Test
    void buildsExpectedPandocArgsWithExtractMedia() throws Exception {
        Path input = tempDir.resolve("notes.odt");
        Path markdown = tempDir.resolve("notes.md");
        Files.writeString(input, "odt");
        AtomicReference<List<String>> captured = new AtomicReference<>();
        PandocToMarkdownConverter.ProcessRunner runner = (command, cwd) -> {
            captured.set(command);
            try {
                Files.writeString(markdown, "# Notes\n\nHello\n", StandardCharsets.UTF_8);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            return new PandocToMarkdownConverter.ProcessResult(0, "", "", null);
        };

        PandocToMarkdownConverter converter = new PandocToMarkdownConverter("pandoc-custom", runner);
        converter.convert(input, markdown);

        assertThat(captured.get())
                .containsExactly(
                        "pandoc-custom",
                        "-f",
                        "odt",
                        "-t",
                        "gfm",
                        input.toAbsolutePath().normalize().toString(),
                        "-o",
                        markdown.toAbsolutePath().normalize().toString(),
                        "--wrap",
                        "none",
                        "--strip-comments",
                        "--extract-media",
                        "notes_files");
        assertThat(markdown).exists();
    }

    @Test
    void flattensNestedMediaAndWritesPandocImageManifest() throws Exception {
        Path input = tempDir.resolve("doc.rst");
        Path markdown = tempDir.resolve("doc.md");
        Files.writeString(input, "rst");
        PandocToMarkdownConverter.ProcessRunner runner = (command, cwd) -> {
            try {
                Path mediaNested = markdown.resolveSibling("doc_files/media");
                Files.createDirectories(mediaNested);
                Files.write(mediaNested.resolve("pic.png"), new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
                Files.writeString(
                        markdown,
                        "See ![x](doc_files/media/pic.png)\n<img src=\"doc_files/media/pic.png\" alt=\"y\">\n",
                        StandardCharsets.UTF_8);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            return new PandocToMarkdownConverter.ProcessResult(0, "", "", null);
        };

        new PandocToMarkdownConverter("pandoc", runner).convert(input, markdown);

        String md = Files.readString(markdown, StandardCharsets.UTF_8);
        assertThat(md).contains("![x](doc_files/pic.png)");
        assertThat(md).contains("![y](doc_files/pic.png)");
        assertThat(md).doesNotContain("doc_files/media/");
        assertThat(markdown.resolveSibling("doc_files/pic.png")).exists();
        assertThat(markdown.resolveSibling("doc_files/media")).doesNotExist();
        String manifest = Files.readString(markdown.resolveSibling("doc_files/image_manifest.json"));
        assertThat(manifest).contains("\"source_kind\" : \"pandoc_image\"");
        assertThat(manifest).contains("\"filename\" : \"pic.png\"");
    }

    @Test
    void missingPandocHardFailsWithInstallHint() {
        Path input = tempDir.resolve("a.odt");
        Path markdown = tempDir.resolve("a.md");
        PandocToMarkdownConverter.ProcessRunner runner =
                (command, cwd) -> new PandocToMarkdownConverter.ProcessResult(
                        127, "", "Cannot run program \"pandoc\": error=2, No such file", "No such file");

        assertThatThrownBy(() -> new PandocToMarkdownConverter("pandoc", runner).convert(input, markdown))
                .isInstanceOf(ImportConversionException.class)
                .hasMessageContaining("pandoc is not available")
                .hasMessageContaining("ppt-master.pandoc-command");
    }

    @Test
    @EnabledIf("pandocAvailable")
    void smokeConvertsRstWhenPandocPresent() throws Exception {
        Path input = tempDir.resolve("smoke.rst");
        Path markdown = tempDir.resolve("smoke.md");
        Files.writeString(input, "Hello\n=====\n\nWorld.\n", StandardCharsets.UTF_8);

        new PandocToMarkdownConverter().convert(input, markdown);

        assertThat(markdown).exists();
        assertThat(Files.readString(markdown, StandardCharsets.UTF_8)).contains("World");
    }

    static boolean pandocAvailable() {
        try {
            Process process = new ProcessBuilder("pandoc", "-v").start();
            return process.waitFor() == 0;
        } catch (Exception ex) {
            return false;
        }
    }
}
