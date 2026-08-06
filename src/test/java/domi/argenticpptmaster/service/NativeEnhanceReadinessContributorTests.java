package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class NativeEnhanceReadinessContributorTests {

    @TempDir
    Path tempDir;

    @Test
    void ffprobeDownWhenMissing() {
        NativeEnhanceReadinessContributor contributor =
                new NativeEnhanceReadinessContributor(tempDir.resolve("missing-ffprobe").toString());
        Map<String, String> component = contributor.checkFfprobe();
        assertThat(component.get("status")).isEqualTo("DOWN");
        assertThat(component.get("reasonCode"))
                .isEqualTo(NativeEnhanceReadinessContributor.REASON_FFPROBE_MISSING);
        assertThat(contributor.isFfprobeAvailable()).isFalse();
        Health health = contributor.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void ffprobeUpWhenExecutablePresent() throws Exception {
        Path binary = tempDir.resolve("ffprobe");
        Files.writeString(binary, "#!/bin/sh\necho ok\n");
        binary.toFile().setExecutable(true);
        NativeEnhanceReadinessContributor contributor =
                new NativeEnhanceReadinessContributor(binary.toString());
        assertThat(contributor.isFfprobeAvailable()).isTrue();
        assertThat(contributor.checkFfprobe().get("reasonCode"))
                .isEqualTo(NativeEnhanceReadinessContributor.REASON_OK);
    }
}
