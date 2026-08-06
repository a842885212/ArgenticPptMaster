package domi.argenticpptmaster.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class NativeEnhancePptxStaticPageTests {

    @Test
    void staticPageContainsHardConstraintAndWorkflowMode() throws Exception {
        ClassPathResource page = new ClassPathResource("static/native-enhance-pptx/index.html");
        ClassPathResource app = new ClassPathResource("static/native-enhance-pptx/app.js");
        assertThat(page.exists()).isTrue();
        assertThat(app.exists()).isTrue();
        String html = page.getContentAsString(StandardCharsets.UTF_8);
        String js = app.getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("不改版式与可见内容，仅追加备注/旁白/切换");
        assertThat(js).contains("native-enhance-pptx");
        assertThat(js).contains("enhancement_plan_confirmation");
        assertThat(js).contains("audio_voice_confirmation");
    }
}
