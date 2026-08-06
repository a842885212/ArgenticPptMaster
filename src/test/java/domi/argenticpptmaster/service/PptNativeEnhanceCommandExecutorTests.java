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
import java.util.Map;
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
        executor = new PptNativeEnhanceCommandExecutor(
                properties, repository, events, commands,
                new NativeEnhanceReadinessContributor(),
                new NativeEnhanceOutputVerifier());
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

        PptJob waiting = repository.findById(jobId).orElseThrow();
        assertThat(waiting.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        assertThat(waiting.confirmationPayload().get("stage")).isEqualTo("enhancement_plan_confirmation");
        assertThat(waiting.nodeExecution(PptJobNode.ENHANCEMENT_PLAN_DRAFTED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(waiting.nodeExecution(PptJobNode.ENHANCEMENT_PLAN_CONFIRMED).status())
                .isNotEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(waiting.exportPath()).isEmpty();

        NativeEnhancePlanStore.PlanMetadata draft = NativeEnhancePlanStore.readDraft(waiting).orElseThrow();
        NativeEnhancePlanStore.confirmDraft(waiting, draft.version(), draft.digest(), Map.of());
        waiting.completeNode(PptJobNode.ENHANCEMENT_PLAN_CONFIRMED, Map.of(
                "enhancementPlanStatus", "confirmed",
                "confirmationMode", "confirm-api"));
        repository.save(waiting);

        executor.continueAfterPlanConfirmation(jobId);

        PptJob completed = repository.findById(jobId).orElseThrow();
        assertThat(completed.status()).isEqualTo(PptJobStatus.COMPLETED);
        assertThat(completed.exportPath()).isPresent();
        assertThat(completed.exportPath().orElseThrow().getFileName().toString()).isEqualTo("source_enhanced.pptx");
        assertThat(completed.nodeExecution(PptJobNode.NATIVE_ENHANCE_INITIALIZED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(completed.nodeExecution(PptJobNode.ENHANCEMENT_PLAN_DRAFTED).status())
                .isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(completed.nodeExecution(PptJobNode.ENHANCEMENT_PLAN_DRAFTED).summary())
                .containsEntry("enhancementPlanStatus", "draft");
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
        assertThat(Files.isRegularFile(project.resolve("validation/native-enhance-readback.json"))).isTrue();
        assertThat(Files.readString(project.resolve("validation/native-enhance-readback.json")))
                .contains("\"status\" : \"PASSED\"");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(commands, org.mockito.Mockito.atLeastOnce())
                .runPythonScript(eq(PptNativeEnhanceCommandExecutor.NATIVE_ENHANCE_SCRIPT), argsCaptor.capture(), any());
        assertThat(argsCaptor.getAllValues()).allSatisfy(args ->
                assertThat(args).noneMatch(arg -> arg != null && arg.contains("--force")));
    }

    @Test
    void buildApplyArgumentsIncludesControlledFlagsWithoutForce() {
        Path project = Path.of("/tmp/proj");
        NativeEnhancePlanStore.ApplySettings settings = new NativeEnhancePlanStore.ApplySettings(
                true, true, true, true, "fade", 0.5, true, 0.4);
        List<String> args = PptNativeEnhanceCommandExecutor.buildApplyArguments(project, settings);
        assertThat(args).contains("apply", "--overwrite", "--transition", "fade",
                "--transition-duration", "0.5", "--narration-padding", "0.4",
                "--apply-transition-without-audio");
        assertThat(args).noneMatch(a -> a.contains("--force"));
    }

    @Test
    void buildApplyArgumentsSkipsTransitionFlagsWhenDisabled() {
        List<String> args = PptNativeEnhanceCommandExecutor.buildApplyArguments(
                Path.of("/tmp/proj"),
                new NativeEnhancePlanStore.ApplySettings(true, false, false, false, "fade", 0.5, false, 0.4));
        assertThat(args).containsExactly("apply", "/tmp/proj", "--overwrite");
    }

    @Test
    void buildNotesToAudioArgumentsUsesConfirmedVoice() {
        List<String> args = PptNativeEnhanceCommandExecutor.buildNotesToAudioArguments(
                Path.of("/tmp/proj"),
                Map.of("provider", "edge", "voice", "zh-CN-YunjianNeural", "rate", "+10%"));
        assertThat(args).containsExactly(
                "/tmp/proj", "--voice", "zh-CN-YunjianNeural", "--rate", "+10%", "--provider", "edge");
        assertThat(args).noneMatch(a -> a.contains("--force"));
    }

    @Test
    void rejectUnsafeCommandAllowsNotesToAudioScript() {
        PptNativeEnhanceCommandExecutor.rejectUnsafeCommand(
                "AUDIO",
                PptNativeEnhanceCommandExecutor.NOTES_TO_AUDIO_SCRIPT,
                List.of("/tmp/proj", "--voice", "x"));
    }

    @Test
    void resumeFromInitializedSkipsInitAndPreservesUploadBytes() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = properties.workspacePath().resolve("jobs").resolve(jobId.toString());
        Files.createDirectories(workspace.resolve("uploads"));
        Path source = workspace.resolve("uploads/source.pptx");
        byte[] original = new byte[] {0x50, 0x4b, 0x03, 0x04};
        Files.write(source, original);

        Path project = workspace.resolve("projects/phase0");
        Files.createDirectories(project.resolve("analysis"));
        Files.createDirectories(project.resolve("notes"));
        Files.createDirectories(project.resolve("exports"));
        Files.createDirectories(project.resolve("validation"));
        Files.writeString(project.resolve("project.json"), """
                {"schema":"native_pptx_enhancement_project.v1","kind":"native_pptx_enhancement","slide_count":1}
                """);
        ObjectNode index = objectMapper.createObjectNode();
        index.put("schema", "native_pptx_enhancement_slide_index.v1");
        index.put("slide_count", 1);
        index.putArray("slides").addObject()
                .put("index", 1).put("note_file", "001.md").put("slide_number", 1).put("title", "Cover");
        objectMapper.writeValue(project.resolve("analysis/slide_index.json").toFile(), index);
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("schema", "native_pptx_enhancement_plan.v1");
        plan.put("status", "draft");
        ObjectNode modules = plan.putObject("modules");
        modules.putObject("notes").put("enabled", true);
        modules.putObject("audio").put("enabled", false);
        modules.putObject("timings").put("enabled", false);
        modules.putObject("transitions").put("enabled", false);
        objectMapper.writeValue(project.resolve("analysis/enhancement_plan.json").toFile(), plan);

        PptJob job = new PptJob(jobId, "phase0", "ppt169", "notes", PptWorkflowMode.NATIVE_ENHANCE_PPTX, workspace);
        job.addSource(new PptSourceFile("source.pptx", "application/octet-stream", original.length, source));
        job.prepareProject(project);
        job.startNode(PptJobNode.PROJECT_READY);
        job.completeNode(PptJobNode.PROJECT_READY, Map.of("projectReady", true));
        job.startNode(PptJobNode.NATIVE_ENHANCE_INITIALIZED);
        job.completeNode(PptJobNode.NATIVE_ENHANCE_INITIALIZED, Map.of("sourcePageCount", 1));
        job.startNode(PptJobNode.ENHANCEMENT_PLAN_DRAFTED);
        job.completeNode(PptJobNode.ENHANCEMENT_PLAN_DRAFTED, Map.of(
                "enhancementPlanStatus", "draft", "enabledModules", List.of("notes")));
        repository.save(job);

        when(commands.runPythonScript(anyString(), anyList(), any(Duration.class))).thenAnswer(invocation -> {
            String script = invocation.getArgument(0);
            List<String> args = invocation.getArgument(1);
            if (PptNativeEnhanceCommandExecutor.NATIVE_ENHANCE_SCRIPT.equals(script) && args.get(0).equals("init")) {
                return new PptMasterCommandExecutor.CommandResult(1, "init should not run", false);
            }
            if (PptNativeEnhanceCommandExecutor.NATIVE_ENHANCE_SCRIPT.equals(script) && args.get(0).equals("validate")) {
                Files.writeString(project.resolve("validation/report.json"), """
                        {"schema":"native_pptx_enhancement_validation.v1","plan_status":"confirmed","notes_count":1,"missing_notes":[]}
                        """);
                return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
            }
            if (PptNativeEnhanceCommandExecutor.NATIVE_ENHANCE_SCRIPT.equals(script) && args.get(0).equals("apply")) {
                writeMinimalPptx(project.resolve("exports/source_enhanced.pptx"), 1);
                return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
            }
            if (PptNativeEnhanceCommandExecutor.PPT_TO_MD_SCRIPT.equals(script)) {
                Path out = Path.of(args.get(args.indexOf("-o") + 1));
                Files.writeString(out, "# readback\n");
                return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
            }
            return new PptMasterCommandExecutor.CommandResult(1, "unexpected", false);
        });

        executor.resumeFromCheckpoint(jobId, PptJobNode.NATIVE_ENHANCE_INITIALIZED);

        PptJob waiting = repository.findById(jobId).orElseThrow();
        assertThat(waiting.status()).isEqualTo(PptJobStatus.WAITING_CONFIRMATION);
        assertThat(Files.readAllBytes(source)).isEqualTo(original);
        assertThat(waiting.confirmationPayload().get("stage")).isEqualTo("enhancement_plan_confirmation");
        org.mockito.Mockito.verify(commands, org.mockito.Mockito.never())
                .runPythonScript(eq(PptNativeEnhanceCommandExecutor.NATIVE_ENHANCE_SCRIPT),
                        org.mockito.ArgumentMatchers.argThat(args -> args != null && !args.isEmpty() && "init".equals(args.get(0))),
                        any());
    }

    @Test
    void cannotApplyWithoutConfirmedPlan() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = properties.workspacePath().resolve("jobs").resolve(jobId.toString());
        Files.createDirectories(workspace.resolve("uploads"));
        Path source = workspace.resolve("uploads/source.pptx");
        Files.write(source, new byte[] {0x50, 0x4b});

        Path project = workspace.resolve("projects/phase0");
        Files.createDirectories(project.resolve("analysis"));
        Files.createDirectories(project.resolve("exports"));
        Files.writeString(project.resolve("project.json"), """
                {"schema":"native_pptx_enhancement_project.v1","kind":"native_pptx_enhancement","slide_count":1}
                """);
        ObjectNode index = objectMapper.createObjectNode();
        index.put("schema", "native_pptx_enhancement_slide_index.v1");
        index.put("slide_count", 1);
        index.putArray("slides").addObject()
                .put("index", 1).put("note_file", "001.md").put("slide_number", 1);
        objectMapper.writeValue(project.resolve("analysis/slide_index.json").toFile(), index);
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("schema", "native_pptx_enhancement_plan.v1");
        plan.put("status", "draft");
        plan.put("slide_count", 1);
        ObjectNode modules = plan.putObject("modules");
        modules.putObject("notes").put("enabled", true);
        modules.putObject("audio").put("enabled", false);
        modules.putObject("timings").put("enabled", false);
        modules.putObject("transitions").put("enabled", false);
        objectMapper.writeValue(project.resolve("analysis/enhancement_plan.json").toFile(), plan);

        PptJob job = new PptJob(jobId, "phase0", "ppt169", "notes", PptWorkflowMode.NATIVE_ENHANCE_PPTX, workspace);
        job.addSource(new PptSourceFile("source.pptx", "application/octet-stream", 2, source));
        job.prepareProject(project);
        job.completeNode(PptJobNode.PROJECT_READY, Map.of());
        job.completeNode(PptJobNode.NATIVE_ENHANCE_INITIALIZED, Map.of());
        job.completeNode(PptJobNode.ENHANCEMENT_PLAN_DRAFTED, Map.of("enhancementPlanStatus", "draft"));
        // Node marked confirmed but on-disk plan remains draft — apply must still refuse.
        job.completeNode(PptJobNode.ENHANCEMENT_PLAN_CONFIRMED, Map.of("enhancementPlanStatus", "confirmed"));
        repository.save(job);
        NativeEnhancePlanStore.ensureDraftMetadata(job);

        when(commands.runPythonScript(anyString(), anyList(), any(Duration.class)))
                .thenReturn(new PptMasterCommandExecutor.CommandResult(0, "should not apply", false));

        assertThatThrownBy(() -> executor.continueAfterPlanConfirmation(jobId))
                .isInstanceOf(NativeEnhanceExecutionException.class)
                .hasMessageContaining("confirmed");
    }

    @Test
    void initFailureRecordsStableErrorCode() {
        UUID jobId = UUID.randomUUID();
        Path workspace = properties.workspacePath().resolve("jobs").resolve(jobId.toString());
        try {
            Files.createDirectories(workspace.resolve("uploads"));
            Path source = workspace.resolve("uploads/source.pptx");
            Files.write(source, new byte[] {0x50, 0x4b});
            PptJob job = new PptJob(jobId, "phase0", "ppt169", "notes", PptWorkflowMode.NATIVE_ENHANCE_PPTX, workspace);
            job.addSource(new PptSourceFile("source.pptx", "application/octet-stream", 2, source));
            repository.save(job);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        when(commands.runPythonScript(anyString(), anyList(), any(Duration.class)))
                .thenReturn(new PptMasterCommandExecutor.CommandResult(1, "boom", true));

        assertThatThrownBy(() -> executor.runNotesOnlyMvp(jobId))
                .isInstanceOf(NativeEnhanceExecutionException.class)
                .satisfies(ex -> {
                    NativeEnhanceExecutionException failure = (NativeEnhanceExecutionException) ex;
                    assertThat(failure.errorCode().code()).isEqualTo("NATIVE_ENHANCE_INIT_FAILED");
                    assertThat(failure.stage()).isEqualTo("INIT");
                });

        PptJob failed = repository.findById(jobId).orElseThrow();
        assertThat(failed.status()).isEqualTo(PptJobStatus.FAILED);
        assertThat(failed.errorMessage().orElse("")).contains("NATIVE_ENHANCE_INIT_FAILED");
    }

    @Test
    void timingsEnabledFailsClosedWhenFfprobeMissing() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = properties.workspacePath().resolve("jobs").resolve(jobId.toString());
        Files.createDirectories(workspace.resolve("uploads"));
        Path source = workspace.resolve("uploads/source.pptx");
        Files.write(source, new byte[] {0x50, 0x4b});

        Path project = workspace.resolve("projects/phase0");
        Files.createDirectories(project.resolve("analysis"));
        Files.createDirectories(project.resolve("notes"));
        Files.createDirectories(project.resolve("exports"));
        Files.createDirectories(project.resolve("validation"));
        Files.writeString(project.resolve("project.json"), """
                {"schema":"native_pptx_enhancement_project.v1","kind":"native_pptx_enhancement","slide_count":1,"audio":{"provider":"edge","voice":"zh-CN-YunjianNeural","rate":"+0%"}}
                """);
        ObjectNode index = objectMapper.createObjectNode();
        index.put("schema", "native_pptx_enhancement_slide_index.v1");
        index.put("slide_count", 1);
        index.putArray("slides").addObject()
                .put("index", 1).put("note_file", "001.md").put("slide_number", 1).put("title", "Cover");
        objectMapper.writeValue(project.resolve("analysis/slide_index.json").toFile(), index);

        PptJob job = new PptJob(jobId, "phase0", "ppt169", "notes", PptWorkflowMode.NATIVE_ENHANCE_PPTX, workspace);
        job.addSource(new PptSourceFile("source.pptx", "application/octet-stream", 2, source));
        job.prepareProject(project);
        job.completeNode(PptJobNode.PROJECT_READY, Map.of());
        job.completeNode(PptJobNode.NATIVE_ENHANCE_INITIALIZED, Map.of());
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("schema", "native_pptx_enhancement_plan.v1");
        plan.put("status", "draft");
        plan.put("slide_count", 1);
        ObjectNode modules = plan.putObject("modules");
        modules.putObject("notes").put("enabled", true);
        modules.putObject("audio").put("enabled", false);
        modules.putObject("timings").put("enabled", true).put("narration_padding", 0.4);
        modules.putObject("transitions").put("enabled", false);
        objectMapper.writeValue(project.resolve("analysis/enhancement_plan.json").toFile(), plan);
        NativeEnhancePlanStore.PlanMetadata draft = NativeEnhancePlanStore.ensureDraftMetadata(job);
        NativeEnhancePlanStore.confirmDraft(job, draft.version(), draft.digest(), Map.of(
                "notesEnabled", true,
                "timingsEnabled", true,
                "audioEnabled", false,
                "transitionsEnabled", false));
        job.completeNode(PptJobNode.ENHANCEMENT_PLAN_DRAFTED, Map.of());
        job.completeNode(PptJobNode.ENHANCEMENT_PLAN_CONFIRMED, Map.of());
        repository.save(job);

        PptNativeEnhanceCommandExecutor failingExecutor = new PptNativeEnhanceCommandExecutor(
                properties, repository, events, commands,
                new NativeEnhanceReadinessContributor(tempDir.resolve("no-ffprobe").toString()),
                new NativeEnhanceOutputVerifier());

        assertThatThrownBy(() -> failingExecutor.continueAfterPlanConfirmation(jobId))
                .isInstanceOf(NativeEnhanceExecutionException.class)
                .satisfies(ex -> {
                    NativeEnhanceExecutionException failure = (NativeEnhanceExecutionException) ex;
                    assertThat(failure.errorCode().code()).isEqualTo("NATIVE_ENHANCE_FFPROBE_UNAVAILABLE");
                });
    }

    private static void writeMinimalPptx(Path path, int slides) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types></Types>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            StringBuilder presentation = new StringBuilder(
                    "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">");
            for (int i = 1; i <= slides; i++) {
                presentation.append("<p:sldId id=\"").append(i).append("\"/>");
            }
            presentation.append("</p:presentation>");
            zip.putNextEntry(new ZipEntry("ppt/presentation.xml"));
            zip.write(presentation.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int i = 1; i <= slides; i++) {
                zip.putNextEntry(new ZipEntry("ppt/slides/slide" + i + ".xml"));
                zip.write(("<p:sld><p:cSld/></p:sld>").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("ppt/notesSlides/notesSlide" + i + ".xml"));
                zip.write("<p:notes></p:notes>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
