package domi.argenticpptmaster.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 模式无关的低基数 Micrometer 记录器。
 * <p>
 * 指标名与允许标签集合由外观提供；{@code incompatibleMetric} 为空时不注册该系列。
 * </p>
 */
public final class ModeTelemetryRecorder {

    public static final String UNKNOWN = "UNKNOWN";

    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_STAGE = "stage";
    private static final String TAG_ERROR_CODE = "error_code";
    private static final String TAG_DECISION = "decision";
    private static final String TAG_OBJECT_CATEGORY = "object_category";

    public record MetricNames(
            String creation,
            String stage,
            String stageDuration,
            String error,
            String decision,
            String recovery,
            String incompatible) {
    }

    private final MeterRegistry registry;
    private final MetricNames metrics;
    private final Set<String> knownStages;
    private final Set<String> knownOutcomes;
    private final Set<String> knownDecisions;
    private final Set<String> knownErrorCodes;
    private final Set<String> knownObjectCategories;
    private final Map<String, Map<String, Counter>> stageCounters = new HashMap<>();
    private final Map<String, Timer> stageTimers = new HashMap<>();
    private final Map<String, Counter> errorCounters = new HashMap<>();
    private final Map<String, Counter> decisionCounters = new HashMap<>();
    private final Map<String, Counter> recoveryCounters = new HashMap<>();
    private final Map<String, Counter> incompatibleCounters = new HashMap<>();
    private final Counter creationAccepted;
    private final Counter creationRejected;

    public ModeTelemetryRecorder(
            MeterRegistry registry,
            MetricNames metrics,
            Collection<String> stages,
            Collection<String> outcomes,
            Collection<String> decisions,
            Collection<String> errorCodes,
            Collection<String> objectCategories) {
        this.registry = registry;
        this.metrics = metrics;
        this.knownStages = copyClosed(stages);
        this.knownOutcomes = copyClosed(outcomes);
        this.knownDecisions = copyClosed(decisions);
        this.knownErrorCodes = copyClosed(errorCodes);
        this.knownObjectCategories = objectCategories == null || objectCategories.isEmpty()
                ? Set.of()
                : copyClosed(objectCategories);

        for (String stage : knownStages) {
            Map<String, Counter> byOutcome = new HashMap<>();
            for (String outcome : knownOutcomes) {
                byOutcome.put(outcome, counter(metrics.stage(), TAG_STAGE, stage, TAG_OUTCOME, outcome));
            }
            stageCounters.put(stage, byOutcome);
            stageTimers.put(stage, registry.timer(metrics.stageDuration(), TAG_STAGE, stage));
        }
        for (String code : knownErrorCodes) {
            errorCounters.put(code, counter(metrics.error(), TAG_ERROR_CODE, code));
        }
        if (!errorCounters.containsKey(UNKNOWN)) {
            errorCounters.put(UNKNOWN, counter(metrics.error(), TAG_ERROR_CODE, UNKNOWN));
        }
        for (String decision : knownDecisions) {
            decisionCounters.put(decision, counter(metrics.decision(), TAG_DECISION, decision));
        }
        for (String outcome : knownOutcomes) {
            recoveryCounters.put(outcome, counter(metrics.recovery(), TAG_OUTCOME, outcome));
        }
        if (metrics.incompatible() != null && !metrics.incompatible().isBlank()) {
            for (String category : knownObjectCategories) {
                incompatibleCounters.put(
                        category, counter(metrics.incompatible(), TAG_OBJECT_CATEGORY, category));
            }
        }
        creationAccepted = counter(metrics.creation(), TAG_OUTCOME, "ACCEPTED");
        creationRejected = counter(metrics.creation(), TAG_OUTCOME, "REJECTED");
    }

    public void recordCreationAccepted() {
        creationAccepted.increment();
    }

    public void recordCreationRejected() {
        creationRejected.increment();
    }

    public void recordStage(String stage, String outcome, Duration duration) {
        String resolvedStage = normalize(stage, knownStages);
        String resolvedOutcome = normalize(outcome, knownOutcomes);
        stageCounters.get(resolvedStage).get(resolvedOutcome).increment();
        if (duration != null && !duration.isNegative()) {
            stageTimers.get(resolvedStage).record(duration.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    public void recordError(String errorCode) {
        errorCounters.get(normalize(errorCode, knownErrorCodes)).increment();
    }

    public void recordDecision(String decision) {
        decisionCounters.get(normalize(decision, knownDecisions)).increment();
    }

    public void recordRecovery(boolean success) {
        recoveryCounters.get(success ? "SUCCESS" : "FAILURE").increment();
    }

    public void recordIncompatible(String category) {
        if (incompatibleCounters.isEmpty()) {
            throw new IllegalStateException("incompatible metrics are not enabled for this mode");
        }
        incompatibleCounters.get(normalize(category, knownObjectCategories)).increment();
    }

    public int maxLabelCardinality() {
        int cardinality = 2
                + knownStages.size() * knownOutcomes.size()
                + knownStages.size()
                + errorCounters.size()
                + knownDecisions.size()
                + knownOutcomes.size();
        if (!incompatibleCounters.isEmpty()) {
            cardinality += knownObjectCategories.size();
        }
        return cardinality;
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private static Set<String> copyClosed(Collection<String> values) {
        Set<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                copy.add(value);
            }
        }
        copy.add(UNKNOWN);
        return Set.copyOf(copy);
    }

    private static String normalize(String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : UNKNOWN;
    }
}
