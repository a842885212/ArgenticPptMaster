package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.FillPlanStatus;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.domain.TemplateFillPlanMetadata;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.exception.PptTemplateFillConflictException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PptTemplateFillPlanStoreTests {

    @TempDir
    Path tempDir;

    private Path slideLibrary;
    private String validDraft;

    @BeforeEach
    void setUp() throws Exception {
        slideLibrary = tempDir.resolve("template.slide_library.json");
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"), slideLibrary);
        validDraft = Files.readString(Path.of(getClass().getResource("/template-fill/fill-plan-draft-valid.json").toURI()));
    }

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

    @Test
    void storesDraftAtomicallyWithForcedStatusVersionAndDigest() throws Exception {
        PptJob job = job();
        PptTemplateFillPlanStore store = store(64_000);

        TemplateFillPlanMetadata first = store.storeDraftPlan(job, validDraft, slideLibrary);
        TemplateFillPlanMetadata second = store.storeDraftPlan(
                job, validDraft.replace("\"version\": 1", "\"version\": 99"), slideLibrary);

        assertThat(first.status()).isEqualTo("draft");
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.digest()).isNotBlank();
        assertThat(second.version()).isEqualTo(2);
        assertThat(Files.readString(tempDir.resolve("analysis/fill_plan.json"))).contains("\"status\":\"draft\"");
        assertThat(job.fillPlanStatus()).isEqualTo(FillPlanStatus.DRAFT);
        assertThat(job.planSlideCount()).isEqualTo(2);
        assertThat(store.readMetadata(job)).contains(second);
        assertThat(Files.list(tempDir.resolve("analysis")))
                .noneMatch(path -> path.getFileName().toString().endsWith(".tmp"));
    }

    @Test
    void rejectsOversizedAndInvalidDraftReferences() throws Exception {
        PptTemplateFillPlanStore tiny = store(64);
        PptTemplateFillPlanStore normal = store(64_000);
        PptJob job = job();
        String invalid = Files.readString(Path.of(
                getClass().getResource("/template-fill/fill-plan-draft-invalid-refs.json").toURI()));

        assertThatThrownBy(() -> tiny.storeDraftPlan(job, validDraft, slideLibrary))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> normal.storeDraftPlan(job, invalid, slideLibrary))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("unknown template slide");
        assertThat(Files.exists(tempDir.resolve("analysis/fill_plan.json"))).isFalse();
    }

    @Test
    void compareAndConfirmSucceedsOnlyWithMatchingVersionDigestAndDraftStatus() {
        PptJob job = job();
        PptTemplateFillPlanStore store = store(64_000);
        TemplateFillPlanMetadata draft = store.storeDraftPlan(job, validDraft, slideLibrary);

        TemplateFillPlanMetadata confirmed = store.confirmCurrentDraft(
                job, "confirm-1", draft.version(), draft.digest());

        assertThat(confirmed.status()).isEqualTo("confirmed");
        assertThat(confirmed.confirmationId()).isEqualTo("confirm-1");
        assertThat(confirmed.approvedAt()).isNotNull();
        assertThat(job.fillPlanStatus()).isEqualTo(FillPlanStatus.CONFIRMED);
        assertThat(store.findConfirmedPlan(job)).isPresent();
        assertThat(store.hasApprovedRecord(job)).isTrue();
    }

    @Test
    void compareAndConfirmRejectsStaleVersionTamperedDigestAndRepeatApprove() throws Exception {
        PptJob job = job();
        PptTemplateFillPlanStore store = store(64_000);
        TemplateFillPlanMetadata draft = store.storeDraftPlan(job, validDraft, slideLibrary);

        assertThatThrownBy(() -> store.confirmCurrentDraft(job, "c1", draft.version() + 1, draft.digest()))
                .isInstanceOf(PptTemplateFillConflictException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> store.confirmCurrentDraft(job, "c1", draft.version(), "deadbeef"))
                .isInstanceOf(PptTemplateFillConflictException.class)
                .hasMessageContaining("digest");

        Files.writeString(tempDir.resolve("analysis/fill_plan.json"),
                Files.readString(tempDir.resolve("analysis/fill_plan.json")).replace("封面页", "篡改"));
        assertThatThrownBy(() -> store.confirmCurrentDraft(job, "c1", draft.version(), draft.digest()))
                .isInstanceOf(PptTemplateFillConflictException.class)
                .hasMessageContaining("digest");

        TemplateFillPlanMetadata fresh = store.storeDraftPlan(job, validDraft, slideLibrary);
        store.confirmCurrentDraft(job, "c-ok", fresh.version(), fresh.digest());
        assertThatThrownBy(() -> store.confirmCurrentDraft(job, "c-again", fresh.version(), fresh.digest()))
                .isInstanceOf(PptTemplateFillConflictException.class)
                .hasMessageContaining("awaiting confirmation");
    }

    @Test
    void selfDeclaredConfirmedCannotEstablishDraftApprovalFact() {
        PptJob job = job();
        PptTemplateFillPlanStore store = store(64_000);

        assertThatThrownBy(() -> store.storeDraftPlan(
                job, validDraft.replace("\"status\": \"draft\"", "\"status\": \"confirmed\""), slideLibrary))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("draft");
        assertThat(store.hasApprovedRecord(job)).isFalse();
    }

    private PptTemplateFillPlanStore store(long maxBytes) {
        return new PptTemplateFillPlanStore(new PptMasterProperties(
                tempDir, tempDir, "python3", Duration.ofSeconds(1), "token", maxBytes, 0, 0, 0, 0, null, null, null, null, null),
                new TemplateFillPlanValidator());
    }

    private PptJob job() {
        return new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, tempDir);
    }
}
