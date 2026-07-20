package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/** 验证脱敏 template-fill 夹具可被稳定读取，而非依赖完整二进制快照。 */
class TemplateFillFixturesTests {

    private static final String TEMPLATE_RESOURCE = "/template-fill/minimal-template.pptx";
    private static final String TEMPLATE_SHA_256 = "ddcaef381c298e0c5f4d1c636731044ed513e30166c07e79dae70ff5896227a3";

    @Test
    void containsStablePptxFingerprintSlideCountAndKeyText() throws Exception {
        try (InputStream resource = getClass().getResourceAsStream(TEMPLATE_RESOURCE)) {
            assertThat(resource).isNotNull();
            byte[] bytes = resource.readAllBytes();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo(TEMPLATE_SHA_256);
        }

        var resourceUrl = getClass().getResource(TEMPLATE_RESOURCE);
        assertThat(resourceUrl).isNotNull();
        try (ZipFile pptx = new ZipFile(java.nio.file.Path.of(resourceUrl.toURI()).toFile())) {
            long slideCount = pptx.stream()
                    .filter(entry -> entry.getName().matches("ppt/slides/slide\\d+\\.xml"))
                    .count();
            assertThat(slideCount).isEqualTo(10);
            try (InputStream slide = pptx.getInputStream(pptx.getEntry("ppt/slides/slide1.xml"))) {
                assertThat(new String(slide.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("Kubernetes", "Cluster Architecture");
            }
        }
    }

    @Test
    void documentsSlideLibraryAndCheckReportContract() throws Exception {
        String slideLibrary = readResource("/template-fill/template.slide_library.json");
        assertThat(slideLibrary).contains("\"schema\": \"template_fill_pptx_library.v1\"");
        assertThat(slideLibrary).contains("\"slide_count\": 10");
        assertThat(slideLibrary).contains("\"canvas_px\"");

        String checkReport = readResource("/template-fill/check_report.json");
        assertThat(checkReport).contains("\"schema\": \"template_fill_pptx_check.v1\"");
        assertThat(checkReport).contains("\"warn\": 2");
        assertThat(checkReport).contains("\"error\": 0");
    }

    @Test
    void providesConfirmedDraftAndInvalidFillPlans() throws Exception {
        assertThat(readResource("/template-fill/fill-plan-confirmed.json")).contains("\"status\": \"confirmed\"");
        assertThat(readResource("/template-fill/fill-plan-draft.json")).contains("\"status\": \"draft\"");
        assertThat(readResource("/template-fill/fill-plan-invalid.json")).doesNotContain("}");
        assertThat(readResource("/template-fill/content.md")).contains("模板填充");
    }

    private String readResource(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
