package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.domain.BeautifyPptxErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BeautifyPptxTelemetryTests {

    private SimpleMeterRegistry registry;
    private BeautifyPptxTelemetry telemetry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        telemetry = new BeautifyPptxTelemetry(registry);
    }

    @Test
    void mapsUnknownErrorCodesToUnknownLabel() {
        telemetry.recordError("not-a-real-code");
        telemetry.recordError((BeautifyPptxErrorCode) null);
        telemetry.recordError("");

        Counter unknown = registry.find(BeautifyPptxTelemetry.METRIC_ERROR)
                .tag("error_code", "UNKNOWN")
                .counter();
        assertThat(unknown.count()).isEqualTo(3.0);
        assertThat(registry.find(BeautifyPptxTelemetry.METRIC_ERROR)
                .tag("error_code", "not-a-real-code")
                .counter()).isNull();
    }

    @Test
    void recordsStageDurationAndOutcomeWithoutHighCardinalityTags() {
        telemetry.recordStage(BeautifyPptxTelemetry.Stage.IDENTITY,
                BeautifyPptxTelemetry.Outcome.SUCCESS, Duration.ofMillis(250));

        Counter stageCounter = registry.find(BeautifyPptxTelemetry.METRIC_STAGE)
                .tag("stage", "IDENTITY")
                .tag("outcome", "SUCCESS")
                .counter();
        assertThat(stageCounter.count()).isEqualTo(1.0);
        assertThat(registry.find(BeautifyPptxTelemetry.METRIC_STAGE_DURATION)
                .tag("stage", "IDENTITY")
                .timer()
                .count()).isEqualTo(1L);
    }

    @Test
    void rejectsUnrecognizedEnumValuesViaUnknownBucket() {
        telemetry.recordStage(null, null, null);
        telemetry.recordDecision(null);
        telemetry.recordIncompatible(null);

        assertThat(registry.find(BeautifyPptxTelemetry.METRIC_STAGE)
                .tag("stage", "UNKNOWN")
                .tag("outcome", "UNKNOWN")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.find(BeautifyPptxTelemetry.METRIC_DECISION)
                .tag("decision", "UNKNOWN")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.find(BeautifyPptxTelemetry.METRIC_INCOMPATIBLE)
                .tag("object_category", "UNKNOWN")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void boundsLabelCardinality() {
        telemetry.recordCreation(BeautifyPptxTelemetry.CreationOutcome.ACCEPTED);
        telemetry.recordCreation(BeautifyPptxTelemetry.CreationOutcome.REJECTED);
        for (BeautifyPptxTelemetry.Stage stage : BeautifyPptxTelemetry.Stage.values()) {
            telemetry.recordStage(stage, BeautifyPptxTelemetry.Outcome.SUCCESS, Duration.ofMillis(1));
        }
        for (BeautifyPptxErrorCode code : BeautifyPptxErrorCode.values()) {
            telemetry.recordError(code);
        }
        telemetry.recordError("free-text-should-not-become-label");
        for (BeautifyPptxTelemetry.Decision decision : BeautifyPptxTelemetry.Decision.values()) {
            telemetry.recordDecision(decision);
        }
        telemetry.recordRecovery(true);
        telemetry.recordRecovery(false);
        for (BeautifyPptxTelemetry.ObjectCategory category : BeautifyPptxTelemetry.ObjectCategory.values()) {
            telemetry.recordIncompatible(category);
        }

        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getTags()).noneMatch(tag ->
                    tag.getValue().matches(".*[/\\\\].*")
                            || tag.getValue().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                            || tag.getValue().contains("tenant"));
        }
        assertThat(registry.getMeters()).hasSizeLessThanOrEqualTo(telemetry.maxLabelCardinality());
    }

    @Test
    void recordsStableErrorCodes() {
        telemetry.recordError(BeautifyPptxErrorCode.BEAUTIFY_READBACK_FAILED);

        assertThat(registry.find(BeautifyPptxTelemetry.METRIC_ERROR)
                .tag("error_code", "BEAUTIFY_READBACK_FAILED")
                .counter()
                .count()).isEqualTo(1.0);
    }
}
