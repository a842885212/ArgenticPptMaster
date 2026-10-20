package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.ooxml.PptxPackage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaNativeEnhanceEngineTests {

    @TempDir
    Path tempDir;

    @Test
    void applyNotesOnlyWritesEnhancedPptx() throws Exception {
        Path project = createNotesOnlyProject(tempDir.resolve("project"));
        JavaNativeEnhanceEngine engine = new JavaNativeEnhanceEngine();

        EngineResult result = engine.apply(
                List.of("apply", project.toString(), "--overwrite", "--force"), Duration.ofSeconds(30));

        assertThat(result.successful()).isTrue();
        Path output = project.resolve("exports/source_enhanced.pptx");
        assertThat(output).exists();
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertThat(zip.getEntry("ppt/notesSlides/notesSlide1.xml")).isNotNull();
            assertThat(new String(zip.getInputStream(zip.getEntry("ppt/notesSlides/notesSlide1.xml")).readAllBytes(),
                    StandardCharsets.UTF_8)).contains("Spoken note");
        }
    }

    @Test
    void validateNotesOnlyReportsMissingNotes() throws Exception {
        Path project = createNotesOnlyProject(tempDir.resolve("project-missing"));
        Files.delete(project.resolve("notes/001.md"));
        JavaNativeEnhanceEngine engine = new JavaNativeEnhanceEngine();

        EngineResult result = engine.validate(project, Duration.ofSeconds(10));

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("missing notes");
    }

    @Test
    void initIsSkipped() {
        EngineResult result = new JavaNativeEnhanceEngine()
                .init(tempDir.resolve("x.pptx"), "demo", tempDir, tempDir, Duration.ZERO);
        assertThat(JavaNativeEnhanceEngine.isSkip(result)).isTrue();
    }

    @Test
    void optionalPinPythonGoldenIsSkippedWithoutVenv() {
        Path pin = Path.of("/tmp/ppt-master-cc535363");
        Path python = Path.of("/media/zhang/E/venvs/ppt-master/bin/python");
        assumeTrue(Files.isDirectory(pin) && Files.isExecutable(python), "pin+python unavailable");
        // Environment present: smoke that notes script still exists; do not fail CI without venv.
        assertThat(pin.resolve("skills/ppt-master/scripts/native_enhance_pptx.py")).exists();
    }

    private static Path createNotesOnlyProject(Path project) throws Exception {
        Files.createDirectories(project.resolve("sources"));
        Files.createDirectories(project.resolve("notes"));
        Files.createDirectories(project.resolve("audio"));
        Files.createDirectories(project.resolve("exports"));
        Files.createDirectories(project.resolve("analysis"));
        Files.createDirectories(project.resolve("validation"));

        Path source = project.resolve("sources/source.pptx");
        writeOneSlidePptx(source);
        Files.writeString(project.resolve("notes/001.md"), "Spoken note\n", StandardCharsets.UTF_8);
        Files.writeString(
                project.resolve("project.json"),
                """
                {
                  "schema": "native_pptx_enhancement_project.v1",
                  "source_pptx": "sources/source.pptx",
                  "notes_dir": "notes",
                  "audio_dir": "audio",
                  "exports_dir": "exports",
                  "slide_count": 1
                }
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                project.resolve("analysis/enhancement_plan.json"),
                """
                {
                  "status": "confirmed",
                  "modules": {
                    "notes": {"enabled": true},
                    "audio": {"enabled": false},
                    "timings": {"enabled": false},
                    "transitions": {"enabled": false}
                  }
                }
                """,
                StandardCharsets.UTF_8);
        return project;
    }

    private static void writeOneSlidePptx(Path path) throws Exception {
        // Reuse package helper via extract/zip from test fixture path pattern.
        Path extract = path.getParent().resolve("_pptx_build");
        Files.createDirectories(extract.resolve("_rels"));
        Files.createDirectories(extract.resolve("ppt/slides/_rels"));
        Files.createDirectories(extract.resolve("ppt/_rels"));
        Files.createDirectories(extract.resolve("ppt/slideMasters"));
        Files.createDirectories(extract.resolve("ppt/theme"));
        Files.writeString(
                extract.resolve("[Content_Types].xml"),
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
        Files.writeString(
                extract.resolve("_rels/.rels"),
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
                </Relationships>
                """);
        Files.writeString(
                extract.resolve("ppt/presentation.xml"),
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:presentation xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                                xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
                  <p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst>
                </p:presentation>
                """);
        Files.writeString(
                extract.resolve("ppt/_rels/presentation.xml.rels"),
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>
                </Relationships>
                """);
        Files.writeString(
                extract.resolve("ppt/slides/slide1.xml"),
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld><p:spTree>
                    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                    <p:grpSpPr/>
                    <p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
                      <p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Hello</a:t></a:r></a:p></p:txBody>
                    </p:sp>
                  </p:spTree></p:cSld>
                </p:sld>
                """);
        Files.writeString(
                extract.resolve("ppt/slides/_rels/slide1.xml.rels"),
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                </Relationships>
                """);
        Files.writeString(
                extract.resolve("ppt/slideMasters/slideMaster1.xml"),
                """
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
        Files.writeString(
                extract.resolve("ppt/theme/theme1.xml"),
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
        PptxPackage.zipDirectory(extract, path);
    }
}
