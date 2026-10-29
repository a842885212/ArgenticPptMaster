// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.file.svgtopptx.gate.QualityReportGate;
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

    @Test
    void passWritesGateCompatibleQualityReport() throws Exception {
        writeSvg("slide_01.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\"></svg>");

        EngineResult result = checker.check(project, "ppt169", Duration.ZERO);

        assertThat(result.successful()).isTrue();
        Path reportPath = project.resolve("validation/svg_quality_report.json");
        assertThat(Files.isRegularFile(reportPath)).isTrue();

        JsonNode report = new ObjectMapper().readTree(Files.readString(reportPath));
        assertThat(report.path("schema").asText()).isEqualTo(SvgQualityReportWriter.SCHEMA);
        assertThat(report.path("stage").asText()).isEqualTo(SvgQualityReportWriter.STAGE);
        assertThat(report.path("categories").path("blocking").path("count").asInt()).isZero();

        QualityReportGate.Fingerprint fingerprint =
                QualityReportGate.svgSourceFingerprint(QualityReportGate.listSvgOutput(project));
        QualityReportGate.GateResult gate = QualityReportGate.evaluate(project, fingerprint);
        assertThat(gate.passed()).as(gate.status() + ": " + gate.detail()).isTrue();
    }

    @Test
    void failWritesBlockingQualityReport() throws Exception {
        writeSvg("slide_01.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1024 768\"></svg>");

        EngineResult result = checker.check(project, "ppt169", Duration.ZERO);

        assertThat(result.exitCode()).isEqualTo(1);
        Path reportPath = project.resolve("validation/svg_quality_report.json");
        assertThat(Files.isRegularFile(reportPath)).isTrue();

        JsonNode report = new ObjectMapper().readTree(Files.readString(reportPath));
        assertThat(report.path("schema").asText()).isEqualTo(SvgQualityReportWriter.SCHEMA);
        assertThat(report.path("stage").asText()).isEqualTo(SvgQualityReportWriter.STAGE);
        assertThat(report.path("categories").path("blocking").path("count").asInt()).isGreaterThan(0);
        assertThat(report.path("categories").path("blocking").path("issues")).isNotEmpty();

        QualityReportGate.Fingerprint fingerprint =
                QualityReportGate.svgSourceFingerprint(QualityReportGate.listSvgOutput(project));
        QualityReportGate.GateResult gate = QualityReportGate.evaluate(project, fingerprint);
        assertThat(gate.status()).isEqualTo("failed");
    }

    @Test
    void rejectsLegacyPptxAttribute() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<rect data-pptx-module-bounds=\"0 0 10 10\" width=\"10\" height=\"10\"/>"));
        assertBlockingFailure("legacy data-pptx-module-bounds", "data-pptx-bounds");
    }

    @Test
    void acceptsCanonicalBoundsAttribute() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<g data-pptx-bounds=\"0 0 10 10\">"
                        + "<rect width=\"10\" height=\"10\" fill=\"#112233\"/></g>"));
        assertPass();
    }

    @Test
    void rejectsUnsupportedVisualTag() throws Exception {
        writeSvg("slide_01.svg", cleanSvg("<mesh id=\"bad\"/>"));
        assertBlockingFailure("Unsupported visual SVG element");
    }

    @Test
    void acceptsConverterSupportedShapes() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg(
                        "<g><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"#112233\"/>"
                                + "<circle cx=\"5\" cy=\"5\" r=\"2\" fill=\"none\" stroke=\"#000000\"/>"
                                + "</g>"));
        assertPass();
    }

    @Test
    void rejectsMalformedOpacity() throws Exception {
        writeSvg("slide_01.svg", cleanSvg("<rect opacity=\"12px\" width=\"10\" height=\"10\"/>"));
        assertBlockingFailure("opacity=");
    }

    @Test
    void acceptsUnitlessOpacity() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<rect opacity=\"0.5\" width=\"10\" height=\"10\" fill=\"#112233\"/>"));
        assertPass();
    }

    @Test
    void rejectsIllegalGeometryLength() throws Exception {
        writeSvg("slide_01.svg", cleanSvg("<rect width=\"10cm\" height=\"10\"/>"));
        assertBlockingFailure("width=");
    }

    @Test
    void acceptsUnitlessGeometryLength() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<rect x=\"0\" y=\"0\" width=\"10\" height=\"20\" fill=\"#112233\"/>"));
        assertPass();
    }

    @Test
    void rejectsInvalidStrokeStyle() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<rect stroke-linecap=\"diagonal\" width=\"10\" height=\"10\"/>"));
        assertBlockingFailure("stroke-linecap=");
    }

    @Test
    void acceptsValidStrokeEnum() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg(
                        "<rect width=\"10\" height=\"10\" fill=\"none\" stroke=\"#000000\" "
                                + "stroke-linecap=\"round\"/>"));
        assertPass();
    }

    @Test
    void rejectsUnparsablePaint() throws Exception {
        writeSvg("slide_01.svg", cleanSvg("<rect fill=\"not-a-color!!!\" width=\"10\" height=\"10\"/>"));
        assertBlockingFailure("fill=");
    }

    @Test
    void acceptsCompatibleNonCanonicalPaintWithoutBlocking() throws Exception {
        // lowercase hex is converter-compatible; phase-1 skips spelling recommendations
        writeSvg(
                "slide_01.svg",
                cleanSvg("<rect fill=\"#aabbcc\" width=\"10\" height=\"10\"/>"));
        assertPass();
    }

    @Test
    void rejectsUnsupportedMask() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<rect mask=\"url(#m)\" width=\"10\" height=\"10\"/>"
                        + "<defs><mask id=\"m\"><rect width=\"1\" height=\"1\"/></mask></defs>"));
        assertBlockingFailure("unsupported SVG mask");
    }

    @Test
    void acceptsImageWithoutMaskOrClip() throws Exception {
        Path images = project.resolve("images");
        Files.createDirectories(images);
        Files.write(images.resolve("ok.png"), new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
        writeSvg(
                "slide_01.svg",
                cleanSvg("<image href=\"../images/ok.png\" x=\"0\" y=\"0\" width=\"10\" height=\"10\"/>"));
        assertPass();
    }

    @Test
    void rejectsUnresolvedPaintReference() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<rect fill=\"url(#missing-grad)\" width=\"10\" height=\"10\"/>"));
        assertBlockingFailure("no matching direct <defs> definition");
    }

    @Test
    void acceptsResolvedLinearGradientPaintReference() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg(
                        "<defs><linearGradient id=\"g1\">"
                                + "<stop offset=\"0\" stop-color=\"#000000\"/>"
                                + "<stop offset=\"1\" stop-color=\"#FFFFFF\"/>"
                                + "</linearGradient></defs>"
                                + "<rect fill=\"url(#g1)\" width=\"10\" height=\"10\"/>"));
        assertPass();
    }

    @Test
    void rejectsInvalidMarkerReference() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<line x1=\"0\" y1=\"0\" x2=\"10\" y2=\"10\" marker-end=\"url(#nope)\"/>"));
        assertBlockingFailure("marker-end=");
    }

    @Test
    void acceptsLineWithoutMarkers() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<line x1=\"0\" y1=\"0\" x2=\"10\" y2=\"10\" stroke=\"#000000\"/>"));
        assertPass();
    }

    @Test
    void rejectsInvalidInlineGeometryProperty() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<circle style=\"cx:1px; width:2px\" r=\"1\"/>"));
        assertBlockingFailure("Invalid inline geometry property");
        String before = Files.readString(svgOutput.resolve("slide_01.svg"), StandardCharsets.UTF_8);
        checker.check(project, "ppt169", Duration.ZERO);
        String after = Files.readString(svgOutput.resolve("slide_01.svg"), StandardCharsets.UTF_8);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void acceptsSupportedInlineGeometryOnRect() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<rect style=\"x:1px; y:2px; width:10px; height:10px\" fill=\"#112233\"/>"));
        assertPass();
        String content = Files.readString(svgOutput.resolve("slide_01.svg"), StandardCharsets.UTF_8);
        assertThat(content).contains("style=\"x:1px; y:2px; width:10px; height:10px\"");
    }

    @Test
    void rejectsBrokenLocalUseReference() throws Exception {
        writeSvg("slide_01.svg", cleanSvg("<use href=\"#missing-symbol\"/>"));
        assertBlockingFailure("Invalid local <use> reference");
    }

    @Test
    void acceptsValidLocalUseReference() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg(
                        "<defs><g id=\"icon\"><rect width=\"10\" height=\"10\" fill=\"#112233\"/></g></defs>"
                                + "<use href=\"#icon\" x=\"0\" y=\"0\"/>"));
        assertPass();
    }

    @Test
    void rejectsEmptyTransform() throws Exception {
        writeSvg("slide_01.svg", cleanSvg("<rect transform=\"  \" width=\"10\" height=\"10\"/>"));
        assertBlockingFailure("Invalid SVG transform");
    }

    @Test
    void acceptsTranslateTransform() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<rect transform=\"translate(10 20)\" width=\"10\" height=\"10\" fill=\"#112233\"/>"));
        assertPass();
    }

    @Test
    void rejectsIllegalPathData() throws Exception {
        writeSvg("slide_01.svg", cleanSvg("<path d=\"not-a-path\"/>"));
        assertBlockingFailure(" d:");
    }

    @Test
    void acceptsSimplePathData() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<path d=\"M0 0 L10 10\" fill=\"none\" stroke=\"#000000\"/>"));
        assertPass();
    }

    @Test
    void rejectsUnsupportedFilterPrimitive() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg(
                        "<defs><filter id=\"f1\"><feTurbulence/></filter></defs>"
                                + "<rect filter=\"url(#f1)\" width=\"10\" height=\"10\"/>"));
        assertBlockingFailure("unsupported filter primitive");
    }

    @Test
    void acceptsSupportedDropShadowFilter() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg(
                        "<defs><filter id=\"f1\"><feDropShadow dx=\"1\" dy=\"1\"/></filter></defs>"
                                + "<rect width=\"10\" height=\"10\" fill=\"#112233\"/>"));
        assertPass();
    }

    @Test
    void rejectsIllegalPreserveAspectRatio() throws Exception {
        Path images = project.resolve("images");
        Files.createDirectories(images);
        Files.write(images.resolve("ok.png"), new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
        writeSvg(
                "slide_01.svg",
                cleanSvg(
                        "<image href=\"../images/ok.png\" preserveAspectRatio=\"stretch me\" "
                                + "width=\"10\" height=\"10\"/>"));
        assertBlockingFailure("preserveAspectRatio=");
    }

    @Test
    void acceptsCanonicalPreserveAspectRatio() throws Exception {
        Path images = project.resolve("images");
        Files.createDirectories(images);
        Files.write(images.resolve("ok.png"), new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
        writeSvg(
                "slide_01.svg",
                cleanSvg(
                        "<image href=\"../images/ok.png\" preserveAspectRatio=\"xMidYMid meet\" "
                                + "width=\"10\" height=\"10\"/>"));
        assertPass();
    }

    @Test
    void rejectsUnsupportedTextProperty() throws Exception {
        writeSvg("slide_01.svg", cleanSvg("<text text-transform=\"uppercase\">Hi</text>"));
        assertBlockingFailure("unsupported text");
    }

    @Test
    void acceptsSupportedTextAttributes() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<text x=\"10\" y=\"20\" font-size=\"16\" fill=\"#112233\">Hi</text>"));
        assertPass();
    }

    @Test
    void rejectsInvalidHyperlinkTarget() throws Exception {
        writeSvg("slide_01.svg",                 cleanSvg("<a href=\"javascript:alert(1)\"><text x=\"40\" y=\"80\">x</text></a>"));
        assertBlockingFailure("hyperlink target");
    }

    @Test
    void acceptsHttpHyperlink() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<a href=\"https://example.com\"><text x=\"40\" y=\"80\" fill=\"#112233\">x</text></a>"));
        assertPass();
    }

    @Test
    void rejectsIllegalPatternPreset() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg(
                        "<defs><pattern id=\"p1\" data-pptx-pattern=\"ltGrid\">"
                                + "<rect width=\"1\" height=\"1\"/></pattern></defs>"
                                + "<rect fill=\"url(#p1)\" width=\"10\" height=\"10\"/>"));
        assertBlockingFailure("data-pptx-pattern=");
    }

    @Test
    void acceptsValidPatternPreset() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg(
                        "<defs><pattern id=\"p1\" data-pptx-pattern=\"smGrid\">"
                                + "<rect width=\"1\" height=\"1\" fill=\"#000000\"/></pattern></defs>"
                                + "<rect fill=\"url(#p1)\" width=\"10\" height=\"10\"/>"));
        assertPass();
    }

    @Test
    void rejectsUnknownNativeReplaceWith() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<g data-pptx-replace-with=\"spaceship\" id=\"bad\"></g>"));
        assertBlockingFailure("unsupported data-pptx-replace-with");
    }

    @Test
    void acceptsOrdinaryGroupWithoutNativeMarker() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<g><rect width=\"10\" height=\"10\" fill=\"#112233\"/></g>"));
        assertPass();
    }

    @Test
    void unmarkedTreeSkipsPresetGeometry() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<g><rect width=\"10\" height=\"10\" fill=\"#112233\"/></g>"));
        assertPass();
    }

    @Test
    void rejectsInvalidMarkedPreset() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<g data-pptx-prst=\"notARealPreset\" data-pptx-frame=\"0 0 100 50\"></g>"));
        assertBlockingFailure("invalid PPTX shape metadata", "Unknown or invalid data-pptx-prst");
    }

    @Test
    void acceptsValidMarkedRectPreset() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<g data-pptx-prst=\"rect\" data-pptx-frame=\"10 10 100 50\" "
                        + "data-pptx-object=\"shape\"></g>"));
        assertPass();
    }

    @Test
    void skipsPreservedTxBodyWithoutPayload() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<g data-pptx-object=\"shape\" data-pptx-prst=\"rect\" "
                        + "data-pptx-frame=\"0 0 10 10\"></g>"));
        assertPass();
    }

    @Test
    void rejectsPreservedTxBodyMissingHash() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<g data-pptx-object=\"shape\" data-pptx-prst=\"rect\" data-pptx-frame=\"0 0 10 10\">"
                        + "<metadata data-pptx-part=\"txbody\" data-pptx-encoding=\"base64\">"
                        + "PHAtdHhCb2R5Lz4=</metadata>"
                        + "<rect data-pptx-part=\"geometry\" width=\"10\" height=\"10\" fill=\"#112233\"/>"
                        + "</g>"));
        assertBlockingFailure("cannot preserve source txBody", "text hash");
    }

    @Test
    void rejectsOverlappingModuleBounds() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<g id=\"a\" data-pptx-bounds=\"0 0 200 200\"></g>"
                        + "<g id=\"b\" data-pptx-bounds=\"50 50 200 200\"></g>"));
        assertBlockingFailure("overlaps", "data-pptx-bounds");
    }

    @Test
    void acceptsDisjointModuleBounds() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<g id=\"a\" data-pptx-bounds=\"0 0 100 100\"></g>"
                        + "<g id=\"b\" data-pptx-bounds=\"200 0 100 100\"></g>"));
        assertPass();
    }

    @Test
    void rejectsTextOverflowingCanvas() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<text x=\"1200\" y=\"100\" font-size=\"48\" fill=\"#112233\">"
                        + "OVERFLOWING_TEXT_BLOCK_FOR_BOUNDS</text>"));
        assertBlockingFailure("exceeds the root viewBox");
    }

    @Test
    void acceptsOnCanvasText() throws Exception {
        writeSvg(
                "slide_01.svg",
                cleanSvg("<text x=\"40\" y=\"80\" font-size=\"24\" fill=\"#112233\">Hello</text>"));
        assertPass();
    }

    @Test
    void rejectsTypographySizeRecurrenceOnThirdOccurrence() throws Exception {
        writeSpecLock("""
                ## typography
                | font_family | Arial |
                | title | 32 |
                | body | 16 |
                ## colors
                | text | #112233 |
                | bg | #FFFFFF |
                | primary | #4F81BD |
                ## canvas
                | viewBox | 0 0 1280 720 |
                ## pptx_structure
                | mode | flat |
                """);
        String text = "<text x=\"40\" y=\"80\" font-size=\"28\" fill=\"#112233\">Hi</text>";
        writeSvg("slide_01.svg", cleanSvg(text));
        writeSvg("slide_02.svg", cleanSvg(text));
        writeSvg("slide_03.svg", cleanSvg(text));
        assertBlockingFailure("typography-size recurrence", "28");
    }

    @Test
    void acceptsSparseUndeclaredSizeTwice() throws Exception {
        writeSpecLock("""
                ## typography
                | font_family | Arial |
                | title | 32 |
                | body | 16 |
                ## colors
                | text | #112233 |
                | bg | #FFFFFF |
                | primary | #4F81BD |
                ## canvas
                | viewBox | 0 0 1280 720 |
                ## pptx_structure
                | mode | flat |
                """);
        String text = "<text x=\"40\" y=\"80\" font-size=\"28\" fill=\"#112233\">Hi</text>";
        writeSvg("slide_01.svg", cleanSvg(text));
        writeSvg("slide_02.svg", cleanSvg(text));
        assertPass();
    }

    private void writeSpecLock(String content) throws IOException {
        Files.writeString(project.resolve("spec_lock.md"), content, StandardCharsets.UTF_8);
    }

    private void assertPass() throws Exception {
        EngineResult result = checker.check(project, "ppt169", Duration.ZERO);
        assertThat(result.successful()).as(result.output()).isTrue();
        JsonNode report = readReport();
        assertThat(report.path("categories").path("blocking").path("count").asInt()).isZero();
    }

    private void assertBlockingFailure(String... substrings) throws Exception {
        EngineResult result = checker.check(project, "ppt169", Duration.ZERO);
        assertThat(result.exitCode()).isEqualTo(1);
        for (String substring : substrings) {
            assertThat(result.output()).contains(substring);
        }
        JsonNode report = readReport();
        assertThat(report.path("categories").path("blocking").path("count").asInt()).isGreaterThan(0);
    }

    private JsonNode readReport() throws Exception {
        Path reportPath = project.resolve("validation/svg_quality_report.json");
        assertThat(Files.isRegularFile(reportPath)).isTrue();
        return new ObjectMapper().readTree(Files.readString(reportPath));
    }

    private static String cleanSvg(String body) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\">" + body + "</svg>";
    }

    private void writeSvg(String name, String content) throws IOException {
        Files.writeString(svgOutput.resolve(name), content, StandardCharsets.UTF_8);
    }
}
