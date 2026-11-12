package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.PptAudioFishProperties;
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
        assertThat(health.toString()).doesNotContain("api-key");
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

    @Test
    void fishDownWhenApiKeyMissing() {
        NativeEnhanceReadinessContributor contributor = new NativeEnhanceReadinessContributor(
                "ffprobe", PptAudioFishProperties.defaults());
        assertThat(contributor.isFishAudioAvailable()).isFalse();
        assertThat(contributor.checkFish().get("reasonCode"))
                .isEqualTo(NativeEnhanceReadinessContributor.REASON_FISH_API_KEY_MISSING);
        Health health = contributor.health();
        assertThat(health.getDetails()).containsKey("fish");
        assertThat(String.valueOf(health.getDetails().get("fish"))).doesNotContain("sk-");
    }

    @Test
    void fishUpWhenApiKeyConfigured() {
        PptAudioFishProperties fish = new PptAudioFishProperties(
                null, "sk-fish-audio-key-123456", null, null, null, null);
        NativeEnhanceReadinessContributor contributor =
                new NativeEnhanceReadinessContributor("ffprobe", fish);
        assertThat(contributor.isFishAudioAvailable()).isTrue();
        assertThat(contributor.checkFish().get("reasonCode"))
                .isEqualTo(NativeEnhanceReadinessContributor.REASON_OK);
    }
}
