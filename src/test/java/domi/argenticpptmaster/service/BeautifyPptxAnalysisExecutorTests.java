package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.BeautifyPptxFailureKind;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class BeautifyPptxAnalysisExecutorTests {

    @TempDir
    Path tempDir;

    @Test
    void runsServiceOwnedCommandsInOrderAndPersistsSafeCheckpoint() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        PptJob job = beautifyJob(workspace);
        PptMasterCommandExecutor commands = mock(PptMasterCommandExecutor.class);
        PptWorkflowEvents events = mock(PptWorkflowEvents.class);
        when(commands.runPythonScript(any(), anyList(), any(Duration.class))).thenAnswer(invocation -> {
            String script = invocation.getArgument(0);
            List<String> args = invocation.getArgument(1);
            if (script.endsWith("beautify_identity.py")) {
                return success("{\"canvas\":{\"aspect\":1.7777}}");
            }
            if (script.endsWith("project_manager.py") && "init".equals(args.get(0))) {
                Path project = Path.of(args.get(args.indexOf("--dir") + 1))
                        .resolve(args.get(1) + "_" + args.get(3) + "_" + java.time.LocalDate.now()
                                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
                Files.createDirectories(project.resolve("analysis"));
                Files.createDirectories(project.resolve("sources"));
                return success("ok");
            }
            if (script.endsWith("project_manager.py")) {
                Path project = Path.of(args.get(1));
                Files.writeString(project.resolve("sources/source.pptx"), "pptx");
                return success("ok");
            }
            if (script.endsWith("pptx_intake.py")) {
                Path analysis = Path.of(args.get(args.indexOf("-o") + 1));
                Files.writeString(analysis.resolve("source.identity.json"), identityJson());
                Files.writeString(analysis.resolve("source.slide_library.json"), libraryJson());
                Files.writeString(analysis.resolve("source_profile.json"), profileJson());
                return success("ok");
            }
            if (script.endsWith("beautify_inventory.py")) {
                Path output = Path.of(args.get(args.indexOf("-o") + 1));
                Files.writeString(output, inventoryJson(false));
                return success("ok");
            }
            throw new AssertionError("unexpected command: " + script);
        });

        new BeautifyPptxAnalysisExecutor(PptMasterProperties.forTest(tempDir.resolve("repo"), workspace), commands, events)
                .execute(job);

        assertThat(job.nodeExecution(PptJobNode.PROJECT_READY).status()).isEqualTo(
                domi.argenticpptmaster.domain.PptJobNodeStatus.COMPLETED);
        assertThat(job.nodeExecution(PptJobNode.BEAUTIFY_ANALYZED).summary())
                .containsEntry("sourcePageCount", 1)
                .doesNotContainKeys("workspace", "projectPath", "identityPath");
        InOrder order = inOrder(commands);
        order.verify(commands).runPythonScript(eq("skills/ppt-master/scripts/beautify_identity.py"), anyList(), any(Duration.class));
        order.verify(commands).runPythonScript(eq("skills/ppt-master/scripts/project_manager.py"),
                argThat(args -> "init".equals(args.get(0))), any(Duration.class));
        order.verify(commands).runPythonScript(eq("skills/ppt-master/scripts/project_manager.py"),
                argThat(args -> "import-sources".equals(args.get(0))), any(Duration.class));
        order.verify(commands).runPythonScript(eq("skills/ppt-master/scripts/pptx_intake.py"), anyList(), any(Duration.class));
        order.verify(commands).runPythonScript(eq("skills/ppt-master/scripts/beautify_inventory.py"), anyList(), any(Duration.class));
    }

    @Test
    void acceptsSupportedMediaObjectsAndCountsThem() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        PptJob job = beautifyJob(workspace);
        Path project = preparedProject(workspace, job);
        Files.writeString(project.resolve("analysis/source.identity.json"), identityJson());
        Files.writeString(project.resolve("analysis/source.slide_library.json"), libraryJson());
        Files.writeString(project.resolve("analysis/source_profile.json"), profileJson());
        Files.writeString(project.resolve("analysis/beautify_inventory.json"), inventoryWithMediaJson());
        Files.createDirectories(project.resolve("images"));
        Files.writeString(project.resolve("images/image_manifest.json"), "[{\"filename\":\"a.png\"}]");
        job.prepareProject(project);
        job.completeNode(PptJobNode.PROJECT_READY, java.util.Map.of("resolvedFormat", "ppt169"));

        new BeautifyPptxAnalysisExecutor(PptMasterProperties.forTest(tempDir.resolve("repo"), workspace),
                mock(PptMasterCommandExecutor.class), mock(PptWorkflowEvents.class)).execute(job);

        assertThat(job.nodeExecution(PptJobNode.BEAUTIFY_ANALYZED).summary())
                .containsEntry("sourcePageCount", 1)
                .containsEntry("imageCount", 1)
                .containsEntry("tableCount", 1)
                .containsEntry("chartCount", 1)
                .containsEntry("imageManifestReady", true)
                .containsEntry("complexObjectAlertCount", 0);
    }

    @Test
    void surfacesComplexObjectsAsAlertsWithoutFailingAnalysis() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        PptJob job = beautifyJob(workspace);
        Path project = preparedProject(workspace, job);
        Files.writeString(project.resolve("analysis/source.identity.json"), identityJson());
        Files.writeString(project.resolve("analysis/source.slide_library.json"), libraryJson());
        Files.writeString(project.resolve("analysis/source_profile.json"), profileJson());
        Files.writeString(project.resolve("analysis/beautify_inventory.json"), inventoryWithComplexJson());
        job.prepareProject(project);
        job.completeNode(PptJobNode.PROJECT_READY, java.util.Map.of("resolvedFormat", "ppt169"));

        new BeautifyPptxAnalysisExecutor(PptMasterProperties.forTest(tempDir.resolve("repo"), workspace),
                mock(PptMasterCommandExecutor.class), mock(PptWorkflowEvents.class)).execute(job);

        assertThat(job.nodeExecution(PptJobNode.BEAUTIFY_ANALYZED).status())
                .isEqualTo(domi.argenticpptmaster.domain.PptJobNodeStatus.COMPLETED);
        assertThat(job.nodeExecution(PptJobNode.BEAUTIFY_ANALYZED).summary())
                .containsEntry("chartCount", 1)
                .containsEntry("complexObjectAlertCount", 2);
    }

    @Test
    void rejectsUnclassifiableOrUnsupportedObjects() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        PptJob job = beautifyJob(workspace);
        Path project = preparedProject(workspace, job);
        Files.writeString(project.resolve("analysis/source.identity.json"), identityJson());
        Files.writeString(project.resolve("analysis/source.slide_library.json"), libraryJson());
        Files.writeString(project.resolve("analysis/source_profile.json"), profileJson());
        Files.writeString(project.resolve("analysis/beautify_inventory.json"), inventoryUnsupportedJson());
        job.prepareProject(project);
        job.completeNode(PptJobNode.PROJECT_READY, java.util.Map.of("resolvedFormat", "ppt169"));

        BeautifyPptxAnalysisExecutor executor = new BeautifyPptxAnalysisExecutor(
                PptMasterProperties.forTest(tempDir.resolve("repo"), workspace), mock(PptMasterCommandExecutor.class),
                mock(PptWorkflowEvents.class));

        assertThatThrownBy(() -> executor.execute(job))
                .isInstanceOf(BeautifyPptxAnalysisException.class)
                .extracting(error -> ((BeautifyPptxAnalysisException) error).failureKind())
                .isEqualTo(BeautifyPptxFailureKind.UNSUPPORTED_CONTENT);
    }

    @Test
    void reusesValidIntakeAndInventoryWithoutRerunningCommands() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        PptJob job = beautifyJob(workspace);
        Path project = preparedProject(workspace, job);
        Files.writeString(project.resolve("analysis/source.identity.json"), identityJson());
        Files.writeString(project.resolve("analysis/source.slide_library.json"), libraryJson());
        Files.writeString(project.resolve("analysis/source_profile.json"), profileJson());
        Files.writeString(project.resolve("analysis/beautify_inventory.json"), inventoryJson(false));
        job.prepareProject(project);
        job.completeNode(PptJobNode.PROJECT_READY, java.util.Map.of("resolvedFormat", "ppt169"));
        PptMasterCommandExecutor commands = mock(PptMasterCommandExecutor.class);

        new BeautifyPptxAnalysisExecutor(PptMasterProperties.forTest(tempDir.resolve("repo"), workspace), commands,
                mock(PptWorkflowEvents.class)).execute(job);

        assertThat(job.nodeExecution(PptJobNode.BEAUTIFY_ANALYZED).summary()).containsEntry("sourcePageCount", 1);
        org.mockito.Mockito.verifyNoInteractions(commands);
    }

    @Test
    void rebuildsInvalidDerivedArtifactsWhileLeavingUploadSourceUntouched() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        PptJob job = beautifyJob(workspace);
        Path project = preparedProject(workspace, job);
        Path upload = job.sourceFiles().get(0).storedPath();
        Files.writeString(upload, "original-upload");
        Files.writeString(project.resolve("analysis/source.identity.json"), "{not-json");
        Files.writeString(project.resolve("analysis/source.slide_library.json"), libraryJson());
        Files.writeString(project.resolve("analysis/source_profile.json"), profileJson());
        Files.writeString(project.resolve("analysis/beautify_inventory.json"), "{\"schema\":\"broken\"}");
        job.prepareProject(project);
        job.completeNode(PptJobNode.PROJECT_READY, java.util.Map.of("resolvedFormat", "ppt169"));
        PptMasterCommandExecutor commands = mock(PptMasterCommandExecutor.class);
        when(commands.runPythonScript(any(), anyList(), any(Duration.class))).thenAnswer(invocation -> {
            String script = invocation.getArgument(0);
            List<String> args = invocation.getArgument(1);
            if (script.endsWith("pptx_intake.py")) {
                Path analysis = Path.of(args.get(args.indexOf("-o") + 1));
                Files.writeString(analysis.resolve("source.identity.json"), identityJson());
                Files.writeString(analysis.resolve("source.slide_library.json"), libraryJson());
                Files.writeString(analysis.resolve("source_profile.json"), profileJson());
                return success("ok");
            }
            if (script.endsWith("beautify_inventory.py")) {
                Files.writeString(Path.of(args.get(args.indexOf("-o") + 1)), inventoryJson(false));
                return success("ok");
            }
            throw new AssertionError("unexpected command: " + script);
        });

        new BeautifyPptxAnalysisExecutor(PptMasterProperties.forTest(tempDir.resolve("repo"), workspace), commands,
                mock(PptWorkflowEvents.class)).execute(job);

        assertThat(job.nodeExecution(PptJobNode.BEAUTIFY_ANALYZED).status())
                .isEqualTo(domi.argenticpptmaster.domain.PptJobNodeStatus.COMPLETED);
        assertThat(Files.readString(upload)).isEqualTo("original-upload");
        InOrder order = inOrder(commands);
        order.verify(commands).runPythonScript(eq("skills/ppt-master/scripts/pptx_intake.py"), anyList(), any(Duration.class));
        order.verify(commands).runPythonScript(eq("skills/ppt-master/scripts/beautify_inventory.py"), anyList(), any(Duration.class));
    }

    private Path preparedProject(Path workspace, PptJob job) throws IOException {
        Path project = workspace.resolve("projects/demo_" + job.id().toString().substring(0, 8) + "_ppt169_"
                + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
        Files.createDirectories(project.resolve("analysis"));
        Files.createDirectories(project.resolve("sources"));
        Files.writeString(project.resolve("sources/source.pptx"), "pptx");
        return project;
    }

    private PptJob beautifyJob(Path workspace) throws IOException {
        UUID id = UUID.randomUUID();
        Path source = workspace.resolve("jobs").resolve(id.toString()).resolve("uploads/source.pptx");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "pptx");
        PptJob job = new PptJob(id, "demo", "ppt169", "layout only", PptWorkflowMode.BEAUTIFY_PPTX, source.getParent());
        job.addSource(new PptSourceFile("source.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", 4, source));
        return job;
    }

    private static PptMasterCommandExecutor.CommandResult success(String output) {
        return new PptMasterCommandExecutor.CommandResult(0, output, false);
    }

    private static String identityJson() {
        return "{\"slide_count\":1,\"canvas\":{\"width_px\":1920,\"height_px\":1080,\"aspect\":1.7777}}";
    }

    private static String libraryJson() {
        return "{\"slide_count\":1,\"slides\":[{\"slide_index\":1}]}";
    }

    private static String profileJson() {
        return "{\"decks\":[{}]}";
    }

    private static String inventoryJson(boolean complex) {
        return "{\"schema\":\"beautify_inventory.v1\",\"slide_count\":1,\"slides\":[{\"slide_index\":1,"
                + "\"text_blocks\":[{\"text\":\"Hello\"}],\"images\":" + (complex ? "[{\"id\":\"image\"}]" : "[]")
                + ",\"tables\":[],\"charts\":[],\"shapes\":[]}],\"ignored\":[],\"needs_confirmation\":[]}";
    }

    private static String inventoryWithMediaJson() {
        return "{\"schema\":\"beautify_inventory.v1\",\"slide_count\":1,\"slides\":[{\"slide_index\":1,"
                + "\"text_blocks\":[{\"text\":\"Hello\"}],"
                + "\"images\":[{\"id\":\"img-1\",\"filename\":\"a.png\"}],"
                + "\"tables\":[{\"id\":\"t1\",\"rows\":[[\"A\",\"B\"],[\"1\",\"2\"]]}],"
                + "\"charts\":[{\"id\":\"c1\",\"chart_type\":\"clustered_column\","
                + "\"categories\":[\"Q1\"],\"series\":[{\"name\":\"S1\",\"values\":[1]}]}],"
                + "\"shapes\":[]}],\"ignored\":[],\"needs_confirmation\":[]}";
    }

    private static String inventoryWithComplexJson() {
        return "{\"schema\":\"beautify_inventory.v1\",\"slide_count\":1,\"slides\":[{\"slide_index\":1,"
                + "\"text_blocks\":[{\"text\":\"Hello\"}],\"images\":[],"
                + "\"tables\":[{\"id\":\"t1\",\"has_merged_cells\":true,\"rows\":[[\"A\"]]}],"
                + "\"charts\":[{\"id\":\"c1\",\"chart_type\":\"combo\",\"categories\":[\"Q1\"],"
                + "\"series\":[{\"name\":\"S1\",\"values\":[1]}]}],\"shapes\":[]}],"
                + "\"ignored\":[],\"needs_confirmation\":[]}";
    }

    private static String inventoryUnsupportedJson() {
        return "{\"schema\":\"beautify_inventory.v1\",\"slide_count\":1,\"slides\":[{\"slide_index\":1,"
                + "\"text_blocks\":[{\"text\":\"Hello\"}],\"images\":[],\"tables\":[],\"charts\":[],"
                + "\"shapes\":[],\"unsupported_objects\":[{\"id\":\"x\",\"type\":\"ole\"}]}],"
                + "\"ignored\":[],\"needs_confirmation\":[]}";
    }
}
