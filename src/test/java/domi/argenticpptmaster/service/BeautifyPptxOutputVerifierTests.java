package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.domain.BeautifyPptxErrorCode;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.engine.python.PythonPptxMarkdownReadback;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BeautifyPptxOutputVerifierTests {

    @TempDir
    Path tempDir;

    @Test
    void passesMatchingReadbackAndCompletesValidationNode() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("analysis"));
        Files.writeString(project.resolve("analysis/beautify_inventory.json"),
                "{\"schema\":\"beautify_inventory.v1\",\"slide_count\":1,\"slides\":[{"
                        + "\"slide_index\":1,\"text_blocks\":[{\"text\":\"Hello\"}],"
                        + "\"images\":[],\"tables\":[],\"charts\":[]}]}");
        Path export = writePptx(project.resolve("exports/out.pptx"), 1);
        PptJob job = job(project);
        job.completeNode(PptJobNode.BEAUTIFY_ANALYZED, Map.of("sourcePageCount", 1));
        BeautifyPptxPlanStore.storeDraft(job, draftJson());
        BeautifyPptxPlanStore.PlanMetadata draft = BeautifyPptxPlanStore.readDraft(job).orElseThrow();
        BeautifyPptxPlanStore.confirmDraft(job, draft.version(), draft.digest());

        PptMasterCommandExecutor commands = mock(PptMasterCommandExecutor.class);
        when(commands.runPythonScript(eq("skills/ppt-master/scripts/ppt_to_md.py"), anyList(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    Path output = Path.of((String) ((java.util.List<?>) invocation.getArgument(1)).get(2));
                    Files.createDirectories(output.getParent());
                    Files.writeString(output, "## Slide 1\n\nHello\n");
                    return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
                });

        BeautifyPptxOutputVerifier.ReadbackResult result = new BeautifyPptxOutputVerifier(
                new PythonPptxMarkdownReadback(commands, PythonPptxMarkdownReadback.BEAUTIFY_SCRIPT),
                mock(PptWorkflowEvents.class))
                .verify(job, export, project, Duration.ofSeconds(5));

        assertThat(result.passed()).isTrue();
        assertThat(job.nodeExecution(PptJobNode.BEAUTIFY_OUTPUT_VALIDATED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(Files.readString(project.resolve("validation/beautify-readback.json")))
                .contains("\"status\":\"PASSED\"")
                .doesNotContain(tempDir.toString());
    }

    @Test
    void failsWhenFrozenTextMissing() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("analysis"));
        Files.writeString(project.resolve("analysis/beautify_inventory.json"),
                "{\"schema\":\"beautify_inventory.v1\",\"slide_count\":1,\"slides\":[{"
                        + "\"slide_index\":1,\"text_blocks\":[{\"text\":\"Hello\"}],"
                        + "\"images\":[],\"tables\":[],\"charts\":[]}]}");
        Path export = writePptx(project.resolve("exports/out.pptx"), 1);
        PptJob job = job(project);
        job.completeNode(PptJobNode.BEAUTIFY_ANALYZED, Map.of("sourcePageCount", 1));

        PptMasterCommandExecutor commands = mock(PptMasterCommandExecutor.class);
        when(commands.runPythonScript(any(), anyList(), any(Duration.class))).thenAnswer(invocation -> {
            Path output = Path.of((String) ((java.util.List<?>) invocation.getArgument(1)).get(2));
            Files.createDirectories(output.getParent());
            Files.writeString(output, "## Slide 1\n\nDifferent\n");
            return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
        });

        BeautifyPptxOutputVerifier.ReadbackResult result = new BeautifyPptxOutputVerifier(
                new PythonPptxMarkdownReadback(commands, PythonPptxMarkdownReadback.BEAUTIFY_SCRIPT),
                mock(PptWorkflowEvents.class))
                .verify(job, export, project, Duration.ofSeconds(5));

        assertThat(result.passed()).isFalse();
        assertThat(job.readbackValidationStatus()).isEqualTo("FAILED");
        assertThat(job.nodeExecution(PptJobNode.BEAUTIFY_OUTPUT_VALIDATED).status())
                .isNotEqualTo(PptJobNodeStatus.COMPLETED);
    }

    @Test
    void failsClosedWhenReadbackCommandFails() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("analysis"));
        Files.writeString(project.resolve("analysis/beautify_inventory.json"),
                "{\"schema\":\"beautify_inventory.v1\",\"slide_count\":1,\"slides\":[{"
                        + "\"slide_index\":1,\"text_blocks\":[{\"text\":\"Hello\"}],"
                        + "\"images\":[],\"tables\":[],\"charts\":[]}]}");
        Path export = writePptx(project.resolve("exports/out.pptx"), 1);
        PptJob job = job(project);

        PptMasterCommandExecutor commands = mock(PptMasterCommandExecutor.class);
        when(commands.runPythonScript(any(), anyList(), any(Duration.class)))
                .thenReturn(new PptMasterCommandExecutor.CommandResult(1, "boom", false));

        assertThatThrownBy(() -> new BeautifyPptxOutputVerifier(
                new PythonPptxMarkdownReadback(commands, PythonPptxMarkdownReadback.BEAUTIFY_SCRIPT),
                mock(PptWorkflowEvents.class))
                .verify(job, export, project, Duration.ofSeconds(5)))
                .isInstanceOf(BeautifyPptxAnalysisException.class)
                .extracting(ex -> ((BeautifyPptxAnalysisException) ex).errorCode())
                .isEqualTo(BeautifyPptxErrorCode.BEAUTIFY_READBACK_FAILED);
    }

    private PptJob job(Path project) {
        PptJob job = new PptJob(UUID.randomUUID(), "beautify", "ppt169", null, PptWorkflowMode.BEAUTIFY_PPTX, project);
        job.prepareProject(project);
        return job;
    }

    private String draftJson() {
        return """
                {"version":1,"status":"draft","sourcePageCount":1,"hardConstraints":{"textVerbatim":true,
                "dataVerbatim":true,"pageMapping":"1:1"},"identityRecommendation":{"source":"theme",
                "candidates":[{"id":"theme"},{"id":"observed"}],"paletteCandidateId":"theme",
                "fontCandidateId":"theme","bodySizeCandidateId":"theme","rationale":"x"},
                "ignored":[],"needsConfirmation":[],"validationStrictness":"standard"}
                """;
    }

    private Path writePptx(Path path, int slides) throws IOException {
        Files.createDirectories(path.getParent());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<?xml version=\"1.0\"?><Types></Types>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            StringBuilder presentation = new StringBuilder(
                    "<?xml version=\"1.0\"?><p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
                            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
            for (int i = 0; i < slides; i++) {
                presentation.append("<p:sldId id=\"").append(256 + i).append("\" r:id=\"rId").append(i + 1).append("\"/>");
            }
            presentation.append("</p:presentation>");
            zip.putNextEntry(new ZipEntry("ppt/presentation.xml"));
            zip.write(presentation.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.write(path, bytes.toByteArray());
        return path;
    }
}
