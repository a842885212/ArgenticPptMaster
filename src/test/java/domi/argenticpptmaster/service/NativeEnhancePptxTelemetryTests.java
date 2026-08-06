package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.domain.NativeEnhanceErrorCode;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class NativeEnhancePptxTelemetryTests {

    @Test
    void usesClosedLowCardinalityLabelsOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NativeEnhancePptxTelemetry telemetry = new NativeEnhancePptxTelemetry(registry);

        telemetry.recordCreation(NativeEnhancePptxTelemetry.CreationOutcome.ACCEPTED);
        telemetry.recordCreation(NativeEnhancePptxTelemetry.CreationOutcome.REJECTED);
        telemetry.recordStage(NativeEnhancePptxTelemetry.Stage.INIT,
                NativeEnhancePptxTelemetry.Outcome.SUCCESS, Duration.ofMillis(12));
        telemetry.recordDecision(NativeEnhancePptxTelemetry.Decision.REJECT);
        telemetry.recordRecovery(true);
        telemetry.recordError(NativeEnhanceErrorCode.NATIVE_ENHANCE_VALIDATE_FAILED);
        telemetry.recordError("job-id-should-become-unknown");

        Set<String> tagKeys = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey() + "=" + tag.getValue())
                .collect(Collectors.toSet());

        assertThat(tagKeys).noneMatch(tag -> tag.contains("job") || tag.contains("tenant"));
        assertThat(tagKeys).anyMatch(tag -> tag.equals("error_code=UNKNOWN"));
        assertThat(telemetry.maxLabelCardinality()).isLessThan(500);
        assertThat(registry.getMeters().stream().map(Meter::getId).map(id -> id.getName()).collect(Collectors.toSet()))
                .contains(
                        NativeEnhancePptxTelemetry.METRIC_CREATION,
                        NativeEnhancePptxTelemetry.METRIC_STAGE,
                        NativeEnhancePptxTelemetry.METRIC_DECISION);
    }
}
