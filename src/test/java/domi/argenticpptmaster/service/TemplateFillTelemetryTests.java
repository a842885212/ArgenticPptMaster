package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.domain.TemplateFillErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TemplateFillTelemetryTests {

    private SimpleMeterRegistry registry;
    private TemplateFillTelemetry telemetry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        telemetry = new TemplateFillTelemetry(registry);
    }

    @Test
    void mapsUnknownErrorCodesToUnknownLabel() {
        telemetry.recordError("not-a-real-code");
        telemetry.recordError((TemplateFillErrorCode) null);
        telemetry.recordError("");

        Counter unknown = registry.find(TemplateFillTelemetry.METRIC_ERROR)
                .tag("error_code", "UNKNOWN")
                .counter();
        assertThat(unknown.count()).isEqualTo(3.0);
        assertThat(registry.find(TemplateFillTelemetry.METRIC_ERROR)
                .tag("error_code", "not-a-real-code")
                .counter()).isNull();
    }

    @Test
    void recordsStageDurationAndOutcomeWithoutHighCardinalityTags() {
        telemetry.recordStage(TemplateFillTelemetry.Stage.ANALYZE,
                TemplateFillTelemetry.Outcome.SUCCESS, Duration.ofMillis(250));

        Counter stageCounter = registry.find(TemplateFillTelemetry.METRIC_STAGE)
                .tag("stage", "ANALYZE")
                .tag("outcome", "SUCCESS")
                .counter();
        assertThat(stageCounter.count()).isEqualTo(1.0);
        assertThat(registry.find(TemplateFillTelemetry.METRIC_STAGE_DURATION)
                .tag("stage", "ANALYZE")
                .timer()
                .count()).isEqualTo(1L);
    }

    @Test
    void rejectsUnrecognizedEnumValuesViaUnknownBucket() {
        telemetry.recordStage(null, null, null);
        telemetry.recordDecision(null);
        telemetry.recordIncompatible(null);

        assertThat(registry.find(TemplateFillTelemetry.METRIC_STAGE)
                .tag("stage", "UNKNOWN")
                .tag("outcome", "UNKNOWN")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.find(TemplateFillTelemetry.METRIC_DECISION)
                .tag("decision", "UNKNOWN")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.find(TemplateFillTelemetry.METRIC_INCOMPATIBLE)
                .tag("object_category", "UNKNOWN")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void boundsLabelCardinality() {
        telemetry.recordCreation(TemplateFillTelemetry.CreationOutcome.ACCEPTED);
        telemetry.recordCreation(TemplateFillTelemetry.CreationOutcome.REJECTED);
        for (TemplateFillTelemetry.Stage stage : TemplateFillTelemetry.Stage.values()) {
            telemetry.recordStage(stage, TemplateFillTelemetry.Outcome.SUCCESS, Duration.ofMillis(1));
        }
        for (TemplateFillErrorCode code : TemplateFillErrorCode.values()) {
            telemetry.recordError(code);
        }
        telemetry.recordError("free-text-should-not-become-label");
        for (TemplateFillTelemetry.Decision decision : TemplateFillTelemetry.Decision.values()) {
            telemetry.recordDecision(decision);
        }
        telemetry.recordRecovery(true);
        telemetry.recordRecovery(false);
        for (TemplateFillTelemetry.ObjectCategory category : TemplateFillTelemetry.ObjectCategory.values()) {
            telemetry.recordIncompatible(category);
        }

        Set<String> tagValues = new HashSet<>();
        for (Meter meter : registry.getMeters()) {
            meter.getId().getTags().forEach(tag -> tagValues.add(tag.getKey() + "=" + tag.getValue()));
            assertThat(meter.getId().getTags()).noneMatch(tag ->
                    tag.getValue().matches(".*[/\\\\].*")
                            || tag.getValue().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                            || tag.getValue().contains("tenant"));
        }
        assertThat(registry.getMeters()).hasSizeLessThanOrEqualTo(telemetry.maxLabelCardinality());
    }

    @Test
    void recordsStableErrorCodes() {
        telemetry.recordError(TemplateFillErrorCode.TEMPLATE_APPLY_FAILED);

        assertThat(registry.find(TemplateFillTelemetry.METRIC_ERROR)
                .tag("error_code", "TEMPLATE_APPLY_FAILED")
                .counter()
                .count()).isEqualTo(1.0);
    }
}
