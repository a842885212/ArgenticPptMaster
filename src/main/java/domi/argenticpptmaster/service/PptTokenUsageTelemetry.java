package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.AgentScopeProperties.ModelGroup;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality Micrometer adapter for cross-mode chat token usage.
 * <p>
 * Tag values are drawn from closed sets; unknown provider/model map to {@code unknown}/{@code UNKNOWN}.
 * Job identifiers, tenants and free text MUST NOT be used as labels.
 * </p>
 */
@Component
public class PptTokenUsageTelemetry {

    public static final String METRIC_INPUT = "ppt.chat.tokens.input.total";
    public static final String METRIC_OUTPUT = "ppt.chat.tokens.output.total";
    public static final String METRIC_CALLS = "ppt.chat.tokens.calls.total";

    public static final String TAG_MODE = "mode";
    public static final String TAG_PROVIDER = "provider";
    public static final String TAG_MODEL = "model";

    public static final String UNKNOWN_MODEL = "UNKNOWN";
    public static final String UNKNOWN_PROVIDER = "unknown";

    private static final Set<String> PROVIDERS = Set.of("openai", "dashscope", "ollama", UNKNOWN_PROVIDER);

    private final Set<String> knownModes;
    private final Set<String> knownProviders;
    private final Set<String> knownModels;
    private final Map<String, Counter> inputCounters = new LinkedHashMap<>();
    private final Map<String, Counter> outputCounters = new LinkedHashMap<>();
    private final Map<String, Counter> callCounters = new LinkedHashMap<>();

    public PptTokenUsageTelemetry(MeterRegistry registry, AgentScopeProperties agentScopeProperties) {
        this.knownModes = Set.copyOf(
                Arrays.stream(PptWorkflowMode.values()).map(Enum::name).toList());
        this.knownProviders = Set.copyOf(PROVIDERS);
        this.knownModels = closedModels(agentScopeProperties);

        for (String mode : knownModes) {
            for (String provider : knownProviders) {
                for (String model : knownModels) {
                    String key = key(mode, provider, model);
                    inputCounters.put(
                            key,
                            Counter.builder(METRIC_INPUT)
                                    .tags(TAG_MODE, mode, TAG_PROVIDER, provider, TAG_MODEL, model)
                                    .register(registry));
                    outputCounters.put(
                            key,
                            Counter.builder(METRIC_OUTPUT)
                                    .tags(TAG_MODE, mode, TAG_PROVIDER, provider, TAG_MODEL, model)
                                    .register(registry));
                    callCounters.put(
                            key,
                            Counter.builder(METRIC_CALLS)
                                    .tags(TAG_MODE, mode, TAG_PROVIDER, provider, TAG_MODEL, model)
                                    .register(registry));
                }
            }
        }
    }

    /**
     * 按封闭标签累计一次已成功计入 job 的 chat usage。
     */
    public void record(
            PptWorkflowMode mode, String provider, String modelName, int inputTokens, int outputTokens) {
        if (mode == null) {
            return;
        }
        String modeTag = mode.name();
        if (!knownModes.contains(modeTag)) {
            return;
        }
        String providerTag = normalizeProvider(provider);
        String modelTag = normalizeModel(modelName);
        String key = key(modeTag, providerTag, modelTag);
        Counter input = inputCounters.get(key);
        Counter output = outputCounters.get(key);
        Counter calls = callCounters.get(key);
        if (input == null || output == null || calls == null) {
            return;
        }
        input.increment(Math.max(0, inputTokens));
        output.increment(Math.max(0, outputTokens));
        calls.increment();
    }

    /** Upper bound on distinct meter series emitted by this adapter. */
    public int maxLabelCardinality() {
        return 3 * knownModes.size() * knownProviders.size() * knownModels.size();
    }

    Set<String> knownModels() {
        return knownModels;
    }

    Set<String> knownProviders() {
        return knownProviders;
    }

    Set<String> knownModes() {
        return knownModes;
    }

    private String normalizeProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN_PROVIDER;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return knownProviders.contains(normalized) ? normalized : UNKNOWN_PROVIDER;
    }

    private String normalizeModel(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN_MODEL;
        }
        String trimmed = raw.trim();
        return knownModels.contains(trimmed) ? trimmed : UNKNOWN_MODEL;
    }

    private static String key(String mode, String provider, String model) {
        return mode + '\0' + provider + '\0' + model;
    }

    private static Set<String> closedModels(AgentScopeProperties properties) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        if (properties != null) {
            addModel(models, properties.effectivePrimary());
            for (ModelGroup group : properties.effectiveFallbacks()) {
                addModel(models, group);
            }
        }
        models.add(UNKNOWN_MODEL);
        return Set.copyOf(models);
    }

    private static void addModel(Set<String> models, ModelGroup group) {
        if (group == null || group.modelName() == null || group.modelName().isBlank()) {
            return;
        }
        models.add(group.modelName().trim());
    }
}
