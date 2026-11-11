package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptModelTokenUsage;
import domi.argenticpptmaster.domain.PptTokenUsage;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PptTokenUsageLedgerStoreTests {

    @TempDir
    Path tempDir;

    @Test
    void appendsJsonlWithoutSecretsAndSummarizesByModel() throws Exception {
        PptTokenUsageLedgerStore store = new PptTokenUsageLedgerStore();
        Path workspace = tempDir.resolve("job-a");
        Files.createDirectories(workspace);
        PptJob job = new PptJob(
                UUID.randomUUID(), "demo", "ppt169", "make a deck", PptWorkflowMode.BASIC, workspace);
        job.assignOwnership("client-alpha", "client-alpha");

        store.append(job, "kimi-for-coding", 100, 40, 12, null, "openai");
        store.append(job, "gemini-3.8-flash", 50, 20, null, 3, "openai");
        store.append(job, "kimi-for-coding", 10, 5, null, null, "openai");

        Path ledger = PptTokenUsageLedgerStore.ledgerPath(workspace);
        assertThat(ledger).exists();
        List<String> lines = Files.readAllLines(ledger, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(3);
        String joined = String.join("\n", lines);
        assertThat(joined).doesNotContain("sk-");
        assertThat(joined).contains("ownershipDigest");
        assertThat(joined).doesNotContain("client-alpha");
        assertThat(joined).contains("\"model\":\"kimi-for-coding\"");
        assertThat(joined).contains("\"cachedTokens\":12");
        assertThat(joined).contains("\"reasoningTokens\":3");

        PptTokenUsage summarized = store.summarize(workspace, job.id());
        assertThat(summarized.inputTokens()).isEqualTo(160L);
        assertThat(summarized.outputTokens()).isEqualTo(65L);
        assertThat(summarized.totalTokens()).isEqualTo(225L);
        assertThat(summarized.byModel().get("kimi-for-coding"))
                .isEqualTo(new PptModelTokenUsage(110L, 45L, 155L, 2L));
        assertThat(summarized.byModel().get("gemini-3.8-flash"))
                .isEqualTo(new PptModelTokenUsage(50L, 20L, 70L, 1L));
    }

    @Test
    void appendFailureDoesNotThrow() throws Exception {
        PptTokenUsageLedgerStore store = new PptTokenUsageLedgerStore();
        Path workspace = tempDir.resolve("job-b");
        Files.createDirectories(workspace);
        // 把 usage 目录占成普通文件，迫使 createDirectories / write 失败
        Files.writeString(workspace.resolve("usage"), "not-a-directory", StandardCharsets.UTF_8);
        PptJob job = new PptJob(
                UUID.randomUUID(), "demo", "ppt169", "make a deck", PptWorkflowMode.BASIC, workspace);
        job.assignOwnership("client-beta", "client-beta");

        store.append(job, "kimi-for-coding", 1, 1, null, null, "openai");

        assertThat(store.summarize(workspace)).isEqualTo(PptTokenUsage.empty());
    }
}
