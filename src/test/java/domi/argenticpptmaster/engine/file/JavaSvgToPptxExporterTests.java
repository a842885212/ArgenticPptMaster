// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.engine.compare.SlideXmlComparator;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link JavaSvgToPptxExporter} against pin {@code svg_to_pptx} gold.
 *
 * <p>Compares slide XML via {@link SlideXmlComparator} (ignore rIds / whitespace).</p>
 */
class JavaSvgToPptxExporterTests {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"shapes-text", "images", "formula", "table-marker", "chart-marker"})
    void exportSucceedsAndMatchesPinSlideXml(String fixture) throws Exception {
        Path project = materializeFixture(fixture);
        var result = new JavaSvgToPptxExporter().export(project, Duration.ofMinutes(2));
        assertThat(result.successful()).as(result.output()).isTrue();

        Path actual = newestExport(project.resolve("exports"));
        assertThat(actual).as("export pptx").isNotNull();
        assertThat(Files.isRegularFile(actual)).isTrue();

        Path expected = Path.of("src/test/resources/svg-to-pptx", fixture, "expected/export.pptx");
        SlideXmlComparator.Result comparison = new SlideXmlComparator().compare(actual, expected);
        assertThat(comparison.equal())
                .as(() -> "slide XML mismatch for " + fixture + ": " + summarize(comparison))
                .isTrue();
    }

    @Test
    void missingSpecLockFails() throws Exception {
        Path project = materializeFixture("shapes-text");
        Files.deleteIfExists(project.resolve("spec_lock.md"));
        var result = new JavaSvgToPptxExporter().export(project, Duration.ZERO);
        assertThat(result.successful()).isFalse();
        assertThat(result.output()).contains("spec_lock.md");
    }

    @Test
    void missingQualityReportFails() throws Exception {
        Path project = materializeFixture("shapes-text");
        Files.deleteIfExists(project.resolve("validation/svg_quality_report.json"));
        var result = new JavaSvgToPptxExporter().export(project, Duration.ZERO);
        assertThat(result.successful()).isFalse();
        assertThat(result.output()).containsIgnoringCase("quality");
        assertThat(result.output()).contains("validate_svg_output");
        assertThat(result.output()).doesNotContain("svg_quality_checker.py");
    }

    @Test
    void shapesTextUsesEmuPerPx9525() throws Exception {
        Path project = materializeFixture("shapes-text");
        assertThat(new JavaSvgToPptxExporter().export(project, Duration.ZERO).successful()).isTrue();
        Path pptx = newestExport(project.resolve("exports"));
        String slide = readZipEntry(pptx, "ppt/slides/slide1.xml");
        // rect x=80 → 80*9525 = 762000
        assertThat(slide).contains("x=\"762000\"");
        assertThat(slide).contains("prst=\"roundRect\"");
        assertThat(slide).contains("prst=\"ellipse\"");
        assertThat(slide).contains("<p:fade/>");
    }

    private Path materializeFixture(String fixture) throws Exception {
        Path source = Path.of("src/test/resources/svg-to-pptx", fixture);
        Path project = tempDir.resolve(fixture);
        copyDirectory(source, project);
        Files.createDirectories(project.resolve("exports"));
        return project;
    }

    private static Path newestExport(Path exportsDir) throws Exception {
        if (!Files.isDirectory(exportsDir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(exportsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pptx"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        }
    }

    private static String summarize(SlideXmlComparator.Result result) {
        if (result.equal()) {
            return "identical";
        }
        StringBuilder sb = new StringBuilder();
        for (SlideXmlComparator.Diff diff : result.diffs()) {
            sb.append(diff.partName())
                    .append(" leftLen=")
                    .append(diff.leftNormalized().length())
                    .append(" rightLen=")
                    .append(diff.rightNormalized().length())
                    .append("; ");
            int max = Math.min(240, Math.min(diff.leftNormalized().length(), diff.rightNormalized().length()));
            if (max > 0) {
                sb.append("left=")
                        .append(diff.leftNormalized(), 0, Math.min(120, diff.leftNormalized().length()))
                        .append(" | right=")
                        .append(diff.rightNormalized(), 0, Math.min(120, diff.rightNormalized().length()));
            }
        }
        return sb.toString();
    }

    private static String readZipEntry(Path pptx, String entryName) throws Exception {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(pptx.toFile())) {
            var entry = zip.getEntry(entryName);
            assertThat(entry).isNotNull();
            try (var in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws java.io.IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Path dest = target.resolve(source.relativize(file));
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
