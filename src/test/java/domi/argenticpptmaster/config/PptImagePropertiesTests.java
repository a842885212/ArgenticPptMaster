package domi.argenticpptmaster.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.engine.ImageGenerationGateway;
import domi.argenticpptmaster.engine.file.JavaImageGenerationGateway;
import domi.argenticpptmaster.engine.file.imagegen.OpenAiImageClient;
import domi.argenticpptmaster.engine.python.PythonImageGenerationGateway;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@code ppt.image.*} 配置绑定、密钥回落与出图权威装配的测试。
 */
class PptImagePropertiesTests {

    @TempDir
    Path tempDir;

    @Test
    void appliesDefaultsWhenKeysAreAbsent() {
        PptImageProperties properties = PptImageProperties.defaults();

        assertThat(properties.model()).isEqualTo("gpt-image-2");
        assertThat(properties.effectiveConcurrency()).isEqualTo(5);
        assertThat(properties.itemTimeout()).isEqualTo(Duration.ofSeconds(180));
        assertThat(properties.isPythonFallback()).isFalse();
        assertThat(properties.hasBaseUrl()).isFalse();
    }

    @Test
    void bindsDocumentedKeysFromProperties() {
        Map<String, Object> values = Map.of(
                "ppt.image.base-url", "https://images.example.test/v1",
                "ppt.image.api-key", "sk-image",
                "ppt.image.model", "gpt-image-2-turbo",
                "ppt.image.concurrency", "7",
                "ppt.image.item-timeout", "90s",
                "ppt.image.python-fallback", "true");

        PptImageProperties bound = new Binder(new MapConfigurationPropertySource(values))
                .bind("ppt.image", PptImageProperties.class)
                .get();

        assertThat(bound.baseUrl()).isEqualTo("https://images.example.test/v1");
        assertThat(bound.apiKey()).isEqualTo("sk-image");
        assertThat(bound.model()).isEqualTo("gpt-image-2-turbo");
        assertThat(bound.effectiveConcurrency()).isEqualTo(7);
        assertThat(bound.itemTimeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(bound.isPythonFallback()).isTrue();
    }

    @Test
    void missingBaseUrlFailsBeforeAnyImageRequest() {
        PptImageProperties properties = new PptImageProperties("   ", null, null, null, null, null);

        assertThat(properties.hasBaseUrl()).isFalse();
        assertThatThrownBy(properties::requireBaseUrl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ppt.image.base-url");
    }

    @Test
    void apiKeyFallsBackToChatKeyButImageKeyWins() {
        PptImageProperties withoutKey = new PptImageProperties("https://images.example.test/v1", null,
                null, null, null, null);
        PptImageProperties withKey = new PptImageProperties("https://images.example.test/v1", "  sk-image  ",
                null, null, null, null);

        assertThat(withoutKey.effectiveApiKey("sk-chat")).isEqualTo("sk-chat");
        assertThat(withKey.effectiveApiKey("sk-chat")).isEqualTo("sk-image");
        assertThat(withoutKey.effectiveApiKey(null)).isNull();
    }

    @Test
    void javaGatewayIsTheProductionDefaultAndPythonRequiresExplicitFallback() {
        PptEngineConfiguration configuration = new PptEngineConfiguration();
        PptMasterCommandExecutor commands = new PptMasterCommandExecutor(
                PptMasterProperties.forTest(tempDir, tempDir.resolve("workspace")));
        AgentScopeProperties chat = new AgentScopeProperties(
                "openai", "chat-model", "sk-chat", "http://chat.example.test/v1",
                8, tempDir.resolve("sessions"), "ppt-master-service", null, null, null);

        ImageGenerationGateway java = configuration.imageGenerationGateway(
                commands,
                new PptImageProperties("https://images.example.test/v1", null, null, null, null, null),
                chat);
        ImageGenerationGateway python = configuration.imageGenerationGateway(
                commands,
                new PptImageProperties(null, null, null, null, null, true),
                chat);

        assertThat(java).isInstanceOf(JavaImageGenerationGateway.class);
        assertThat(python).isInstanceOf(PythonImageGenerationGateway.class);
    }

    @Test
    void imageEndpointIsDerivedOnlyFromTheConfiguredImageBaseUrl() {
        PptImageProperties image = new PptImageProperties(
                "https://images.example.test/v1/", null, null, null, null, null);

        assertThat(new OpenAiImageClient(image, "sk-image").endpoint(image.requireBaseUrl()))
                .isEqualTo(URI.create("https://images.example.test/v1/images/generations"));
        assertThat(PptImageProperties.defaults().hasBaseUrl()).isFalse();
    }
}
