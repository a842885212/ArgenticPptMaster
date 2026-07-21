package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.TemplateFillErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_STAGE = "stage";
    private static final String TAG_ERROR_CODE = "error_code";
    private static final String TAG_DECISION = "decision";
    private static final String TAG_OBJECT_CATEGORY = "object_category";
    private static final String UNKNOWN = "UNKNOWN";

    private final MeterRegistry registry;
    private final Map<Stage, Map<Outcome, Counter>> stageCounters = new EnumMap<>(Stage.class);
    private final Map<Stage, Timer> stageTimers = new EnumMap<>(Stage.class);
    private final Map<String, Counter> errorCounters = new java.util.HashMap<>();
    private final Map<Decision, Counter> decisionCounters = new EnumMap<>(Decision.class);
    private final Map<Outcome, Counter> recoveryCounters = new EnumMap<>(Outcome.class);
    private final Map<ObjectCategory, Counter> incompatibleCounters = new EnumMap<>(ObjectCategory.class);
    private Counter creationAccepted;
    private Counter creationRejected;

    public TemplateFillTelemetry(MeterRegistry registry) {
        this.registry = registry;
        for (Stage stage : Stage.values()) {
            stageCounters.put(stage, new EnumMap<>(Outcome.class));
            for (Outcome outcome : Outcome.values()) {
                stageCounters.get(stage).put(outcome, counter(METRIC_STAGE, TAG_STAGE, stage.name(), TAG_OUTCOME, outcome.name()));
            }
            stageTimers.put(stage, registry.timer(METRIC_STAGE_DURATION, TAG_STAGE, stage.name()));
        }
        for (TemplateFillErrorCode code : TemplateFillErrorCode.values()) {
            errorCounters.put(code.name(), counter(METRIC_ERROR, TAG_ERROR_CODE, code.name()));
        }
        errorCounters.put(UNKNOWN, counter(METRIC_ERROR, TAG_ERROR_CODE, UNKNOWN));
        for (Decision decision : Decision.values()) {
            decisionCounters.put(decision, counter(METRIC_DECISION, TAG_DECISION, decision.name()));
        }
        for (Outcome outcome : Outcome.values()) {
            recoveryCounters.put(outcome, counter(METRIC_RECOVERY, TAG_OUTCOME, outcome.name()));
        }
        for (ObjectCategory category : ObjectCategory.values()) {
            incompatibleCounters.put(category, counter(METRIC_INCOMPATIBLE, TAG_OBJECT_CATEGORY, category.name()));
        }
        creationAccepted = counter(METRIC_CREATION, TAG_OUTCOME, CreationOutcome.ACCEPTED.name());
        creationRejected = counter(METRIC_CREATION, TAG_OUTCOME, CreationOutcome.REJECTED.name());
    }

    public static TemplateFillTelemetry noop(MeterRegistry registry) {
        return new TemplateFillTelemetry(registry);
    }

    public void recordCreation(CreationOutcome outcome) {
        if (outcome == CreationOutcome.ACCEPTED) {
            creationAccepted.increment();
        } else {
            creationRejected.increment();
        }
    }

    public void recordStage(Stage stage, Outcome outcome, Duration duration) {
        Stage resolvedStage = normalizeStage(stage);
        Outcome resolvedOutcome = normalizeOutcome(outcome);
        stageCounters.get(resolvedStage).get(resolvedOutcome).increment();
        if (duration != null && !duration.isNegative()) {
            stageTimers.get(resolvedStage).record(duration.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    public void recordError(TemplateFillErrorCode errorCode) {
        String label = errorCode == null ? UNKNOWN : normalizeErrorCode(errorCode.name());
        errorCounters.get(label).increment();
    }

    public void recordError(String errorCode) {
        String label = normalizeErrorCode(errorCode);
        errorCounters.get(label).increment();
    }

    public void recordDecision(Decision decision) {
        decisionCounters.get(normalizeDecision(decision)).increment();
    }

    public void recordRecovery(boolean success) {
        recoveryCounters.get(success ? Outcome.SUCCESS : Outcome.FAILURE).increment();
    }

    public void recordIncompatible(ObjectCategory category) {
        incompatibleCounters.get(normalizeObjectCategory(category)).increment();
    }

    /** Upper bound on distinct meter series emitted by this adapter. */
    public int maxLabelCardinality() {
        return 2
                + Stage.values().length * Outcome.values().length
                + Stage.values().length
                + errorCounters.size()
                + Decision.values().length
                + Outcome.values().length
                + ObjectCategory.values().length;
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private static Stage normalizeStage(Stage stage) {
        if (stage == null) {
            return Stage.UNKNOWN;
        }
        try {
            return Stage.valueOf(stage.name());
        } catch (IllegalArgumentException ex) {
            return Stage.UNKNOWN;
        }
    }

    private static Outcome normalizeOutcome(Outcome outcome) {
        if (outcome == null) {
            return Outcome.UNKNOWN;
        }
        try {
            return Outcome.valueOf(outcome.name());
        } catch (IllegalArgumentException ex) {
            return Outcome.UNKNOWN;
        }
    }

    private static Decision normalizeDecision(Decision decision) {
        if (decision == null) {
            return Decision.UNKNOWN;
        }
        try {
            return Decision.valueOf(decision.name());
        } catch (IllegalArgumentException ex) {
            return Decision.UNKNOWN;
        }
    }

    private static ObjectCategory normalizeObjectCategory(ObjectCategory category) {
        if (category == null) {
            return ObjectCategory.UNKNOWN;
        }
        try {
            return ObjectCategory.valueOf(category.name());
        } catch (IllegalArgumentException ex) {
            return ObjectCategory.UNKNOWN;
        }
    }

    private static String normalizeErrorCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (TemplateFillErrorCode code : TemplateFillErrorCode.values()) {
            if (code.name().equals(normalized)) {
                return code.name();
            }
        }
        return UNKNOWN;
    }
}
