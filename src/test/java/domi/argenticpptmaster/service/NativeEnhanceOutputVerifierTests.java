package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeEnhanceOutputVerifierTests {

    @TempDir
    Path tempDir;

    @Test
    void writesReadbackReportWithRequiredFields() throws Exception {
        Path export = tempDir.resolve("out_enhanced.pptx");
        writePptx(export, 2, true, true, false);
        Path validation = tempDir.resolve("validation");
        NativeEnhancePlanStore.ApplySettings settings =
                new NativeEnhancePlanStore.ApplySettings(true, false, false, false, "fade", 0.5, false, 0.4);

        NativeEnhanceOutputVerifier.ReadbackResult result = new NativeEnhanceOutputVerifier()
                .verify(export, validation, 2, settings, 2, 0);

        assertThat(result.passed()).isTrue();
        assertThat(result.status()).isIn("PASSED", "PASSED_WITH_WARNINGS");
        Path report = validation.resolve(NativeEnhanceOutputVerifier.REPORT_FILE);
        assertThat(Files.isRegularFile(report)).isTrue();
        String json = Files.readString(report);
        assertThat(json).contains(NativeEnhanceOutputVerifier.SCHEMA);
        assertThat(json).contains("\"expectedPageCount\" : 2");
        assertThat(json).contains("\"actualPageCount\" : 2");
        assertThat(json).contains("visibleContentStability");
        assertThat(json).contains("enabledModules");
        assertThat(json).contains("notesSlideCount");
    }

    @Test
    void failsWhenPageCountMismatches() throws Exception {
        Path export = tempDir.resolve("out_enhanced.pptx");
        writePptx(export, 1, true, false, false);
        NativeEnhanceOutputVerifier.ReadbackResult result = new NativeEnhanceOutputVerifier()
                .verify(export, tempDir.resolve("validation"), 3,
                        NativeEnhancePlanStore.ApplySettings.defaults(), 1, 0);
        assertThat(result.passed()).isFalse();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(Files.readString(tempDir.resolve("validation")
                .resolve(NativeEnhanceOutputVerifier.REPORT_FILE)))
                .contains("slide_count_mismatch");
    }

    private static void writePptx(Path path, int slides, boolean notes, boolean media, boolean transition)
            throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types></Types>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            StringBuilder presentation = new StringBuilder("<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">");
            for (int i = 1; i <= slides; i++) {
                presentation.append("<p:sldId id=\"").append(i).append("\"/>");
            }
            presentation.append("</p:presentation>");
            zip.putNextEntry(new ZipEntry("ppt/presentation.xml"));
            zip.write(presentation.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int i = 1; i <= slides; i++) {
                String slideXml = transition
                        ? "<p:sld><p:transition/></p:sld>"
                        : "<p:sld></p:sld>";
                zip.putNextEntry(new ZipEntry("ppt/slides/slide" + i + ".xml"));
                zip.write(slideXml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                if (notes) {
                    zip.putNextEntry(new ZipEntry("ppt/notesSlides/notesSlide" + i + ".xml"));
                    zip.write("<p:notes></p:notes>".getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            if (media) {
                zip.putNextEntry(new ZipEntry("ppt/media/audio1.mp3"));
                zip.write(new byte[] {1, 2, 3});
                zip.closeEntry();
            }
        }
    }
}
