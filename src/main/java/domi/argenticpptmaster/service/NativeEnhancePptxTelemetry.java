package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.NativeEnhanceErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality Micrometer adapter for native-enhance-pptx production metrics.
 * <p>
 * All tag values are drawn from closed enums; unknown inputs map to {@code UNKNOWN}.
 * Job identifiers, tenants and free text MUST NOT be used as labels.
 * </p>
 */
@Component
public class NativeEnhancePptxTelemetry {

    public static final String METRIC_CREATION = "native.enhance.pptx.creation.total";
    public static final String METRIC_STAGE = "native.enhance.pptx.stage.total";
    public static final String METRIC_STAGE_DURATION = "native.enhance.pptx.stage.duration";
    public static final String METRIC_ERROR = "native.enhance.pptx.error.total";
    public static final String METRIC_DECISION = "native.enhance.pptx.decision.total";
    public static final String METRIC_RECOVERY = "native.enhance.pptx.recovery.total";

    public enum CreationOutcome {
        ACCEPTED,
        REJECTED
    }

    public enum Stage {
        INIT,
        PLAN,
        CONFIRM_PLAN,
        NOTES,
        CONFIRM_VOICE,
        AUDIO,
        VALIDATE,
        APPLY,
        READBACK,
        RESUME,
        CLEANUP,
        DIAGNOSTICS,
        UNKNOWN
    }

    public enum Outcome {
        SUCCESS,
        FAILURE,
        REJECTED,
        UNKNOWN
    }

    public enum Decision {
        ACCEPT,
        REJECT,
        UNKNOWN
    }

    private final ModeTelemetryRecorder recorder;

    public NativeEnhancePptxTelemetry(MeterRegistry registry) {
        this.recorder = new ModeTelemetryRecorder(
                registry,
                new ModeTelemetryRecorder.MetricNames(
                        METRIC_CREATION,
                        METRIC_STAGE,
                        METRIC_STAGE_DURATION,
                        METRIC_ERROR,
                        METRIC_DECISION,
                        METRIC_RECOVERY,
                        null),
                Arrays.stream(Stage.values()).map(Enum::name).toList(),
                Arrays.stream(Outcome.values()).map(Enum::name).toList(),
                Arrays.stream(Decision.values()).map(Enum::name).toList(),
                Arrays.stream(NativeEnhanceErrorCode.values()).map(Enum::name).toList(),
                List.of());
    }

    public static NativeEnhancePptxTelemetry noop(MeterRegistry registry) {
        return new NativeEnhancePptxTelemetry(registry);
    }

    public void recordCreation(CreationOutcome outcome) {
        if (outcome == CreationOutcome.ACCEPTED) {
            recorder.recordCreationAccepted();
        } else {
            recorder.recordCreationRejected();
        }
    }

    public void recordStage(Stage stage, Outcome outcome, Duration duration) {
        recorder.recordStage(stage == null ? null : stage.name(), outcome == null ? null : outcome.name(), duration);
    }

    public void recordError(NativeEnhanceErrorCode errorCode) {
        recorder.recordError(errorCode == null ? null : errorCode.name());
    }

    public void recordError(String errorCode) {
        recorder.recordError(errorCode);
    }

    public void recordDecision(Decision decision) {
        recorder.recordDecision(decision == null ? null : decision.name());
    }

    public void recordRecovery(boolean success) {
        recorder.recordRecovery(success);
    }

    /** Upper bound on distinct meter series emitted by this adapter. */
    public int maxLabelCardinality() {
        return recorder.maxLabelCardinality();
    }
}
