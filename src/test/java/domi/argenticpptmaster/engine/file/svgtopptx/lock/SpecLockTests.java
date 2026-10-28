package domi.argenticpptmaster.engine.file.svgtopptx.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpecLockTests {

    @TempDir
    Path tempDir;

    @Test
    void loadsTitleCaseCanvasWithViewBoxRow() throws Exception {
        Path project = projectWithLock(
                """
                ## Canvas
                - viewBox: 0 0 1280 720
                - format: ppt169

                ## colors
                - bg: #FFFFFF
                - primary: #1F4E79
                - text: #222222

                ## typography
                - font_family: Microsoft YaHei
                - title_family: Microsoft YaHei
                - body_family: Microsoft YaHei

                ## pptx_structure
                - mode: flat
                """);

        SpecLock.Parsed parsed = SpecLock.load(project);
        assertThat(parsed.viewBox()).isEqualTo("0 0 1280 720");
        assertThat(parsed.format()).isEqualTo("ppt169");
    }

    @Test
    void loadsDottedCanvasViewBoxUnderAnyHeading() throws Exception {
        Path project = projectWithLock(
                """
                ## Notes
                - canvas.viewBox: 0 0 1280 720
                - canvas.format: ppt169

                ## colors
                - primary: #1F4E79

                ## typography
                - font_family: Microsoft YaHei
                - title_family: Microsoft YaHei
                - body_family: Microsoft YaHei
                """);

        SpecLock.Parsed parsed = SpecLock.load(project);
        assertThat(parsed.viewBox()).isEqualTo("0 0 1280 720");
        assertThat(parsed.format()).isEqualTo("ppt169");
    }

    @Test
    void normalizesHyphenatedAnnotatedColorRows() throws Exception {
        Path project = projectWithLock(
                """
                ## canvas
                - viewBox: 0 0 1280 720

                ## Colors
                - ink-900: "#0B2545" cover background
                - primary: #1D4E89

                ## typography
                - font_family: Microsoft YaHei
                - title_family: Microsoft YaHei
                - body_family: Microsoft YaHei
                """);

        SpecLock.Parsed parsed = SpecLock.load(project);
        assertThat(parsed.colors().roles()).containsEntry("ink_900", "0B2545");
        assertThat(parsed.colors().roles()).containsEntry("primary", "1D4E89");
    }

    @Test
    void lastWriteWinsForDuplicateViewBox() throws Exception {
        Path project = projectWithLock(
                """
                ## canvas
                - viewBox: 0 0 100 100
                - viewBox: 0 0 1280 720

                ## colors
                - primary: #112233

                ## typography
                - font_family: Microsoft YaHei
                - title_family: Microsoft YaHei
                - body_family: Microsoft YaHei
                """);

        assertThat(SpecLock.load(project).viewBox()).isEqualTo("0 0 1280 720");
    }

    @Test
    void missingViewBoxStillFails() throws Exception {
        Path project = projectWithLock(
                """
                ## canvas
                - format: ppt169

                ## colors
                - primary: #112233

                ## typography
                - font_family: Microsoft YaHei
                - title_family: Microsoft YaHei
                - body_family: Microsoft YaHei
                """);

        assertThatThrownBy(() -> SpecLock.load(project))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canvas.viewBox");
    }

    @ParameterizedTest
    @ValueSource(strings = {"shapes-text", "images", "formula", "table-marker", "chart-marker"})
    void fixtureSpecLocksStillLoad(String fixture) throws Exception {
        Path project = tempDir.resolve(fixture);
        Files.createDirectories(project);
        Path source = Path.of("src/test/resources/svg-to-pptx", fixture, "spec_lock.md");
        Files.copy(source, project.resolve("spec_lock.md"), StandardCopyOption.REPLACE_EXISTING);

        SpecLock.Parsed parsed = SpecLock.load(project);
        assertThat(parsed.viewBox()).isEqualTo("0 0 1280 720");
        assertThat(parsed.pptxStructureMode()).isEqualTo("flat");
        assertThat(parsed.colors()).isNotNull();
        assertThat(parsed.fonts()).isNotNull();
    }

    private Path projectWithLock(String content) throws Exception {
        Path project = tempDir.resolve("project-" + Integer.toHexString(content.hashCode()));
        Files.createDirectories(project);
        Files.writeString(project.resolve("spec_lock.md"), content);
        return project;
    }
}
