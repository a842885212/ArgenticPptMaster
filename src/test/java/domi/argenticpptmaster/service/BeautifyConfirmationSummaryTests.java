package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BeautifyConfirmationSummaryTests {

    @TempDir
    Path tempDir;

    @Test
    void buildsBoundedConfirmationContextWithoutAbsolutePaths() {
        Path project = tempDir.resolve("project");
        PptJob job = new PptJob(UUID.randomUUID(), "beautify", "ppt169", null,
                PptWorkflowMode.BEAUTIFY_PPTX, project);
        job.prepareProject(project);
        job.completeNode(PptJobNode.BEAUTIFY_ANALYZED, Map.of("sourcePageCount", 1));
        BeautifyPptxPlanStore.storeDraft(job, """
                {"version":1,"status":"draft","sourcePageCount":1,"hardConstraints":{"textVerbatim":true,
                "dataVerbatim":true,"pageMapping":"1:1"},"identityRecommendation":{"source":"theme",
                "candidates":[{"id":"theme","label":"Theme driven"},{"id":"observed"}],
                "paletteCandidateId":"theme","fontCandidateId":"theme","bodySizeCandidateId":"theme",
                "rationale":"theme-driven"},"ignored":[{"page":1,"type":"hidden","reason":"master text"}],
                "needsConfirmation":[],"validationStrictness":"strict"}
                """);

        Map<String, Object> summary = BeautifyConfirmationSummary.fromWorkspace(job, Map.of());

        assertThat(summary).containsEntry("type", "beautify_pptx_plan");
        assertThat(summary).containsEntry("sourcePageCount", 1);
        assertThat(summary).containsEntry("ignoredCount", 1);
        assertThat(summary.get("identityRecommendation").toString()).contains("theme");
        assertThat(summary.toString()).doesNotContain(project.toAbsolutePath().toString());
    }
}
