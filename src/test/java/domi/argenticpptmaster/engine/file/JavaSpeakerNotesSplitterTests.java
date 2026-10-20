package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.engine.EngineResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaSpeakerNotesSplitterTests {

    @TempDir
    Path tempDir;

    private JavaSpeakerNotesSplitter splitter;
    private Duration timeout;

    @BeforeEach
    void setUp() {
        splitter = new JavaSpeakerNotesSplitter();
        timeout = Duration.ofSeconds(30);
    }

    @Test
    void failsWhenProjectPathDoesNotExist() {
        Path missing = tempDir.resolve("missing-project");

        EngineResult result = splitter.split(missing, timeout);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.output()).isEqualTo("Error: Path does not exist: " + missing);
    }

    @Test
    void failsWhenNoSvgFiles() throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);

        EngineResult result = splitter.split(project, timeout);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("Error: No SVG files found");
    }

    @Test
    void splitsWithExactHeadingMatch() throws IOException {
        Path project = createProject(
                List.of("intro"),
                """
                # intro
                Hello exact match.
                """);

        EngineResult result = splitter.split(project, timeout);

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).contains("[OK] SVG files and notes have one-to-one correspondence");
        assertThat(result.output()).contains("Generated: intro.md");
        assertThat(result.output()).contains("[Done] Notes splitting complete");
        assertThat(Files.readString(project.resolve("notes/intro.md"), StandardCharsets.UTF_8))
                .isEqualTo("Hello exact match.");
    }

    @Test
    void splitsWithNormalizedHeadingMatch() throws IOException {
        Path project = createProject(
                List.of("hello_world"),
                """
                # Hello World!
                Normalized body.
                """);

        EngineResult result = splitter.split(project, timeout);

        assertThat(result.successful()).isTrue();
        assertThat(Files.readString(project.resolve("notes/hello_world.md"), StandardCharsets.UTF_8))
                .isEqualTo("Normalized body.");
    }

    @Test
    void splitsWithLeadingNumberHeadingMatch() throws IOException {
        Path project = createProject(
                List.of("1_intro"),
                """
                # Slide 1 — Overview
                Number matched body.
                """);

        EngineResult result = splitter.split(project, timeout);

        assertThat(result.successful()).isTrue();
        assertThat(Files.readString(project.resolve("notes/1_intro.md"), StandardCharsets.UTF_8))
                .isEqualTo("Number matched body.");
    }

    @Test
    void discardsHorizontalRuleLines() throws IOException {
        Path project = createProject(
                List.of("slide_a"),
                """
                # slide_a
                Line one
                ---
                Line two
                """);

        EngineResult result = splitter.split(project, timeout);

        assertThat(result.successful()).isTrue();
        assertThat(Files.readString(project.resolve("notes/slide_a.md"), StandardCharsets.UTF_8))
                .isEqualTo("Line one\nLine two");
    }

    @Test
    void doesNotWriteFilesWhenNotesMissingForSvg() throws IOException {
        Path project = tempDir.resolve("project");
        Path svgDir = project.resolve("svg_output");
        Files.createDirectories(svgDir);
        Files.writeString(svgDir.resolve("a.svg"), "<svg/>", StandardCharsets.UTF_8);
        Files.writeString(svgDir.resolve("b.svg"), "<svg/>", StandardCharsets.UTF_8);
        Path notesDir = project.resolve("notes");
        Files.createDirectories(notesDir);
        Files.writeString(
                notesDir.resolve("total.md"),
                """
                # a
                Only first slide.
                """,
                StandardCharsets.UTF_8);

        EngineResult result = splitter.split(project, timeout);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("Error: SVG files and notes do not match");
        assertThat(result.output()).contains("Missing notes: b");
        assertThat(Files.exists(notesDir.resolve("a.md"))).isFalse();
        assertThat(Files.exists(notesDir.resolve("b.md"))).isFalse();
    }

    @Test
    void ordersSvgFilesWithNumericSegments() throws IOException {
        Path svgDir = tempDir.resolve("svg_output");
        Files.createDirectories(svgDir);
        Files.writeString(svgDir.resolve("10.svg"), "<svg/>", StandardCharsets.UTF_8);
        Files.writeString(svgDir.resolve("2.svg"), "<svg/>", StandardCharsets.UTF_8);

        List<Path> ordered = SlideRoster.discoverSlideSvgs(svgDir);

        assertThat(ordered).extracting(path -> path.getFileName().toString())
                .containsExactly("2.svg", "10.svg");
    }

    @Test
    void failsWhenTotalMdMissing() throws IOException {
        Path project = tempDir.resolve("project");
        Path svgDir = project.resolve("svg_output");
        Files.createDirectories(svgDir);
        Files.writeString(svgDir.resolve("only.svg"), "<svg/>", StandardCharsets.UTF_8);
        Files.createDirectories(project.resolve("notes"));

        EngineResult result = splitter.split(project, timeout);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("file does not exist");
        assertThat(result.output()).contains("Error: No notes content found");
    }

    private Path createProject(List<String> svgStems, String totalMd) throws IOException {
        Path project = tempDir.resolve("project");
        Path svgDir = project.resolve("svg_output");
        Files.createDirectories(svgDir);
        for (String stem : svgStems) {
            Files.writeString(svgDir.resolve(stem + ".svg"), "<svg/>", StandardCharsets.UTF_8);
        }
        Path notesDir = project.resolve("notes");
        Files.createDirectories(notesDir);
        Files.writeString(notesDir.resolve("total.md"), totalMd.strip() + "\n", StandardCharsets.UTF_8);
        return project;
    }
}
