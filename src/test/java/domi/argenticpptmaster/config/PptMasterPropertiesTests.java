package domi.argenticpptmaster.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PptMasterPropertiesTests {

    @Test
    void templateFillSecurityDefaultsToDisabledAndBoundedPlanSize() {
        PptMasterProperties properties = new PptMasterProperties(
                Path.of("repo"), Path.of("workspace"), "python3", Duration.ofMinutes(1), null, 0, 0, 0, 0, 0, null);

        assertThat(properties.templateFillDebugToken()).isNull();
        assertThat(properties.templateFillPlanMaxBytes()).isEqualTo(1_048_576L);
        assertThat(properties.templateFillTemplateMaxBytes()).isEqualTo(52_428_800L);
        assertThat(properties.templateFillContentMaxBytes()).isEqualTo(20_971_520L);
        assertThat(properties.templateFillTotalUploadMaxBytes()).isEqualTo(104_857_600L);
        assertThat(properties.templateFillMaxConcurrentJobs()).isEqualTo(2);
        assertThat(properties.templateFillAnalyzeTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.toString()).doesNotContain("debug");
    }

    @Test
    void acceptsTemplateFillResourceOverrides() {
        PptMasterProperties properties = new PptMasterProperties(
                Path.of("repo"), Path.of("workspace"), "python3", Duration.ofMinutes(1), null, 2048L,
                100L, 200L, 300L, 4, Duration.ofSeconds(30));

        assertThat(properties.templateFillPlanMaxBytes()).isEqualTo(2048L);
        assertThat(properties.templateFillTemplateMaxBytes()).isEqualTo(100L);
        assertThat(properties.templateFillContentMaxBytes()).isEqualTo(200L);
        assertThat(properties.templateFillTotalUploadMaxBytes()).isEqualTo(300L);
        assertThat(properties.templateFillMaxConcurrentJobs()).isEqualTo(4);
        assertThat(properties.templateFillAnalyzeTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void acceptsTemplateFillOverridesWithoutLeakingToken() {
        PptMasterProperties properties = new PptMasterProperties(
                Path.of("repo"), Path.of("workspace"), "python3", Duration.ofMinutes(1), "secret-token", 2048L,
                0, 0, 0, 0, null);

        assertThat(properties.templateFillDebugToken()).isEqualTo("secret-token");
        assertThat(properties.templateFillPlanMaxBytes()).isEqualTo(2048L);
        assertThat(properties.toString()).doesNotContain("secret-token");
    }
}
