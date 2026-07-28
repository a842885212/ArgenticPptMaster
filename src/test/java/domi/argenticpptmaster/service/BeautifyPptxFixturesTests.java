package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/** 验证阶段 0 合成 PPTX fixture 的结构契约，不依赖二进制快照。 */
class BeautifyPptxFixturesTests {

    private static final String FIXTURE_RESOURCE = "/beautify-pptx/phase0-baseline.pptx";

    @Test
    void providesSevenSlidesCoveringThePhaseZeroPageTypes() throws Exception {
        var resourceUrl = getClass().getResource(FIXTURE_RESOURCE);
        assertThat(resourceUrl).isNotNull();

        try (ZipFile pptx = new ZipFile(java.nio.file.Path.of(resourceUrl.toURI()).toFile())) {
            long slideCount = pptx.stream()
                    .filter(entry -> entry.getName().matches("ppt/slides/slide\\d+\\.xml"))
                    .count();
            assertThat(slideCount).isEqualTo(7);

            assertThat(slideXml(pptx, 1)).contains("Beautify Phase 0 Baseline");
            assertThat(slideXml(pptx, 3)).contains("Image Re-layout");
            assertThat(slideXml(pptx, 4)).contains("Table Fidelity", "Metric", "Q1", "Q2");
            assertThat(slideXml(pptx, 5)).contains("Chart Fidelity");
            assertThat(zipXml(pptx, "ppt/charts/chart1.xml")).contains("North", "South", "Actual", "Plan");
            assertThat(slideXml(pptx, 6)).contains("Density Check");
            assertThat(slideXml(pptx, 7)).contains("Pause");
        }
    }

    @Test
    void providesARedactedMachineReadableExpectationManifest() throws Exception {
        String manifest = readResource("/beautify-pptx/expected.json");
        assertThat(manifest).contains("\"slideCount\": 7");
        assertThat(manifest).contains("\"pageTypes\"");
        assertThat(manifest).contains("\"charts\"");
        assertThat(manifest).contains("\"tables\"");
        assertThat(manifest).doesNotContain("/home/", "var/ppt-master");
    }

    @Test
    void providesPureTextFixtureWithRepeatedDenseAndNearEmptySlides() throws Exception {
        var resourceUrl = getClass().getResource("/beautify-pptx/pure-text-mvp.pptx");
        assertThat(resourceUrl).isNotNull();
        String expected = readResource("/beautify-pptx/pure-text-mvp.expected.json");

        try (ZipFile pptx = new ZipFile(java.nio.file.Path.of(resourceUrl.toURI()).toFile())) {
            assertThat(pptx.stream().filter(entry -> entry.getName().matches("ppt/slides/slide\\d+\\.xml")).count())
                    .isEqualTo(4);
            assertThat(slideXml(pptx, 2)).contains("Keep me", "Dense page starts here");
            assertThat(slideXml(pptx, 3)).contains("One", "Eight");
            assertThat(slideXml(pptx, 4)).contains("Pause");
            assertThat(pptx.stream().noneMatch(entry -> entry.getName().startsWith("ppt/media/"))).isTrue();
            assertThat(pptx.stream().noneMatch(entry -> entry.getName().startsWith("ppt/charts/"))).isTrue();
        }
        assertThat(expected).contains("\"slideCount\": 4", "Keep me", "Dense page");
    }

    private String slideXml(ZipFile pptx, int slideNumber) throws Exception {
        return zipXml(pptx, "ppt/slides/slide" + slideNumber + ".xml");
    }

    private String zipXml(ZipFile pptx, String entryName) throws Exception {
        try (InputStream input = pptx.getInputStream(pptx.getEntry(entryName))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String readResource(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
