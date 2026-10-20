// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.engine.EngineResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaSvgQualityCheckerTest {

    @TempDir
    Path tempDir;

    private Path project;
    private Path svgOutput;
    private JavaSvgQualityChecker checker;

    @BeforeEach
    void setUp() throws IOException {
        project = tempDir.resolve("demo");
        svgOutput = project.resolve("svg_output");
        Files.createDirectories(svgOutput);
        checker = new JavaSvgQualityChecker();
    }

    @Test
    void ppt169Accepts1280By720ViewBox() throws IOException {
        writeSvg("slide_01.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\"></svg>");

        EngineResult result = checker.check(project, "ppt169", Duration.ofMinutes(1));

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).contains(JavaSvgQualityChecker.SUBSET_HEADER);
        assertThat(result.output()).contains("expected viewBox '0 0 1280 720'");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void rejectsMismatchedViewBox() throws IOException {
        writeSvg("slide_01.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1024 768\"></svg>");

        EngineResult result = checker.check(project, "ppt169", Duration.ZERO);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("viewBox mismatch");
        assertThat(result.output()).contains("expected '0 0 1280 720'");
        assertThat(result.output()).contains("got '0 0 1024 768'");
    }

    @Test
    void rejectsMissingViewBox() throws IOException {
        writeSvg("slide_01.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>");

        EngineResult result = checker.check(project, "ppt169", Duration.ZERO);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("could not read viewBox");
    }

    @Test
    void rejectsInvalidXml() throws IOException {
        writeSvg("slide_01.svg", "<svg><unclosed>");

        EngineResult result = checker.check(project, "ppt169", Duration.ZERO);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("could not read viewBox");
    }

    @Test
    void failsWhenSvgDirectoryIsEmpty() {
        EngineResult result = checker.check(project, "ppt169", Duration.ZERO);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("No SVG files found");
    }

    @Test
    void xhsAliasUsesXiaohongshuViewBox() throws IOException {
        writeSvg("slide_01.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1242 1660\"></svg>");

        EngineResult result = checker.check(project, "xhs", Duration.ZERO);

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).contains("expected viewBox '0 0 1242 1660'");
    }

    @Test
    void rejectsForbiddenStyleElement() throws IOException {
        writeSvg(
                "slide_01.svg",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\">"
                        + "<style>.a{}</style></svg>");

        EngineResult result = checker.check(project, "ppt169", Duration.ZERO);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("Detected forbidden <style> element");
    }

    @Test
    void rejectsUnsupportedInlineStyleProperty() throws IOException {
        writeSvg(
                "slide_01.svg",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\">"
                        + "<rect style=\"cursor:pointer\" width=\"10\" height=\"10\"/></svg>");

        EngineResult result = checker.check(project, "ppt169", Duration.ZERO);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("unsupported inline style property 'cursor'");
    }

    @Test
    void rejectsUnsupportedFontSizeUnit() throws IOException {
        writeSvg(
                "slide_01.svg",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\">"
                        + "<text font-size=\"12vh\">x</text></svg>");

        EngineResult result = checker.check(project, "ppt169", Duration.ZERO);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("Unsupported font-size value(s)");
        assertThat(result.output()).contains("12vh");
    }

    @Test
    void rejectsMissingExternalImage() throws IOException {
        writeSvg(
                "slide_01.svg",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\">"
                        + "<image href=\"../images/missing.png\" width=\"100\" height=\"100\"/>"
                        + "</svg>");

        EngineResult result = checker.check(project, "ppt169", Duration.ZERO);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("External image not found");
    }

    @Test
    void rejectsMissingIconPlaceholder() throws IOException {
        writeSvg(
                "slide_01.svg",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\">"
                        + "<use data-icon=\"lucide/missing-icon\" href=\"#icon\"/>"
                        + "</svg>");

        EngineResult result = checker.check(project, "ppt169", Duration.ZERO);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("Project-local icon not found");
        assertThat(result.output()).contains("lucide/missing-icon");
    }

    private void writeSvg(String name, String content) throws IOException {
        Files.writeString(svgOutput.resolve(name), content, StandardCharsets.UTF_8);
    }
}
