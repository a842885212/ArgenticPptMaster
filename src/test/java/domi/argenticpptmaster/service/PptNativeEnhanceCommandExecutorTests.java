package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.repository.InMemoryPptJobRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class PptNativeEnhanceCommandExecutorTests {

    @TempDir
    Path tempDir;

    private PptMasterProperties properties;
    private InMemoryPptJobRepository repository;
    private PptWorkflowEvents events;
    private PptMasterCommandExecutor commands;
    private PptNativeEnhanceCommandExecutor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new PptMasterProperties(
                tempDir.resolve("repo"),
                tempDir.resolve("workspace"),
                "python3",
                Duration.ofMinutes(1),
                null, 0, 0, 0, 0, 0, null, null, null, null, null, null);
        repository = new InMemoryPptJobRepository();
        events = mock(PptWorkflowEvents.class);
        commands = mock(PptMasterCommandExecutor.class);
        executor = new PptNativeEnhanceCommandExecutor(properties, repository, events, commands);
    }

    @Test
    void rejectUnsafeCommandBansForceAndNonWhitelistedScripts() {
        assertThatThrownBy(() -> PptNativeEnhanceCommandExecutor.rejectUnsafeCommand(
                "APPLY",
                PptNativeEnhanceCommandExecutor.NATIVE_ENHANCE_SCRIPT,
                List.of("apply", "/tmp/project", "--force")))
                .isInstanceOf(NativeEnhanceExecutionException.class)
                .hasMessageContaining("force");

        assertThatThrownBy(() -> PptNativeEnhanceCommandExecutor.rejectUnsafeCommand(
                "INIT",
                "skills/ppt-master/scripts/finalize_svg.py",
                List.of("x")))
                .isInstanceOf(NativeEnhanceExecutionException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void sanitizeSlugReplacesNonAscii() {
        assertThat(PptNativeEnhanceCommandExecutor.sanitizeSlug("你好 deck")).isEqualTo("___deck");
        assertThat(PptNativeEnhanceCommandExecutor.sanitizeSlug("  ")).isEqualTo("native_enhance");
        assertThat(PptNativeEnhanceCommandExecutor.sanitizeSlug("Demo_01")).isEqualTo("Demo_01");
    }

    @Test
    void runNotesOnlyMvpAdvancesNodesAndCompletesExport() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = properties.workspacePath().resolve("jobs").resolve(jobId.toString());
        Files.createDirectories(workspace.resolve("uploads"));
        Path source = workspace.resolve("uploads/source.pptx");
        Files.write(source, new byte[] {0x50, 0x4b});

        PptJob job = new PptJob(jobId, "phase0", "ppt169", "notes", PptWorkflowMode.NATIVE_ENHANCE_PPTX, workspace);
        job.addSource(new PptSourceFile("source.pptx", "application/octet-stream", 2, source));
        repository.save(job);

        when(commands.runPythonScript(anyString(), anyList(), any(Duration.class))).thenAnswer(invocation -> {
            String script = invocation.getArgument(0);
            List<String> args = invocation.getArgument(1);
            if (PptNativeEnhanceCommandExecutor.NATIVE_ENHANCE_SCRIPT.equals(script) && args.get(0).equals("init")) {
                Path project = Path.of(args.get(args.indexOf("--project-dir") + 1));
                Files.createDirectories(project.resolve("analysis"));
                Files.createDirectories(project.resolve("notes"));
                Files.createDirectories(project.resolve("exports"));
                Files.createDirectories(project.resolve("validation"));
                Files.writeString(project.resolve("project.json"), """
                        {"schema":"native_pptx_enhancement_project.v1","kind":"native_pptx_enhancement","slide_count":2}
                        """);
                ObjectNode index = objectMapper.createObjectNode();
                index.put("schema", "native_pptx_enhancement_slide_index.v1");
                index.put("slide_count", 2);
                ArrayNode slides = index.putArray("slides");
                slides.addObject().put("index", 1).put("note_file", "001.md").put("slide_number", 1).put("title", "Cover");
                slides.addObject().put("index", 2).put("note_file", "002.md").put("slide_number", 2).put("title", "Agenda");
                objectMapper.writeValue(project.resolve("analysis/slide_index.json").toFile(), index);
                ObjectNode plan = objectMapper.createObjectNode();
                plan.put("schema", "native_pptx_enhancement_plan.v1");
                plan.put("status", "draft");
                ObjectNode modules = plan.putObject("modules");
                modules.putObject("notes").put("enabled", true);
                modules.putObject("audio").put("enabled", true);
                modules.putObject("timings").put("enabled", true);
                modules.putObject("transitions").put("enabled", true);
                objectMapper.writeValue(project.resolve("analysis/enhancement_plan.json").toFile(), plan);
                return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
            }
            if (PptNativeEnhanceCommandExecutor.NATIVE_ENHANCE_SCRIPT.equals(script) && args.get(0).equals("validate")) {
                Path project = Path.of(args.get(1));
                Files.writeString(project.resolve("validation/report.json"), """
                        {"schema":"native_pptx_enhancement_validation.v1","plan_status":"confirmed","notes_count":2,"missing_notes":[]}
                        """);
                return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
            }
            if (PptNativeEnhanceCommandExecutor.NATIVE_ENHANCE_SCRIPT.equals(script) && args.get(0).equals("apply")) {
                Path project = Path.of(args.get(1));
                Path export = project.resolve("exports/source_enhanced.pptx");
                writeMinimalPptx(export, 2);
                return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
            }
            if (PptNativeEnhanceCommandExecutor.PPT_TO_MD_SCRIPT.equals(script)) {
                Path out = Path.of(args.get(args.indexOf("-o") + 1));
                Files.writeString(out, "# readback\nTotal slides: 2\n");
                return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
            }
            return new PptMasterCommandExecutor.CommandResult(1, "unexpected", false);
        });

        executor.runNotesOnlyMvp(jobId);

        PptJob completed = repository.findById(jobId).orElseThrow();
        assertThat(completed.status()).isEqualTo(PptJobStatus.COMPLETED);
        assertThat(completed.exportPath()).isPresent();
        assertThat(completed.exportPath().orElseThrow().getFileName().toString()).isEqualTo("source_enhanced.pptx");
        assertThat(completed.nodeExecution(PptJobNode.NATIVE_ENHANCE_INITIALIZED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(completed.nodeExecution(PptJobNode.ENHANCEMENT_PLAN_CONFIRMED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(completed.nodeExecution(PptJobNode.NOTES_GENERATED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(completed.nodeExecution(PptJobNode.NATIVE_ENHANCE_VALIDATED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(completed.nodeExecution(PptJobNode.PPT_EXPORTED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(completed.nodeExecution(PptJobNode.NATIVE_ENHANCE_OUTPUT_VALIDATED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);

        Path project = completed.projectPath().orElseThrow();
        assertThat(Files.readString(project.resolve("analysis/enhancement_plan.json")))
                .contains("\"status\" : \"confirmed\"")
                .contains("\"enabled\" : false");
        assertThat(Files.isRegularFile(project.resolve("notes/001.md"))).isTrue();
        assertThat(Files.isRegularFile(project.resolve("notes/002.md"))).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(commands, org.mockito.Mockito.atLeastOnce())
                .runPythonScript(eq(PptNativeEnhanceCommandExecutor.NATIVE_ENHANCE_SCRIPT), argsCaptor.capture(), any());
        assertThat(argsCaptor.getAllValues()).allSatisfy(args ->
                assertThat(args).noneMatch(arg -> arg != null && arg.contains("--force")));
    }

    private static void writeMinimalPptx(Path path, int slides) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types></Types>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int i = 1; i <= slides; i++) {
                zip.putNextEntry(new ZipEntry("ppt/slides/slide" + i + ".xml"));
                zip.write(("<s" + i + "/>").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
