package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptSourceFile;
import domi.argenticpptmaster.domain.PptTemplateFile;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.engine.file.JavaTemplateFillEngine;
import domi.argenticpptmaster.engine.file.JavaTemplateFillEngineTests;
import domi.argenticpptmaster.engine.python.PythonProjectWorkspace;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class PptTemplateFillCommandExecutorTests {

    @TempDir
    Path tempDir;

    private PptJobRepository repository;
    private PptMasterCommandExecutor commands;
    private PptWorkflowEvents events;
    private PptTemplateFillAnalysisReader analysisReader;
    private PptTemplateFillConcurrencyLimiter concurrencyLimiter;
    private PptTemplateFillCommandExecutor executor;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(PptJobRepository.class);
        commands = org.mockito.Mockito.mock(PptMasterCommandExecutor.class);
        events = org.mockito.Mockito.mock(PptWorkflowEvents.class);
        analysisReader = new PptTemplateFillAnalysisReader();
        concurrencyLimiter = new PptTemplateFillConcurrencyLimiter(
                PptMasterProperties.forTest(tempDir, tempDir));
        executor = new PptTemplateFillCommandExecutor(
                PptMasterProperties.forTest(tempDir, tempDir),
                repository,
                events,
                new PythonProjectWorkspace(commands),
                new JavaTemplateFillEngine(),
                analysisReader,
                concurrencyLimiter,
                new TemplateFillCapabilityIndexLoader(),
                new TemplateFillConstraintResolver(),
                new PptTemplateFillPlanStore(PptMasterProperties.forTest(tempDir, tempDir), new TemplateFillPlanValidator()),
                new TemplateFillOutputVerifier(),
                new TemplateFillLifecycleStore(PptMasterProperties.forTest(tempDir, tempDir)));
    }

    @Test
    void runsFixedNativePptxSequenceAndCompletesJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("job");
        Path templatePath = workspace.resolve("uploads/template/0-brand.pptx");
        Path sourcePath = workspace.resolve("uploads/content/0-source.md");
        Files.createDirectories(templatePath.getParent());
        Files.createDirectories(sourcePath.getParent());
        JavaTemplateFillEngineTests.writeTemplate(templatePath);
        Files.writeString(sourcePath, "# source");
        Path slideLibrary = workspace.resolve("analysis/template.slide_library.json");
        Files.createDirectories(slideLibrary.getParent());
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"), slideLibrary);
        String draft = """
                {
                  "schema": "template_fill_pptx_plan.v1",
                  "status": "draft",
                  "accepted_warnings": [],
                  "slides": [
                    {
                      "source_slide": 1,
                      "purpose": "cover",
                      "replacements": [
                        {"slot_id": "s01_sh1", "text": "模板填充集成方案"}
                      ],
                      "table_edits": [],
                      "chart_edits": [],
                      "transition": "fade"
                    }
                  ]
                }
                """;
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L, templatePath));
        job.addSource(new PptSourceFile("source.md", "text/markdown", 8L, sourcePath));
        PptTemplateFillPlanStore planStore = new PptTemplateFillPlanStore(
                PptMasterProperties.forTest(tempDir, tempDir), new TemplateFillPlanValidator());
        var metadata = planStore.storeDraftPlan(job, draft, slideLibrary);
        planStore.confirmCurrentDraft(job, "confirm-1", metadata.version(), metadata.digest());
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(commands.runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    String script = invocation.getArgument(0);
                    @SuppressWarnings("unchecked") List<String> args = invocation.getArgument(1);
                    if (script.endsWith("project_manager.py") && args.get(0).equals("init")) {
                        Path project = workspace.resolve("projects/demo_ppt169_20260720");
                        Files.createDirectories(project.resolve("analysis"));
                        Files.createDirectories(project.resolve("exports"));
                        Files.createDirectories(project.resolve("validation"));
                        return new PptMasterCommandExecutor.CommandResult(0,
                                "[OK] Project initialized: " + project, false);
                    }
                    return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
                });

        executor.execute(jobId, workspace.resolve("analysis/fill_plan.json"));

        assertThat(job.status()).isEqualTo(domi.argenticpptmaster.domain.PptJobStatus.COMPLETED);
        assertThat(job.exportPath()).isPresent();
        assertThat(job.lastCompletedNode()).contains(PptJobNode.OUTPUT_VALIDATED);
        assertThat(job.templateAnalysisReady()).isTrue();
        assertThat(job.readbackValidationStatus()).isIn("PASSED", "PASSED_WITH_WARNINGS");
        assertThat(job.nodeExecution(PptJobNode.TEMPLATE_ANALYZED).status()).isEqualTo(PptJobNodeStatus.COMPLETED);
        assertThat(job.nodeExecution(PptJobNode.FILL_PLAN_VALIDATED).status()).isEqualTo(PptJobNodeStatus.COMPLETED);
        // Only project_manager remains on Python; template-fill stages are Java authority.
        verify(commands, org.mockito.Mockito.atLeast(2))
                .runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList(), any(Duration.class));
        verify(commands, never()).runPythonScript(
                org.mockito.ArgumentMatchers.eq("skills/ppt-master/scripts/template_fill_pptx.py"),
                anyList(),
                any(Duration.class));
        ArgumentCaptor<PptJobEvent> eventsCaptor = ArgumentCaptor.forClass(PptJobEvent.class);
        verify(events, atLeast(4)).record(eq(job), eventsCaptor.capture());
        assertThat(eventsCaptor.getAllValues()).extracting(PptJobEvent::type)
                .contains(domi.argenticpptmaster.domain.PptJobEventType.TEMPLATE_FILL_STAGE_STARTED,
                        domi.argenticpptmaster.domain.PptJobEventType.TEMPLATE_FILL_STAGE_COMPLETED,
                        domi.argenticpptmaster.domain.PptJobEventType.EXPORT_READY);
    }

    @Test
    void stopsBeforeApplyWhenCheckPlanFails() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("job-fail");
        Path templatePath = workspace.resolve("uploads/template/0-brand.pptx");
        Files.createDirectories(templatePath.getParent());
        // Blank slide has no text slots; analyze + check-plan will fail the confirmed s01_sh1 replacement.
        writeBlankSlideTemplate(templatePath);
        Path slideLibrary = workspace.resolve("analysis/template.slide_library.json");
        Files.createDirectories(slideLibrary.getParent());
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"), slideLibrary);
        String draft = """
                {
                  "schema": "template_fill_pptx_plan.v1",
                  "status": "draft",
                  "accepted_warnings": [],
                  "slides": [
                    {
                      "source_slide": 1,
                      "purpose": "cover",
                      "replacements": [
                        {"slot_id": "s01_sh1", "text": "ok"}
                      ],
                      "table_edits": [],
                      "chart_edits": []
                    }
                  ]
                }
                """;
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L, templatePath));
        job.addSource(new PptSourceFile("source.md", "text/markdown", 8L,
                workspace.resolve("uploads/content/0-source.md")));
        Files.createDirectories(job.sourceFiles().get(0).storedPath().getParent());
        Files.writeString(job.sourceFiles().get(0).storedPath(), "# source");
        PptTemplateFillPlanStore planStore = new PptTemplateFillPlanStore(
                PptMasterProperties.forTest(tempDir, tempDir), new TemplateFillPlanValidator());
        var metadata = planStore.storeDraftPlan(job, draft, slideLibrary);
        planStore.confirmCurrentDraft(job, "confirm-1", metadata.version(), metadata.digest());
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        when(commands.runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    String script = invocation.getArgument(0);
                    @SuppressWarnings("unchecked") List<String> args = invocation.getArgument(1);
                    if (script.endsWith("project_manager.py") && args.get(0).equals("init")) {
                        Path project = workspace.resolve("projects/demo_ppt169_20260720");
                        Files.createDirectories(project.resolve("analysis"));
                        return new PptMasterCommandExecutor.CommandResult(0,
                                "[OK] Project initialized: " + project, false);
                    }
                    return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
                });

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executor.execute(jobId, workspace.resolve("analysis/fill_plan.json")))
                .isInstanceOf(PptTemplateFillExecutionException.class);
        assertThat(job.status()).isEqualTo(domi.argenticpptmaster.domain.PptJobStatus.FAILED);
        assertThat(job.lastCompletedNode()).contains(PptJobNode.FILL_PLAN_CONFIRMED);
        assertThat(job.lastFailureNode()).contains(PptJobNode.FILL_PLAN_VALIDATED);
    }

    private static void writeBlankSlideTemplate(Path path) throws Exception {
        Path extract = path.getParent().resolve("_blank_tpl");
        Files.createDirectories(extract.resolve("_rels"));
        Files.createDirectories(extract.resolve("ppt/slides/_rels"));
        Files.createDirectories(extract.resolve("ppt/_rels"));
        Files.createDirectories(extract.resolve("ppt/slideMasters"));
        Files.createDirectories(extract.resolve("ppt/theme"));
        Files.writeString(extract.resolve("[Content_Types].xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
                  <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
                  <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
                  <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
                </Types>
                """);
        Files.writeString(extract.resolve("_rels/.rels"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
                </Relationships>
                """);
        Files.writeString(extract.resolve("ppt/presentation.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:presentation xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                                xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
                  <p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst>
                </p:presentation>
                """);
        Files.writeString(extract.resolve("ppt/_rels/presentation.xml.rels"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>
                </Relationships>
                """);
        Files.writeString(extract.resolve("ppt/slides/slide1.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld><p:spTree>
                    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                    <p:grpSpPr/>
                  </p:spTree></p:cSld>
                </p:sld>
                """);
        Files.writeString(extract.resolve("ppt/slides/_rels/slide1.xml.rels"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>
                """);
        Files.writeString(extract.resolve("ppt/slideMasters/slideMaster1.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sldMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld><p:spTree>
                    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                    <p:grpSpPr/>
                  </p:spTree></p:cSld>
                  <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2"
                            accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6"
                            hlink="hlink" folHlink="folHlink"/>
                </p:sldMaster>
                """);
        Files.writeString(extract.resolve("ppt/theme/theme1.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office">
                  <a:themeElements>
                    <a:clrScheme name="Office">
                      <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>
                      <a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
                      <a:dk2><a:srgbClr val="1F497D"/></a:dk2>
                      <a:lt2><a:srgbClr val="EEECE1"/></a:lt2>
                      <a:accent1><a:srgbClr val="4F81BD"/></a:accent1>
                      <a:accent2><a:srgbClr val="C0504D"/></a:accent2>
                      <a:accent3><a:srgbClr val="9BBB59"/></a:accent3>
                      <a:accent4><a:srgbClr val="8064A2"/></a:accent4>
                      <a:accent5><a:srgbClr val="4BACC6"/></a:accent5>
                      <a:accent6><a:srgbClr val="F79646"/></a:accent6>
                      <a:hlink><a:srgbClr val="0000FF"/></a:hlink>
                      <a:folHlink><a:srgbClr val="800080"/></a:folHlink>
                    </a:clrScheme>
                    <a:fontScheme name="Office">
                      <a:majorFont><a:latin typeface="Calibri"/></a:majorFont>
                      <a:minorFont><a:latin typeface="Calibri"/></a:minorFont>
                    </a:fontScheme>
                    <a:fmtScheme name="Office">
                      <a:fillStyleLst><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:fillStyleLst>
                      <a:lnStyleLst><a:ln w="9525"><a:solidFill><a:srgbClr val="000000"/></a:solidFill></a:ln></a:lnStyleLst>
                      <a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>
                      <a:bgFillStyleLst><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:bgFillStyleLst>
                    </a:fmtScheme>
                  </a:themeElements>
                </a:theme>
                """);
        domi.argenticpptmaster.engine.ooxml.PptxPackage.zipDirectory(extract, path);
    }

    @Test
    void rejectsTemplatePathOutsideJobWorkspaceBeforeStartingProcess() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("job");
        Path outside = tempDir.resolve("outside.pptx");
        Files.writeString(outside, "template");
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L, outside));
        when(repository.findById(jobId)).thenReturn(Optional.of(job));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executor.execute(jobId, workspace.resolve("analysis/fill_plan.json")))
                .isInstanceOf(PptTemplateFillExecutionException.class)
                .hasMessageContaining("escapes job workspace");
        verify(commands, never()).runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList(), any(Duration.class));
    }

    @Test
    void rejectsDraftOrForcedPlanBeforeRunningNativeCommands() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("draft-job");
        Path templatePath = workspace.resolve("uploads/template/0-brand.pptx");
        Files.createDirectories(templatePath.getParent());
        Files.writeString(templatePath, "template");
        Files.createDirectories(workspace.resolve("analysis"));
        Files.writeString(workspace.resolve("analysis/fill_plan.json"), "{\"status\":\"draft\",\"slides\":[]}");
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L, templatePath));
        when(repository.findById(jobId)).thenReturn(Optional.of(job));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> executor.execute(jobId, workspace.resolve("analysis/fill_plan.json")))
                .isInstanceOf(PptTemplateFillExecutionException.class)
                .hasMessageContaining("confirmed");
        verify(commands, never()).runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList(), any(Duration.class));

        Files.writeString(workspace.resolve("analysis/fill_plan.json"),
                "{\"status\":\"confirmed\",\"slides\":[],\"note\":\"--force\"}");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> executor.execute(jobId, workspace.resolve("analysis/fill_plan.json")))
                .isInstanceOf(PptTemplateFillExecutionException.class)
                .hasMessageContaining("force");
        verify(commands, never()).runPythonScript(org.mockito.ArgumentMatchers.anyString(), anyList(), any(Duration.class));
    }

    @Test
    void nativeCommandsNeverIncludeForceSvgOrImageTooling() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path workspace = tempDir.resolve("args-job");
        Path templatePath = workspace.resolve("uploads/template/0-brand.pptx");
        Path sourcePath = workspace.resolve("uploads/content/0-source.md");
        Files.createDirectories(templatePath.getParent());
        Files.createDirectories(sourcePath.getParent());
        JavaTemplateFillEngineTests.writeTemplate(templatePath);
        Files.writeString(sourcePath, "# source");
        Path slideLibrary = workspace.resolve("analysis/template.slide_library.json");
        Files.createDirectories(slideLibrary.getParent());
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"), slideLibrary);
        String draft = """
                {
                  "schema": "template_fill_pptx_plan.v1",
                  "status": "draft",
                  "accepted_warnings": [],
                  "slides": [
                    {
                      "source_slide": 1,
                      "purpose": "cover",
                      "replacements": [
                        {"slot_id": "s01_sh1", "text": "模板填充集成方案"}
                      ],
                      "table_edits": [],
                      "chart_edits": [],
                      "transition": "fade"
                    }
                  ]
                }
                """;
        PptJob job = new PptJob(jobId, "demo", "ppt169", "fill", PptWorkflowMode.TEMPLATE_FILL, workspace);
        job.setTemplate(new PptTemplateFile("brand.pptx", "application/octet-stream", 8L, templatePath));
        job.addSource(new PptSourceFile("source.md", "text/markdown", 8L, sourcePath));
        PptTemplateFillPlanStore planStore = new PptTemplateFillPlanStore(
                PptMasterProperties.forTest(tempDir, tempDir), new TemplateFillPlanValidator());
        var metadata = planStore.storeDraftPlan(job, draft, slideLibrary);
        planStore.confirmCurrentDraft(job, "confirm-1", metadata.version(), metadata.digest());
        when(repository.findById(jobId)).thenReturn(Optional.of(job));
        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        when(commands.runPythonScript(scriptCaptor.capture(), argsCaptor.capture(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    String script = invocation.getArgument(0);
                    @SuppressWarnings("unchecked") List<String> args = invocation.getArgument(1);
                    if (script.endsWith("project_manager.py") && args.get(0).equals("init")) {
                        Path project = workspace.resolve("projects/demo_ppt169_20260720");
                        Files.createDirectories(project.resolve("analysis"));
                        Files.createDirectories(project.resolve("exports"));
                        Files.createDirectories(project.resolve("validation"));
                        return new PptMasterCommandExecutor.CommandResult(0,
                                "[OK] Project initialized: " + project, false);
                    }
                    return new PptMasterCommandExecutor.CommandResult(0, "ok", false);
                });

        executor.execute(jobId, workspace.resolve("analysis/fill_plan.json"));

        assertThat(scriptCaptor.getAllValues())
                .allSatisfy(script -> assertThat(script).doesNotContain("svg").doesNotContain("image_gen"));
        assertThat(argsCaptor.getAllValues())
                .allSatisfy(args -> assertThat(args).noneMatch(arg -> arg.contains("--force")));
        assertThat(argsCaptor.getAllValues().stream().map(args -> args.get(0)).toList())
                .contains("init", "import-sources")
                .doesNotContain("analyze", "check-plan", "apply", "validate");
    }
}
