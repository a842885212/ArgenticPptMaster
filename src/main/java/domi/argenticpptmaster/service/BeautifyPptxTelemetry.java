package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.BeautifyPptxErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality Micrometer adapter for beautify-pptx production metrics.
 * <p>
 * All tag values are drawn from closed enums; unknown inputs map to {@code UNKNOWN}.
 * Job identifiers, tenants and free text MUST NOT be used as labels.
 * </p>
 */
@Component
public class BeautifyPptxTelemetry {

    public static final String METRIC_CREATION = "beautify.pptx.creation.total";
    public static final String METRIC_STAGE = "beautify.pptx.stage.total";
    public static final String METRIC_STAGE_DURATION = "beautify.pptx.stage.duration";
    public static final String METRIC_ERROR = "beautify.pptx.error.total";
    public static final String METRIC_DECISION = "beautify.pptx.decision.total";
    public static final String METRIC_RECOVERY = "beautify.pptx.recovery.total";
    public static final String METRIC_INCOMPATIBLE = "beautify.pptx.incompatible.total";

    public enum CreationOutcome {
        ACCEPTED,
        REJECTED
    }

    public enum Stage {
        IDENTITY,
        PROJECT,
        INTAKE,
        INVENTORY,
        PLAN,
        SVG,
        EXPORT,
        READBACK,
        CLEANUP,
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

    public enum ObjectCategory {
        CHART,
        TABLE,
        IMAGE,
        TEXT,
        COMPLEX,
        SMARTART,
        OLE,
        OTHER,
        UNKNOWN
    }

    private final ModeTelemetryRecorder recorder;

    public BeautifyPptxTelemetry(MeterRegistry registry) {
        this.recorder = new ModeTelemetryRecorder(
                registry,
                new ModeTelemetryRecorder.MetricNames(
                        METRIC_CREATION,
                        METRIC_STAGE,
                        METRIC_STAGE_DURATION,
                        METRIC_ERROR,
                        METRIC_DECISION,
                        METRIC_RECOVERY,
                        METRIC_INCOMPATIBLE),
                Arrays.stream(Stage.values()).map(Enum::name).toList(),
                Arrays.stream(Outcome.values()).map(Enum::name).toList(),
                Arrays.stream(Decision.values()).map(Enum::name).toList(),
                Arrays.stream(BeautifyPptxErrorCode.values()).map(Enum::name).toList(),
                Arrays.stream(ObjectCategory.values()).map(Enum::name).toList());
    }

    public static BeautifyPptxTelemetry noop(MeterRegistry registry) {
        return new BeautifyPptxTelemetry(registry);
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

    public void recordError(BeautifyPptxErrorCode errorCode) {
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

    public void recordIncompatible(ObjectCategory category) {
        recorder.recordIncompatible(category == null ? null : category.name());
    }

    /** Upper bound on distinct meter series emitted by this adapter. */
    public int maxLabelCardinality() {
        return recorder.maxLabelCardinality();
    }
}
