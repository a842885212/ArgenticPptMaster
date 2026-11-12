package domi.argenticpptmaster.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.engine.NotesAudioGateway;
import domi.argenticpptmaster.engine.file.JavaFishNotesAudioGateway;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PptAudioFishPropertiesTests {

    @Test
    void appliesDefaultsWhenKeysAreAbsent() {
        PptAudioFishProperties properties = PptAudioFishProperties.defaults();

        assertThat(properties.apiUrl()).isEqualTo("https://api.fish.audio/v1");
        assertThat(properties.model()).isEqualTo("s2.1-pro-free");
        assertThat(properties.effectiveConcurrency()).isEqualTo(2);
        assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.hasApiKey()).isFalse();
        assertThat(properties.effectiveFallbackModel()).isNull();
    }

    @Test
    void shortApiKeyIsTreatedAsMissing() {
        PptAudioFishProperties properties = new PptAudioFishProperties(
                null, "short", null, null, null, null);
        assertThat(properties.hasApiKey()).isFalse();
        assertThatThrownBy(properties::requireApiKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ppt.audio.fish.api-key");
    }

    @Test
    void bindsDocumentedKeysFromProperties() {
        Map<String, Object> values = Map.of(
                "ppt.audio.fish.api-url", "https://fish.example.test/v1/",
                "ppt.audio.fish.api-key", "sk-fish-audio-key-123456",
                "ppt.audio.fish.model", "s2.1-pro",
                "ppt.audio.fish.fallback-model", "drama-3",
                "ppt.audio.fish.concurrency", "3",
                "ppt.audio.fish.request-timeout", "45s");

        PptAudioFishProperties bound = new Binder(new MapConfigurationPropertySource(values))
                .bind("ppt.audio.fish", PptAudioFishProperties.class)
                .get();

        assertThat(bound.apiUrl()).isEqualTo("https://fish.example.test/v1");
        assertThat(bound.hasApiKey()).isTrue();
        assertThat(bound.model()).isEqualTo("s2.1-pro");
        assertThat(bound.effectiveFallbackModel()).isEqualTo("drama-3");
        assertThat(bound.effectiveConcurrency()).isEqualTo(3);
        assertThat(bound.requestTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void engineConfigurationWiresJavaFishGatewayOnly() {
        PptEngineConfiguration configuration = new PptEngineConfiguration();
        NotesAudioGateway gateway = configuration.notesAudioGateway(
                new PptAudioFishProperties(null, "sk-fish-audio-key-123456", null, null, null, null));
        assertThat(gateway).isInstanceOf(JavaFishNotesAudioGateway.class);
    }
}
