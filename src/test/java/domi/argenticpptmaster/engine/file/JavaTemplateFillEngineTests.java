package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.ooxml.PptxPackage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class JavaTemplateFillEngineTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void analyzeWritesSlideLibrary() throws Exception {
        Path template = writeTemplate(tempDir.resolve("template.pptx"));
        Path library = tempDir.resolve("lib.json");

        EngineResult result = new JavaTemplateFillEngine().analyze(template, library, Duration.ofSeconds(30));

        assertThat(result.successful()).isTrue();
        JsonNode root = MAPPER.readTree(library.toFile());
        assertThat(root.path("schema").asText()).isEqualTo("template_fill_pptx_library.v1");
        assertThat(root.path("slide_count").asInt()).isEqualTo(1);
        assertThat(root.path("slides").get(0).path("slots").get(0).path("slot_id").asText())
                .isEqualTo("s01_sh1");
    }

    @Test
    void checkPlanReportsOkForMatchingReplacement() throws Exception {
        Path template = writeTemplate(tempDir.resolve("template.pptx"));
        Path library = tempDir.resolve("lib.json");
        new JavaTemplateFillEngine().analyze(template, library, Duration.ofSeconds(30));
        Path plan = tempDir.resolve("plan.json");
        Files.writeString(
                plan,
                """
                {
                  "status": "confirmed",
                  "slides": [
                    {
                      "source_slide": 1,
                      "replacements": [{"slot_id": "s01_sh1", "text": "Filled"}]
                    }
                  ]
                }
                """,
                StandardCharsets.UTF_8);
        Path report = tempDir.resolve("check.json");

        EngineResult result =
                new JavaTemplateFillEngine().checkPlan(library, plan, report, Duration.ofSeconds(30));

        assertThat(result.successful()).isTrue();
        assertThat(MAPPER.readTree(report.toFile()).path("summary").path("error").asInt()).isZero();
    }

    @Test
    void applyNotesTextTableAndTransition() throws Exception {
        Path template = writeTemplateWithTable(tempDir.resolve("template.pptx"));
        Path plan = tempDir.resolve("plan.json");
        Files.writeString(
                plan,
                """
                {
                  "status": "confirmed",
                  "slides": [
                    {
                      "source_slide": 1,
                      "notes": "Speaker **line**",
                      "transition": "fade",
                      "replacements": [
                        {"shape_name": "Title", "text": "Filled"}
                      ],
                      "table_edits": [
                        {
                          "table_id": "s01_tbl3",
                          "cells": [{"row": 0, "col": 0, "text": "CellA"}]
                        }
                      ]
                    }
                  ]
                }
                """,
                StandardCharsets.UTF_8);
        Path export = tempDir.resolve("export.pptx");

        EngineResult result = new JavaTemplateFillEngine().apply(template, plan, export, Duration.ofSeconds(30));

        assertThat(result.successful()).isTrue();
        Path actual = findExport(export);
        try (ZipFile zip = new ZipFile(actual.toFile())) {
            String slideName = zip.stream()
                    .map(e -> e.getName())
                    .filter(name -> name.matches("ppt/slides/slide\\d+\\.xml"))
                    .max(Comparator.comparingInt(name -> Integer.parseInt(name.replaceAll("\\D", ""))))
                    .orElseThrow();
            String slide = new String(zip.getInputStream(zip.getEntry(slideName)).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(slide).contains("Filled");
            assertThat(slide).contains("CellA");
            assertThat(slide).contains("<p:transition");
            assertThat(slide).contains("<p:fade");
            String notesName = "ppt/notesSlides/notesSlide"
                    + slideName.replaceAll("\\D", "")
                    + ".xml";
            assertThat(zip.getEntry(notesName)).isNotNull();
            String notes = new String(
                    zip.getInputStream(zip.getEntry(notesName)).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(notes).contains("Speaker line");
        }
    }

    @Test
    void validatePassesForAppliedPlan() throws Exception {
        Path template = writeTemplate(tempDir.resolve("template.pptx"));
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("analysis"));
        Files.createDirectories(project.resolve("exports"));
        Files.createDirectories(project.resolve("validation"));
        Path library = project.resolve("analysis/template.slide_library.json");
        new JavaTemplateFillEngine().analyze(template, library, Duration.ofSeconds(30));
        Path plan = project.resolve("analysis/fill_plan.json");
        Files.writeString(
                plan,
                """
                {
                  "status": "confirmed",
                  "slides": [
                    {
                      "source_slide": 1,
                      "replacements": [{"slot_id": "s01_sh1", "text": "Filled Title"}]
                    }
                  ]
                }
                """,
                StandardCharsets.UTF_8);
        Path export = project.resolve("exports/template-fill.pptx");
        assertThat(new JavaTemplateFillEngine().apply(template, plan, export, Duration.ofSeconds(30)).successful())
                .isTrue();

        EngineResult result = new JavaTemplateFillEngine().validate(project, Duration.ofSeconds(30));

        assertThat(result.successful()).isTrue();
        assertThat(Files.isRegularFile(project.resolve("validation/validate_report.json"))).isTrue();
    }

    private static Path findExport(Path requested) throws Exception {
        if (Files.isRegularFile(requested)) {
            return requested;
        }
        // apply() may write a timestamped sibling (export_yyyyMMdd_HHmmss.pptx); do not
        // pick unrelated pptx files that share the temp directory (e.g. template.pptx).
        String stem = requested.getFileName().toString().replaceFirst("(?i)\\.pptx$", "");
        try (Stream<Path> stream = Files.list(requested.getParent())) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.regionMatches(true, 0, stem, 0, stem.length())
                                && name.toLowerCase().endsWith(".pptx");
                    })
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow();
        }
    }

    public static Path writeTemplate(Path path) throws Exception {
        return writeTemplateInternal(path, false);
    }

    public static Path writeTemplateWithTable(Path path) throws Exception {
        return writeTemplateInternal(path, true);
    }

    private static Path writeTemplateInternal(Path path, boolean withTable) throws Exception {
        Path extract = path.getParent().resolve("_tpl_extract_" + path.getFileName().toString().replace('.', '_'));
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
                  <p:sldSz cx="12192000" cy="6858000"/>
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
        String tableXml = withTable
                ? """
                    <p:graphicFrame>
                      <p:nvGraphicFramePr>
                        <p:cNvPr id="3" name="Table 3"/>
                        <p:cNvGraphicFramePr/><p:nvPr/>
                      </p:nvGraphicFramePr>
                      <p:xfrm><a:off x="0" y="0"/><a:ext cx="100" cy="100"/></p:xfrm>
                      <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table">
                        <a:tbl>
                          <a:tr h="100"><a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>A</a:t></a:r></a:p></a:txBody></a:tc>
                            <a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>B</a:t></a:r></a:p></a:txBody></a:tc></a:tr>
                        </a:tbl>
                      </a:graphicData></a:graphic>
                    </p:graphicFrame>
                    """
                : "";
        Files.writeString(
                extract.resolve("ppt/slides/slide1.xml"),
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld><p:spTree>
                    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                    <p:grpSpPr/>
                    <p:sp><p:nvSpPr><p:cNvPr id="1" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
                      <p:spPr><a:xfrm><a:off x="914400" y="457200"/><a:ext cx="9144000" cy="914400"/></a:xfrm></p:spPr>
                      <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr sz="3600"/><a:t>Hello</a:t></a:r></a:p></p:txBody>
                    </p:sp>
                    %s
                  </p:spTree></p:cSld>
                </p:sld>
                """.formatted(tableXml));
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
        return path;
    }
}
