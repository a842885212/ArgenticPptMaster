package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PptTemplateFillPlanStoreTests {

    @TempDir
    Path tempDir;

    @Test
    void storesOnlyConfirmedPlanAtomicallyInsideJobWorkspace() throws Exception {
        PptJob job = job();
        PptTemplateFillPlanStore store = store(1024);

        Path stored = store.storeConfirmedPlan(job, "{\"status\":\"confirmed\",\"slides\":[]}");

        assertThat(stored).isEqualTo(tempDir.resolve("analysis/fill_plan.json"));
        assertThat(Files.readString(stored)).contains("\"status\":\"confirmed\"");
        assertThat(Files.list(tempDir.resolve("analysis")))
                .noneMatch(path -> path.getFileName().toString().contains("tmp"));
    }

    @Test
    void rejectsDraftMalformedAndOversizedPlans() {
        PptTemplateFillPlanStore store = store(32);
        PptJob job = job();

        assertThatThrownBy(() -> store.storeConfirmedPlan(job, "{\"status\":\"draft\"}"))
                .isInstanceOf(PptJobStateException.class).hasMessageContaining("confirmed");
        assertThatThrownBy(() -> store.storeConfirmedPlan(job, "not-json"))
                .isInstanceOf(PptJobStateException.class).hasMessageContaining("JSON");
        assertThatThrownBy(() -> store.storeConfirmedPlan(job, "{\"status\":\"confirmed\",\"padding\":\"012345678901234567890\"}"))
                .isInstanceOf(PptJobStateException.class).hasMessageContaining("size");
    }

    private PptTemplateFillPlanStore store(long maxBytes) {
        return new PptTemplateFillPlanStore(new PptMasterProperties(
                tempDir, tempDir, "python3", Duration.ofSeconds(1), "token", maxBytes, 0, 0, 0, 0, null));
    }

    private PptJob job() {
        return new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, tempDir);
    }
}
