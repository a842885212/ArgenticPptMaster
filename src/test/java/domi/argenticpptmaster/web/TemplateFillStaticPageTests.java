package domi.argenticpptmaster.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 不启动完整 Spring 上下文的静态页契约检查。 */
class TemplateFillStaticPageTests {

    @Test
    void pageHasDualUploadZonesAndSemanticAck() throws Exception {
        String html = new ClassPathResource("static/template-fill/index.html")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("模板 PPTX");
        assertThat(html).contains("内容资料");
        assertThat(html).contains("id=\"templateFile\"");
        assertThat(html).contains("id=\"contentFiles\"");
        assertThat(html).contains("semanticAck");
        assertThat(html).contains("选择或克隆");
    }

    @Test
    void clientScriptKeepsRolesAndRequiresAck() throws Exception {
        String js = new ClassPathResource("static/template-fill/app.js")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(js).contains("templateFile");
        assertThat(js).contains("\"files\"");
        assertThat(js).contains("semanticAck");
        assertThat(js).contains("workflowMode");
        assertThat(js).contains("template-fill");
        assertThat(js).contains("EventSource");
        assertThat(js).doesNotContain("storedPath");
    }
}
