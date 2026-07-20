package domi.argenticpptmaster.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.service.PptJobEventPublisher;
import domi.argenticpptmaster.service.PptTemplateFillAnalysisReader;
import domi.argenticpptmaster.service.PptTemplateFillPlanStore;
import domi.argenticpptmaster.service.PptWorkflowEvents;
import domi.argenticpptmaster.service.TemplateFillPlanValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateFillAgentToolsTests {

    @TempDir
    Path tempDir;

    private Path workspace;
    private Path project;
    private PptJob job;
    private PptAgentToolRuntime runtime;
    private TemplateFillAgentTools tools;
    private String validDraft;

    @BeforeEach
    void setUp() throws Exception {
        workspace = tempDir.resolve("job");
        project = workspace.resolve("projects/demo");
        Files.createDirectories(project.resolve("analysis"));
        Files.createDirectories(workspace.resolve("uploads/content"));
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"),
                project.resolve("analysis/template.slide_library.json"));
        validDraft = Files.readString(Path.of(
                getClass().getResource("/template-fill/fill-plan-draft-valid.json").toURI()));

        Path content = workspace.resolve("uploads/content/0-content.md");
        Files.writeString(content, "abcdefghij");
        Path exe = workspace.resolve("uploads/content/0-notes.exe");
        Files.writeString(exe, "binary");

        PptMasterProperties properties = new PptMasterProperties(
                tempDir, tempDir, "python3", Duration.ofSeconds(1), null, 1_048_576L, 0, 0, 0, 0, null);
        tools = new TemplateFillAgentTools(
                new PptTemplateFillPlanStore(properties, new TemplateFillPlanValidator()),
                new PptTemplateFillAnalysisReader());
        job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.prepareProject(project);
        job.addSource(new PptSourceFile("content.md", "text/markdown", 8L, content));
        job.addSource(new PptSourceFile("notes.exe", "application/octet-stream", 6L, exe));
        job.completeNode(PptJobNode.TEMPLATE_ANALYZED, Map.of());
        runtime = new PptAgentToolRuntime(
                job, properties, new PptMasterCommandExecutor(properties),
                new PptWorkflowEvents(new PptJobEventPublisher()));
    }

    @Test
    void listsOnlyAllowedContentTypesWithStableSourceRefsAndNoAbsolutePaths() {
        Map<String, Object> listed = tools.listContentSources(runtime);

        assertThat(listed.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) listed.get("sources");
        assertThat(sources).singleElement()
                .satisfies(source -> {
                    assertThat(source.get("sourceRef")).isEqualTo("content:content.md");
                    assertThat(source.get("originalName")).isEqualTo("content.md");
                    assertThat(source.toString()).doesNotContain(workspace.toString());
                });
    }

    @Test
    void readsBoundedContentAndRejectsEscapeSymlinkAndBudgetOverflow() throws Exception {
        Map<String, Object> page = tools.readContentSource(runtime, "content:content.md", 4);
        assertThat(page.get("content").toString()).contains("...[truncated]");
        assertThat(page.get("truncated")).isEqualTo(true);

        assertThatThrownBy(() -> tools.readContentSource(runtime, "content:notes.exe", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not allowed");

        Path outside = tempDir.resolve("outside.md");
        Files.writeString(outside, "secret");
        job.addSource(new PptSourceFile("escape.md", "text/markdown", 6L, outside));
        assertThatThrownBy(() -> tools.readContentSource(runtime, "content:escape.md", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("escapes");

        Path link = workspace.resolve("uploads/content/link.md");
        try {
            Files.createSymbolicLink(link, outside);
            job.addSource(new PptSourceFile("link.md", "text/markdown", 6L, link));
            assertThatThrownBy(() -> tools.readContentSource(runtime, "content:link.md", 10))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("symbolic");
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
            // some CI filesystems disallow symlinks; skip that assertion
        }

        Path bulky = workspace.resolve("uploads/content/1-bulk.md");
        Files.writeString(bulky, "y".repeat(20_000));
        job.addSource(new PptSourceFile("bulk.md", "text/markdown", 20_000L, bulky));
        for (int i = 0; i < 3; i++) {
            tools.readContentSource(runtime, "content:bulk.md", 16_000);
        }
        assertThatThrownBy(() -> tools.readContentSource(runtime, "content:bulk.md", 16_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget");
    }

    @Test
    void paginatesSlideLibraryWithTruncatedOptionalFields() {
        Map<String, Object> page1 = tools.readSlideLibraryPage(runtime, 1, 1);
        assertThat(page1).containsEntry("page", 1).containsEntry("pageSize", 1).containsEntry("totalSlides", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slides = (List<Map<String, Object>>) page1.get("slides");
        assertThat(slides).hasSize(1);
        assertThat(slides.get(0).get("slideIndex")).isEqualTo(1);

        Map<String, Object> page2 = tools.readSlideLibraryPage(runtime, 2, 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slides2 = (List<Map<String, Object>>) page2.get("slides");
        assertThat(slides2.get(0).get("slideIndex")).isEqualTo(2);
        assertThat(tools.readSlideLibraryPage(runtime, 99, 1).get("slides")).asList().isEmpty();
    }

    @Test
    void inspectCheckpointReturnsSafeSummaryWithoutAbsolutePaths() {
        Map<String, Object> status = tools.inspectCheckpointStatus(runtime);

        assertThat(status.get("lastCompletedNode")).isEqualTo("TEMPLATE_ANALYZED");
        assertThat(status.toString()).doesNotContain(workspace.toAbsolutePath().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> nodes = (Map<String, Object>) status.get("nodeStates");
        assertThat(nodes.get("TEMPLATE_ANALYZED")).isEqualTo(PptJobNodeStatus.COMPLETED.name());
        assertThat(nodes).doesNotContainKey("SVG_FINALIZED");
    }

    @Test
    void writePlanDraftStoresAtomicallyAndCompletesDraftNode() {
        Map<String, Object> result = tools.writePlanDraft(runtime, validDraft);

        assertThat(result).containsEntry("status", "draft");
        assertThat(result.get("version")).isEqualTo(1);
        assertThat(result.get("digest")).isNotNull();
        assertThat(job.nodeExecution(PptJobNode.FILL_PLAN_DRAFTED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(job.planSlideCount()).isEqualTo(2);
    }

    @Test
    void writePlanDraftRejectsConfirmedForceInvalidRefsAndArbitraryPaths() throws Exception {
        assertThatThrownBy(() -> tools.writePlanDraft(runtime,
                validDraft.replace("\"status\": \"draft\"", "\"status\": \"confirmed\"")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("draft");
        assertThatThrownBy(() -> tools.writePlanDraft(runtime, validDraft + " --force"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("force");
        String invalid = Files.readString(Path.of(
                getClass().getResource("/template-fill/fill-plan-draft-invalid-refs.json").toURI()));
        assertThatThrownBy(() -> tools.writePlanDraft(runtime, invalid))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("unknown template slide");
        assertThat(job.nodeExecution(PptJobNode.FILL_PLAN_DRAFTED) == null
                || job.nodeExecution(PptJobNode.FILL_PLAN_DRAFTED).status() != PptJobNodeStatus.COMPLETED).isTrue();
    }

    @Test
    void writePlanRequiresRiskOmissionAndStructuredFieldsAgainstCurrentAnalysis() {
        String missingFields = """
                {"status":"draft","slides":[{"outputOrder":1,"templateSlideIndex":1,
                "slotMappings":[{"slotId":"s01_sh1","sourceRef":"content:content.md","preview":"x"}]}]}
                """;
        // missing optional risk arrays are allowed; invalid slot refs are not
        Map<String, Object> ok = tools.writePlanDraft(runtime, missingFields);
        assertThat(ok.get("status")).isEqualTo("draft");

        assertThatThrownBy(() -> tools.writePlanDraft(runtime, """
                {"status":"draft","slides":[{"outputOrder":1,"templateSlideIndex":1,
                "slotMappings":[{"slotId":"missing","sourceRef":"content:content.md","preview":"x"}]}]}
                """))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("unknown slot");
    }
}
