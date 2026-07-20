package domi.argenticpptmaster.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PptMasterPropertiesTests {

    @Test
    void templateFillSecurityDefaultsToDisabledAndBoundedPlanSize() {
        PptMasterProperties properties = new PptMasterProperties(
                Path.of("repo"), Path.of("workspace"), "python3", Duration.ofMinutes(1), null, 0);

        assertThat(properties.templateFillDebugToken()).isNull();
        assertThat(properties.templateFillPlanMaxBytes()).isEqualTo(1_048_576L);
        assertThat(properties.toString()).doesNotContain("debug");
    }

    @Test
    void acceptsTemplateFillOverridesWithoutLeakingToken() {
        PptMasterProperties properties = new PptMasterProperties(
                Path.of("repo"), Path.of("workspace"), "python3", Duration.ofMinutes(1), "secret-token", 2048L);

        assertThat(properties.templateFillDebugToken()).isEqualTo("secret-token");
        assertThat(properties.templateFillPlanMaxBytes()).isEqualTo(2048L);
        assertThat(properties.toString()).doesNotContain("secret-token");
    }
}
