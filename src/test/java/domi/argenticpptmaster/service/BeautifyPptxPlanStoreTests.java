package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BeautifyPptxPlanStoreTests {

    @TempDir
    Path tempDir;

    @Test
    void persistsCanonicalDraftAndConfirmsMatchingDigest() {
        PptJob job = job();

        BeautifyPptxPlanStore.PlanMetadata draft = BeautifyPptxPlanStore.storeDraft(job, draftJson());

        assertThat(BeautifyPptxPlanStore.readDraft(job)).contains(draft);
        BeautifyPptxPlanStore.confirmDraft(job, draft.version(), draft.digest());
        assertThat(BeautifyPptxPlanStore.read(job)).contains(new BeautifyPptxPlanStore.PlanMetadata(
                draft.version(), draft.digest(), "confirmed"));
    }

    @Test
    void rejectsPlanThatWeakensHardConstraintsOrUsesForgedDigest() {
        PptJob job = job();
        assertThatThrownBy(() -> BeautifyPptxPlanStore.storeDraft(job, draftJson().replace("true", "false")))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("preserve text");

        BeautifyPptxPlanStore.PlanMetadata draft = BeautifyPptxPlanStore.storeDraft(job, draftJson());
        assertThatThrownBy(() -> BeautifyPptxPlanStore.confirmDraft(job, draft.version(), "forged"))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("digest mismatch");
    }

    private PptJob job() {
        Path project = tempDir.resolve("project");
        PptJob job = new PptJob(UUID.randomUUID(), "beautify", "ppt169", null,
                PptWorkflowMode.BEAUTIFY_PPTX, project);
        job.prepareProject(project);
        return job;
    }

    private String draftJson() {
        return """
                {"version":1,"status":"draft","hardConstraints":{"textVerbatim":true,
                "dataVerbatim":true,"pageMapping":"1:1"},"slides":[]}
                """;
    }
}
