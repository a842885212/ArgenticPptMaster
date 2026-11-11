package domi.argenticpptmaster.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PptTokenUsageTests {

    @Test
    void newJobExposesEmptyTokenUsage() {
        PptJob job = newJob();

        PptTokenUsage usage = job.tokenUsage();

        assertThat(usage.inputTokens()).isZero();
        assertThat(usage.outputTokens()).isZero();
        assertThat(usage.totalTokens()).isZero();
        assertThat(usage.byModel()).isEmpty();
    }

    @Test
    void accumulatesTotalsAndByModel() {
        PptJob job = newJob();

        assertThat(job.recordTokenUsage("kimi-for-coding", 100, 40)).isTrue();
        assertThat(job.recordTokenUsage("gemini-3.8-flash", 50, 20)).isTrue();
        assertThat(job.recordTokenUsage("kimi-for-coding", 10, 5)).isTrue();

        PptTokenUsage usage = job.tokenUsage();
        assertThat(usage.inputTokens()).isEqualTo(160L);
        assertThat(usage.outputTokens()).isEqualTo(65L);
        assertThat(usage.totalTokens()).isEqualTo(225L);
        assertThat(usage.byModel()).containsOnlyKeys("kimi-for-coding", "gemini-3.8-flash");
        assertThat(usage.byModel().get("kimi-for-coding"))
                .isEqualTo(new PptModelTokenUsage(110L, 45L, 155L, 2L));
        assertThat(usage.byModel().get("gemini-3.8-flash"))
                .isEqualTo(new PptModelTokenUsage(50L, 20L, 70L, 1L));

        long byModelInput = usage.byModel().values().stream().mapToLong(PptModelTokenUsage::inputTokens).sum();
        long byModelOutput = usage.byModel().values().stream().mapToLong(PptModelTokenUsage::outputTokens).sum();
        assertThat(byModelInput).isEqualTo(usage.inputTokens());
        assertThat(byModelOutput).isEqualTo(usage.outputTokens());
    }

    @Test
    void blankModelDoesNotCountAsUsage() {
        PptJob job = newJob();

        assertThat(job.recordTokenUsage(" ", 10, 5)).isFalse();
        assertThat(job.recordTokenUsage(null, 10, 5)).isFalse();

        assertThat(job.tokenUsage()).isEqualTo(PptTokenUsage.empty());
    }

    @Test
    void skippingMissingUsageLeavesTotalsUnchanged() {
        PptJob job = newJob();
        job.recordTokenUsage("kimi-for-coding", 12, 3);
        PptTokenUsage before = job.tokenUsage();

        // 提供方未返回 usage 时调用方不得伪造计入——此处验证未调用则汇总不变
        assertThat(job.tokenUsage()).isEqualTo(before);
    }

    private static PptJob newJob() {
        return new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                PptWorkflowMode.BASIC,
                Path.of("var/ppt-master/jobs/demo-token-usage"));
    }
}
