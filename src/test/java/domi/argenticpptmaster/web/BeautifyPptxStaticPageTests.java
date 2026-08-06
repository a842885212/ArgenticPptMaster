package domi.argenticpptmaster.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 不启动完整 Spring 上下文的静态页契约检查。 */
class BeautifyPptxStaticPageTests {

    @Test
    void pageHasSingleUploadAndHardConstraintCopy() throws Exception {
        String html = new ClassPathResource("static/beautify-pptx/index.html")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("源 PPTX");
        assertThat(html).contains("id=\"sourceFile\"");
        assertThat(html).contains("semanticAck");
        assertThat(html).contains("内容与页序冻结");
        assertThat(html).contains("仅重做布局");
        assertThat(html).doesNotContain("templateFile");
        assertThat(html).doesNotContain("内容资料");
        assertThat(html).doesNotContain("模板 PPTX");
    }

    @Test
    void clientScriptUsesBeautifyModeAndSseBackoff() throws Exception {
        String js = new ClassPathResource("static/beautify-pptx/app.js")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(js).contains("sourceFile");
        assertThat(js).contains("\"files\"");
        assertThat(js).contains("semanticAck");
        assertThat(js).contains("workflowMode");
        assertThat(js).contains("beautify-pptx");
        assertThat(js).contains("EventSource");
        assertThat(js).contains("reconnectAttempt");
        assertThat(js).contains("planVersion");
        assertThat(js).contains("planDigest");
        assertThat(js).contains("downloadReady");
        assertThat(js).doesNotContain("templateFile");
        assertThat(js).doesNotContain("storedPath");
    }
}
