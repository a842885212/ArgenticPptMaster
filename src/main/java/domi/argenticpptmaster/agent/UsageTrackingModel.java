package domi.argenticpptmaster.agent;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.service.PptTokenUsageLedgerStore;
import domi.argenticpptmaster.service.PptTokenUsageTelemetry;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 包装 {@link Model}：在流成功完成时把最后一次非空 {@link ChatUsage} 计入绑定的 {@link PptJob}
 * 与 workspace ledger（按配置的 model 名归因，避免 fallback 记错模型），并打低基数 Micrometer 点。
 */
public final class UsageTrackingModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(UsageTrackingModel.class);

    private final Model delegate;
    private final String modelName;
    private final String provider;
    private final PptJob job;
    private final PptTokenUsageLedgerStore ledgerStore;
    private final PptTokenUsageTelemetry telemetry;

    public UsageTrackingModel(
            Model delegate,
            String modelName,
            String provider,
            PptJob job,
            PptTokenUsageLedgerStore ledgerStore) {
        this(delegate, modelName, provider, job, ledgerStore, null);
    }

    public UsageTrackingModel(
            Model delegate,
            String modelName,
            String provider,
            PptJob job,
            PptTokenUsageLedgerStore ledgerStore,
            PptTokenUsageTelemetry telemetry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName is required");
        }
        this.modelName = modelName.trim();
        this.provider = provider == null || provider.isBlank() ? null : provider.trim();
        this.job = Objects.requireNonNull(job, "job");
        this.ledgerStore = ledgerStore;
        this.telemetry = telemetry;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        AtomicReference<ChatUsage> lastUsage = new AtomicReference<>();
        return delegate.stream(messages, tools, options)
                .doOnNext(response -> {
                    if (response != null && response.getUsage() != null) {
                        lastUsage.set(response.getUsage());
                    }
                })
                .doOnComplete(() -> recordOnce(lastUsage.get()));
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    private void recordOnce(ChatUsage usage) {
        if (usage == null) {
            return;
        }
        boolean recorded = job.recordTokenUsage(modelName, usage.getInputTokens(), usage.getOutputTokens());
        if (!recorded) {
            return;
        }
        recordTelemetry(usage);
        if (ledgerStore == null) {
            return;
        }
        Integer cached = usage.getCachedTokens() > 0 ? usage.getCachedTokens() : null;
        Integer reasoning = usage.getReasoningTokens() > 0 ? usage.getReasoningTokens() : null;
        ledgerStore.append(
                job,
                modelName,
                usage.getInputTokens(),
                usage.getOutputTokens(),
                cached,
                reasoning,
                provider);
    }

    private void recordTelemetry(ChatUsage usage) {
        if (telemetry == null) {
            return;
        }
        try {
            telemetry.record(
                    job.workflowMode(),
                    provider,
                    modelName,
                    usage.getInputTokens(),
                    usage.getOutputTokens());
        } catch (RuntimeException ex) {
            log.warn(
                    "ppt_token_usage_telemetry_failed: jobId={}, model={}, message={}",
                    job.id(),
                    modelName,
                    ex.getMessage());
        }
    }

    Model delegate() {
        return delegate;
    }

    String trackedModelName() {
        return modelName;
    }

    PptJob boundJob() {
        return job;
    }
}
