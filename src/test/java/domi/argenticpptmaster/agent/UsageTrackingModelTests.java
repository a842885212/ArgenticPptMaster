package domi.argenticpptmaster.agent;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptTokenUsage;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.service.PptTokenUsageLedgerStore;
import domi.argenticpptmaster.service.PptTokenUsageTelemetry;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class UsageTrackingModelTests {

    @TempDir
    Path tempDir;

    @Test
    void recordsLastUsageOnceOnSuccessfulStream() throws Exception {
        Path workspace = tempDir.resolve("job-1");
        Files.createDirectories(workspace);
        PptJob job = newJob(workspace);
        job.assignOwnership("client-a", "client-a");
        PptTokenUsageLedgerStore ledger = new PptTokenUsageLedgerStore();

        Model delegate = new StubModel(Flux.just(
                response(null),
                response(new ChatUsage(10, 4, 0.1)),
                response(new ChatUsage(12, 5, 0.2))));
        UsageTrackingModel tracking = new UsageTrackingModel(delegate, "kimi-for-coding", "openai", job, ledger);

        tracking.stream(List.of(), List.of(), null).blockLast();

        assertThat(job.tokenUsage().inputTokens()).isEqualTo(12L);
        assertThat(job.tokenUsage().outputTokens()).isEqualTo(5L);
        assertThat(job.tokenUsage().byModel()).containsOnlyKeys("kimi-for-coding");
        assertThat(ledger.summarize(workspace).totalTokens()).isEqualTo(17L);
    }

    @Test
    void skipsWhenUsageMissing() {
        Path workspace = tempDir.resolve("job-2");
        PptJob job = newJob(workspace);
        UsageTrackingModel tracking = new UsageTrackingModel(
                new StubModel(Flux.just(response(null), response(null))),
                "kimi-for-coding",
                "openai",
                job,
                new PptTokenUsageLedgerStore());

        tracking.stream(List.of(), List.of(), null).blockLast();

        assertThat(job.tokenUsage()).isEqualTo(PptTokenUsage.empty());
    }

    @Test
    void doesNotRecordWhenStreamErrors() {
        Path workspace = tempDir.resolve("job-3");
        PptJob job = newJob(workspace);
        UsageTrackingModel tracking = new UsageTrackingModel(
                new StubModel(Flux.concat(
                        Flux.just(response(new ChatUsage(9, 1, 0.1))),
                        Flux.error(new RuntimeException("boom")))),
                "kimi-for-coding",
                "openai",
                job,
                new PptTokenUsageLedgerStore());

        try {
            tracking.stream(List.of(), List.of(), null).blockLast();
        } catch (RuntimeException ignored) {
            // expected
        }

        assertThat(job.tokenUsage()).isEqualTo(PptTokenUsage.empty());
    }

    @Test
    void fallbackSuccessAttributesUsageToFallbackModelOnly() {
        Path workspace = tempDir.resolve("job-fallback");
        PptJob job = newJob(workspace);
        job.assignOwnership("client-f", "client-f");
        PptTokenUsageLedgerStore ledger = new PptTokenUsageLedgerStore();

        Model primary = new UsageTrackingModel(
                new StubModel(Flux.error(new java.net.SocketException("connection reset"))),
                "primary-model",
                "openai",
                job,
                ledger);
        Model fallback = new UsageTrackingModel(
                new StubModel(Flux.just(response(new ChatUsage(7, 3, 0.1)))),
                "fallback-model",
                "openai",
                job,
                ledger);

        new FallbackModel(List.of(primary, fallback)).stream(List.of(), List.of(), null).blockLast();

        assertThat(job.tokenUsage().byModel()).containsOnlyKeys("fallback-model");
        assertThat(job.tokenUsage().inputTokens()).isEqualTo(7L);
        assertThat(job.tokenUsage().outputTokens()).isEqualTo(3L);
        assertThat(ledger.summarize(workspace).byModel()).containsOnlyKeys("fallback-model");
    }

    @Test
    void recordsMicrometerAfterJobSuccessEvenWhenLedgerAbsent() {
        Path workspace = tempDir.resolve("job-metrics");
        PptJob job = newJob(workspace);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PptTokenUsageTelemetry telemetry = new PptTokenUsageTelemetry(
                registry,
                new AgentScopeProperties(
                        "openai",
                        "kimi-for-coding",
                        null,
                        null,
                        8,
                        tempDir.resolve("sessions"),
                        "ppt-master-service",
                        null,
                        null,
                        null));

        new UsageTrackingModel(
                        new StubModel(Flux.just(response(new ChatUsage(12, 5, 0.2)))),
                        "kimi-for-coding",
                        "openai",
                        job,
                        null,
                        telemetry)
                .stream(List.of(), List.of(), null)
                .blockLast();

        assertThat(job.tokenUsage().totalTokens()).isEqualTo(17L);
        assertThat(registry.find(PptTokenUsageTelemetry.METRIC_INPUT)
                        .tag("mode", "BASIC")
                        .tag("provider", "openai")
                        .tag("model", "kimi-for-coding")
                        .counter()
                        .count())
                .isEqualTo(12.0);
        assertThat(registry.find(PptTokenUsageTelemetry.METRIC_CALLS)
                        .tag("mode", "BASIC")
                        .tag("provider", "openai")
                        .tag("model", "kimi-for-coding")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void telemetryFailureDoesNotBlockJobOrLedger() throws Exception {
        Path workspace = tempDir.resolve("job-metrics-fail");
        Files.createDirectories(workspace);
        PptJob job = newJob(workspace);
        job.assignOwnership("client-m", "client-m");
        PptTokenUsageLedgerStore ledger = new PptTokenUsageLedgerStore();
        PptTokenUsageTelemetry broken = new PptTokenUsageTelemetry(
                new SimpleMeterRegistry(),
                new AgentScopeProperties(
                        "openai",
                        "kimi-for-coding",
                        null,
                        null,
                        8,
                        tempDir.resolve("sessions"),
                        "ppt-master-service",
                        null,
                        null,
                        null)) {
            @Override
            public void record(
                    PptWorkflowMode mode,
                    String provider,
                    String modelName,
                    int inputTokens,
                    int outputTokens) {
                throw new IllegalStateException("meter boom");
            }
        };

        new UsageTrackingModel(
                        new StubModel(Flux.just(response(new ChatUsage(8, 2, 0.1)))),
                        "kimi-for-coding",
                        "openai",
                        job,
                        ledger,
                        broken)
                .stream(List.of(), List.of(), null)
                .blockLast();

        assertThat(job.tokenUsage().inputTokens()).isEqualTo(8L);
        assertThat(ledger.summarize(workspace).totalTokens()).isEqualTo(10L);
    }

    @Test
    void keepsJobsIsolatedAcrossModels() {
        Path wsA = tempDir.resolve("job-a");
        Path wsB = tempDir.resolve("job-b");
        PptJob jobA = newJob(wsA);
        PptJob jobB = newJob(wsB);
        jobA.assignOwnership("a", "a");
        jobB.assignOwnership("b", "b");
        PptTokenUsageLedgerStore ledger = new PptTokenUsageLedgerStore();

        new UsageTrackingModel(
                        new StubModel(Flux.just(response(new ChatUsage(1, 1, 0)))),
                        "model-a",
                        "openai",
                        jobA,
                        ledger)
                .stream(List.of(), List.of(), null)
                .blockLast();
        new UsageTrackingModel(
                        new StubModel(Flux.just(response(new ChatUsage(2, 2, 0)))),
                        "model-b",
                        "openai",
                        jobB,
                        ledger)
                .stream(List.of(), List.of(), null)
                .blockLast();

        assertThat(jobA.tokenUsage().totalTokens()).isEqualTo(2L);
        assertThat(jobB.tokenUsage().totalTokens()).isEqualTo(4L);
        assertThat(jobA.tokenUsage().byModel()).containsOnlyKeys("model-a");
        assertThat(jobB.tokenUsage().byModel()).containsOnlyKeys("model-b");
    }

    private static PptJob newJob(Path workspace) {
        return new PptJob(
                UUID.randomUUID(), "demo", "ppt169", "make a deck", PptWorkflowMode.BASIC, workspace);
    }

    private static ChatResponse response(ChatUsage usage) {
        return new ChatResponse("id", List.of(), usage, null, "stop");
    }

    private static final class StubModel implements Model {
        private final Flux<ChatResponse> responses;
        private final AtomicInteger calls = new AtomicInteger();

        private StubModel(Flux<ChatResponse> responses) {
            this.responses = responses;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            return responses;
        }

        @Override
        public String getModelName() {
            return "stub";
        }
    }
}
