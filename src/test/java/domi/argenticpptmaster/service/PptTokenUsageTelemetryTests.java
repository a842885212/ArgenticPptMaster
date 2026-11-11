package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.AgentScopeProperties.Execution;
import domi.argenticpptmaster.config.AgentScopeProperties.ModelGroup;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PptTokenUsageTelemetryTests {

    private SimpleMeterRegistry registry;
    private PptTokenUsageTelemetry telemetry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        telemetry = new PptTokenUsageTelemetry(registry, properties("kimi-for-coding", "gpt-4o-mini"));
    }

    @Test
    void preRegistersClosedCartesianProduct() {
        int modes = PptWorkflowMode.values().length;
        int providers = 4;
        int models = 3; // primary + fallback + UNKNOWN
        assertThat(registry.getMeters()).hasSize(3 * modes * providers * models);
        assertThat(telemetry.maxLabelCardinality()).isEqualTo(3 * modes * providers * models);
        assertThat(telemetry.knownModels()).containsExactlyInAnyOrder("kimi-for-coding", "gpt-4o-mini", "UNKNOWN");
    }

    @Test
    void incrementsKnownLabels() {
        telemetry.record(PptWorkflowMode.BASIC, "openai", "kimi-for-coding", 12, 5);

        assertThat(counter(PptTokenUsageTelemetry.METRIC_INPUT, "BASIC", "openai", "kimi-for-coding").count())
                .isEqualTo(12.0);
        assertThat(counter(PptTokenUsageTelemetry.METRIC_OUTPUT, "BASIC", "openai", "kimi-for-coding").count())
                .isEqualTo(5.0);
        assertThat(counter(PptTokenUsageTelemetry.METRIC_CALLS, "BASIC", "openai", "kimi-for-coding").count())
                .isEqualTo(1.0);
    }

    @Test
    void mapsUnknownModelCaseSensitivelyToUnknownBucket() {
        telemetry.record(PptWorkflowMode.BASIC, "openai", "Kimi-For-Coding", 3, 1);
        telemetry.record(PptWorkflowMode.BASIC, "openai", "not-configured", 4, 2);

        assertThat(counter(PptTokenUsageTelemetry.METRIC_CALLS, "BASIC", "openai", "UNKNOWN").count())
                .isEqualTo(2.0);
        assertThat(counter(PptTokenUsageTelemetry.METRIC_INPUT, "BASIC", "openai", "UNKNOWN").count())
                .isEqualTo(7.0);
        assertThat(registry.find(PptTokenUsageTelemetry.METRIC_CALLS)
                        .tag("model", "Kimi-For-Coding")
                        .counter())
                .isNull();
    }

    @Test
    void mapsBlankOrIllegalProviderToUnknown() {
        telemetry.record(PptWorkflowMode.IMAGE_ENHANCED, null, "kimi-for-coding", 1, 1);
        telemetry.record(PptWorkflowMode.IMAGE_ENHANCED, "  ", "kimi-for-coding", 2, 2);
        telemetry.record(PptWorkflowMode.IMAGE_ENHANCED, "AzureOpenAI", "kimi-for-coding", 3, 3);

        assertThat(counter(PptTokenUsageTelemetry.METRIC_CALLS, "IMAGE_ENHANCED", "unknown", "kimi-for-coding")
                        .count())
                .isEqualTo(3.0);
        assertThat(counter(PptTokenUsageTelemetry.METRIC_INPUT, "IMAGE_ENHANCED", "unknown", "kimi-for-coding")
                        .count())
                .isEqualTo(6.0);
    }

    @Test
    void normalizesProviderCaseToLowercaseClosedSet() {
        telemetry.record(PptWorkflowMode.BASIC, "OpenAI", "kimi-for-coding", 1, 0);

        assertThat(counter(PptTokenUsageTelemetry.METRIC_CALLS, "BASIC", "openai", "kimi-for-coding").count())
                .isEqualTo(1.0);
    }

    @Test
    void boundsLabelCardinalityWithoutHighCardinalityTags() {
        for (PptWorkflowMode mode : PptWorkflowMode.values()) {
            telemetry.record(mode, "openai", "kimi-for-coding", 1, 1);
        }
        telemetry.record(PptWorkflowMode.BASIC, "free-text-provider", "free-text-model", 9, 9);

        Set<String> tagValues = new HashSet<>();
        for (Meter meter : registry.getMeters()) {
            meter.getId().getTags().forEach(tag -> tagValues.add(tag.getKey() + "=" + tag.getValue()));
            assertThat(meter.getId().getTags())
                    .noneMatch(tag -> tag.getValue().contains("tenant")
                            || tag.getValue().matches(
                                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        }
        assertThat(registry.getMeters()).hasSizeLessThanOrEqualTo(telemetry.maxLabelCardinality());
        assertThat(tagValues).doesNotContain("provider=free-text-provider", "model=free-text-model");
        assertThat(tagValues).contains("provider=unknown", "model=UNKNOWN");
    }

    private Counter counter(String name, String mode, String provider, String model) {
        Counter found = registry.find(name)
                .tag(PptTokenUsageTelemetry.TAG_MODE, mode)
                .tag(PptTokenUsageTelemetry.TAG_PROVIDER, provider)
                .tag(PptTokenUsageTelemetry.TAG_MODEL, model)
                .counter();
        assertThat(found).isNotNull();
        return found;
    }

    private static AgentScopeProperties properties(String primaryModel, String fallbackModel) {
        return new AgentScopeProperties(
                "openai",
                primaryModel,
                null,
                null,
                8,
                Path.of("sessions"),
                "ppt-master-service",
                new ModelGroup("openai", primaryModel, null, null),
                List.of(new ModelGroup("openai", fallbackModel, null, null)),
                new Execution(3, Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(120)));
    }
}
