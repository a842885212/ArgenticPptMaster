package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.TemplateFillErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality Micrometer adapter for template-fill production metrics.
 * <p>
 * All tag values are drawn from closed enums; unknown inputs map to {@code UNKNOWN}.
 * Job identifiers, tenants and free text MUST NOT be used as labels.
 * </p>
 */
@Component
public class TemplateFillTelemetry {

    public static final String METRIC_CREATION = "template.fill.creation.total";
    public static final String METRIC_STAGE = "template.fill.stage.total";
    public static final String METRIC_STAGE_DURATION = "template.fill.stage.duration";
    public static final String METRIC_ERROR = "template.fill.error.total";
    public static final String METRIC_DECISION = "template.fill.decision.total";
    public static final String METRIC_RECOVERY = "template.fill.recovery.total";
    public static final String METRIC_INCOMPATIBLE = "template.fill.incompatible.total";

    public enum CreationOutcome {
        ACCEPTED,
        REJECTED
    }

    public enum Stage {
        ANALYZE,
        PLAN,
        CHECK,
        APPLY,
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
        CAPACITY_WARNING_ACCEPT,
        UNKNOWN
    }

    public enum ObjectCategory {
        SMARTART,
        OLE,
        ANIMATION,
        CHART,
        TABLE,
        TEXT,
        IMAGE,
        TRANSITION,
        OTHER,
        UNKNOWN
    }

    private final ModeTelemetryRecorder recorder;

    public TemplateFillTelemetry(MeterRegistry registry) {
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
                Arrays.stream(TemplateFillErrorCode.values()).map(Enum::name).toList(),
                Arrays.stream(ObjectCategory.values()).map(Enum::name).toList());
    }

    public static TemplateFillTelemetry noop(MeterRegistry registry) {
        return new TemplateFillTelemetry(registry);
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

    public void recordError(TemplateFillErrorCode errorCode) {
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
