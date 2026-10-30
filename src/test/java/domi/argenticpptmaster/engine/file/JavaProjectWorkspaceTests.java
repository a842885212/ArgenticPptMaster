package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.engine.EngineResult;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaProjectWorkspaceTests {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-03-15T08:00:00+08:00"), ZONE);

    @TempDir
    Path tempDir;

    private JavaProjectWorkspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new JavaProjectWorkspace(FIXED_CLOCK);
    }

    @Test
    void initCreatesPpt169ProjectWithOkLineAndDirectories() throws Exception {
        Path projectsDir = tempDir.resolve("projects");

        EngineResult result = workspace.init("demo", "ppt169", projectsDir, Duration.ofSeconds(30));

        assertThat(result.successful()).isTrue();
        Path project = projectsDir.resolve("demo_ppt169_20260315");
        assertThat(project).isDirectory();
        assertThat(result.output()).contains("Project created: " + project);
        assertThat(result.output()).contains("Canvas: PPT 16:9 (1280×720)");
        assertThat(result.output()).contains("[OK] Project initialized: " + project);
        assertThat(result.output()).contains("Next:");
        assertThat(Files.readString(project.resolve("README.md"), StandardCharsets.UTF_8))
                .contains("- Canvas format: ppt169")
                .contains("- Created: 20260315");
        for (String dir :
                List.of("svg_output", "sources", "validation", "images", "exports")) {
            assertThat(project.resolve(dir)).isDirectory();
        }
        String log = Files.readString(project.resolve("validation/workflow.log"), StandardCharsets.UTF_8);
        assertThat(log).contains("Project initialized: profile=default; canvas=ppt169; path=" + project);
    }

    @Test
    void initAcceptsXhsAlias() {
        Path projectsDir = tempDir.resolve("projects");

        EngineResult result = workspace.init("deck", "xhs", projectsDir, Duration.ofSeconds(5));

        assertThat(result.successful()).isTrue();
        assertThat(projectsDir.resolve("deck_xiaohongshu_20260315")).isDirectory();
        assertThat(result.output()).contains("Canvas: 小红书 (1242×1660)");
    }

    @Test
    void initRejectsIllegalProjectName() {
        EngineResult result = workspace.init("../evil", "ppt169", tempDir, Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).isEqualTo("[ERROR] Project name must be a single, non-absolute path component");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void initRejectsExistingDirectory() throws Exception {
        Path projectsDir = tempDir.resolve("projects");
        Files.createDirectories(projectsDir.resolve("demo_ppt169_20260315"));

        EngineResult result = workspace.init("demo", "ppt169", projectsDir, Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("Project directory already exists:");
    }

    @Test
    void initRejectsUnknownFormat() {
        EngineResult result = workspace.init("demo", "not-a-format", tempDir, Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("Unsupported canvas format: not-a-format");
        assertThat(result.output()).contains("common alias: xhs -> xiaohongshu");
        assertThat(result.output()).contains("a4, banner, moments, ppt169, ppt43, story, wechat, xiaohongshu");
    }

    @Test
    void importSourcesCopiesMarkdownIntoSources() throws Exception {
        Path project = initProject("mdproj", "ppt169");
        Path source = tempDir.resolve("article.md");
        Files.writeString(source, "# Title\n\nBody\n", StandardCharsets.UTF_8);

        EngineResult result = workspace.importSources(project, List.of(source), true, Duration.ofSeconds(10));

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).contains("[OK] Imported sources into: " + project);
        assertThat(result.output()).contains("Normalized markdown:");
        Path imported = project.resolve("sources/article.md");
        assertThat(imported).isRegularFile();
        assertThat(Files.readString(imported, StandardCharsets.UTF_8)).contains("# Title");
        assertThat(source).isRegularFile();
    }

    @Test
    void importSourcesCopiesPngToImagesAndSources() throws Exception {
        Path project = initProject("imgproj", "ppt169");
        Path source = tempDir.resolve("photo.png");
        Files.write(source, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});

        EngineResult result = workspace.importSources(project, List.of(source), true, Duration.ofSeconds(10));

        assertThat(result.successful()).isTrue();
        assertThat(project.resolve("sources/photo.png")).isRegularFile();
        assertThat(project.resolve("images/photo.png")).isRegularFile();
        assertThat(result.output()).contains("Runtime image copies:");
    }

    @Test
    void importSourcesRecordsMissingPathInSkipped() throws Exception {
        Path project = initProject("skipproj", "ppt169");
        Path missing = tempDir.resolve("missing.md");

        EngineResult result = workspace.importSources(project, List.of(missing), true, Duration.ofSeconds(10));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("Skipped:");
        assertThat(result.output()).contains(missing + ": path not found");
    }

    @Test
    void importSourcesConvertsPptxToMarkdownAndIntake() throws Exception {
        Path project = initProject("pptxproj", "ppt169");
        Path source = tempDir.resolve("deck.pptx");
        writeMinimalPptx(source);

        EngineResult result = workspace.importSources(project, List.of(source), true, Duration.ofSeconds(30));

        assertThat(result.successful()).isTrue();
        assertThat(project.resolve("sources/deck.pptx")).isRegularFile();
        Path markdown = project.resolve("sources/deck.md");
        assertThat(markdown).isRegularFile();
        assertThat(Files.readString(markdown, StandardCharsets.UTF_8))
                .contains("# deck")
                .contains("## Slide 1")
                .contains("Hello");
        assertThat(project.resolve("analysis/deck.identity.json")).isRegularFile();
        assertThat(project.resolve("analysis/source_profile.json")).isRegularFile();
        assertThat(result.output()).contains("Normalized markdown:").contains("Analysis artifacts:");
    }

    @Test
    void importSourcesArchivesLegacyPptWithoutConversion() throws Exception {
        Path project = initProject("pptproj", "ppt169");
        Path source = tempDir.resolve("legacy.ppt");
        Files.write(source, new byte[] {0x00, 0x01});

        EngineResult result = workspace.importSources(project, List.of(source), true, Duration.ofSeconds(10));

        assertThat(result.successful()).isTrue();
        assertThat(project.resolve("sources/legacy.ppt")).isRegularFile();
        assertThat(project.resolve("sources/legacy.md")).doesNotExist();
        assertThat(result.output()).contains("legacy .ppt is not converted");
    }

    @Test
    void importSourcesFailsClosedOnCorruptPptx() throws Exception {
        Path project = initProject("badpptx", "ppt169");
        Path source = tempDir.resolve("broken.pptx");
        Files.write(source, new byte[] {0x50, 0x4B, 0x03, 0x04});

        EngineResult result = workspace.importSources(project, List.of(source), true, Duration.ofSeconds(10));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).startsWith("[ERROR]");
    }

    @Test
    void infoIsNotImplemented() throws Exception {
        Path project = initProject("infoproj", "ppt169");

        EngineResult result = workspace.info(project, Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).isEqualTo("[ERROR] info is still provided by the Python project workspace");
    }

    @Test
    void importSourcesConvertsExcelToMarkdown() throws Exception {
        Path project = initProject("xlsxproj", "ppt169");
        Path source = tempDir.resolve("table.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                OutputStream out = Files.newOutputStream(source)) {
            Sheet sheet = workbook.createSheet("Data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Value");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("alpha");
            row.createCell(1).setCellValue(42);
            workbook.write(out);
        }

        EngineResult result = workspace.importSources(project, List.of(source), true, Duration.ofSeconds(20));

        assertThat(result.successful()).isTrue();
        Path markdown = project.resolve("sources/table.md");
        assertThat(markdown).isRegularFile();
        assertThat(Files.readString(markdown, StandardCharsets.UTF_8))
                .contains("## Sheet: Data")
                .contains("| Name | Value |")
                .contains("alpha");
    }

    @Test
    void importSourcesFailsClosedOnCorruptExcel() throws Exception {
        Path project = initProject("badxlsx", "ppt169");
        Path source = tempDir.resolve("broken.xlsx");
        Files.writeString(source, "not-an-xlsx", StandardCharsets.UTF_8);

        EngineResult result = workspace.importSources(project, List.of(source), true, Duration.ofSeconds(10));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).startsWith("[ERROR]").contains("Excel conversion failed");
    }

    @Test
    void importSourcesConvertsDocxAndHtml() throws Exception {
        Path project = initProject("docproj", "ppt169");
        Path docx = tempDir.resolve("note.docx");
        try (XWPFDocument document = new XWPFDocument();
                OutputStream out = Files.newOutputStream(docx)) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText("Hello from docx");
            document.write(out);
        }
        Path html = tempDir.resolve("page.html");
        Files.writeString(html, "<html><head><title>Page</title></head><body><p>Hello from html</p></body></html>");

        EngineResult result = workspace.importSources(project, List.of(docx, html), true, Duration.ofSeconds(20));

        assertThat(result.successful()).isTrue();
        assertThat(Files.readString(project.resolve("sources/note.md"), StandardCharsets.UTF_8))
                .contains("Hello from docx");
        assertThat(Files.readString(project.resolve("sources/page.md"), StandardCharsets.UTF_8))
                .contains("Hello from html");
    }

    private Path initProject(String name, String format) {
        Path projectsDir = tempDir.resolve("projects-" + name);
        EngineResult init = workspace.init(name, format, projectsDir, Duration.ofSeconds(30));
        assertThat(init.successful()).isTrue();
        return projectsDir.resolve(name + "_" + format + "_20260315");
    }

    private static void writeMinimalPptx(Path path) throws Exception {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put(
                "[Content_Types].xml",
                """
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
        parts.put(
                "_rels/.rels",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
                </Relationships>
                """);
        parts.put(
                "ppt/presentation.xml",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                                xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:sldMasterIdLst>
                    <p:sldMasterId id="2147483648" r:id="rId1"/>
                  </p:sldMasterIdLst>
                  <p:sldIdLst>
                    <p:sldId id="256" r:id="rId2"/>
                  </p:sldIdLst>
                </p:presentation>
                """);
        parts.put(
                "ppt/_rels/presentation.xml.rels",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>
                </Relationships>
                """);
        parts.put(
                "ppt/slides/slide1.xml",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                       xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld><p:spTree>
                    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                    <p:grpSpPr/>
                    <p:sp>
                      <p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
                      <p:spPr/>
                      <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Hello</a:t></a:r></a:p></p:txBody>
                    </p:sp>
                  </p:spTree></p:cSld>
                </p:sld>
                """);
        parts.put(
                "ppt/slides/_rels/slide1.xml.rels",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                </Relationships>
                """);
        parts.put(
                "ppt/slideMasters/slideMaster1.xml",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                             xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                             xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld><p:spTree>
                    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                    <p:grpSpPr/>
                  </p:spTree></p:cSld>
                  <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2"
                            accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6"
                            hlink="hlink" folHlink="folHlink"/>
                </p:sldMaster>
                """);
        parts.put(
                "ppt/theme/theme1.xml",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office Theme">
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
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
