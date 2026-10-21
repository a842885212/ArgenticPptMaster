// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgtopptx.pkg;

import domi.argenticpptmaster.engine.file.svgtopptx.dims.Emu;
import domi.argenticpptmaster.engine.file.svgtopptx.drawingml.ConvertContext;
import domi.argenticpptmaster.engine.file.svgtopptx.drawingml.SvgSlideConverter;
import domi.argenticpptmaster.engine.file.svgtopptx.lock.SpecLock;
import domi.argenticpptmaster.engine.ooxml.PptxPackage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a flat-structure PPTX package with JDK ZIP (no Apache POI DrawingML writer).
 *
 * <p>Writes slide XML from {@link SvgSlideConverter}, theme from {@code spec_lock},
 * notes when present, and the default fade page transition matching pin CLI defaults.</p>
 */
public final class PptxFlatPackageWriter {

    private PptxFlatPackageWriter() {}

    public static Path write(
            Path project,
            SpecLock.Parsed lock,
            List<SvgSlideConverter.SlideConversion> slides,
            List<Path> svgFiles,
            Map<String, String> notesByStem,
            Path outputPptx)
            throws IOException {
        Path extractDir = Files.createTempDirectory(project.resolve("exports"), "java-svg2pptx-");
        try {
            double widthPx = slides.isEmpty() ? 1280 : slides.get(0).canvasWidthPx();
            double heightPx = slides.isEmpty() ? 720 : slides.get(0).canvasHeightPx();
            int widthEmu = Emu.pxToEmu(widthPx);
            int heightEmu = Emu.pxToEmu(heightPx);

            Files.createDirectories(extractDir.resolve("ppt/slides/_rels"));
            Files.createDirectories(extractDir.resolve("ppt/slideLayouts/_rels"));
            Files.createDirectories(extractDir.resolve("ppt/slideMasters/_rels"));
            Files.createDirectories(extractDir.resolve("ppt/_rels"));
            Files.createDirectories(extractDir.resolve("ppt/theme"));
            Files.createDirectories(extractDir.resolve("ppt/notesSlides/_rels"));
            Files.createDirectories(extractDir.resolve("ppt/notesMasters/_rels"));
            Files.createDirectories(extractDir.resolve("ppt/media"));
            Files.createDirectories(extractDir.resolve("_rels"));
            Files.createDirectories(extractDir.resolve("docProps"));

            writeTheme(extractDir, lock, project.getFileName().toString());
            writeMasterAndLayout(extractDir, widthEmu, heightEmu);
            writeNotesMaster(extractDir);

            List<Boolean> hasNotes = new ArrayList<>();
            for (int i = 0; i < slides.size(); i++) {
                SvgSlideConverter.SlideConversion slide = slides.get(i);
                int slideNum = i + 1;
                String stem = svgFiles.get(i).getFileName().toString().replaceFirst("\\.svg$", "");
                String notes = notesByStem.get(stem);
                boolean notesPresent = notes != null && !notes.isBlank();
                hasNotes.add(notesPresent);

                String slideXml = injectTransition(slide.slideXml());
                Files.writeString(
                        extractDir.resolve("ppt/slides/slide" + slideNum + ".xml"),
                        slideXml,
                        StandardCharsets.UTF_8);

                StringBuilder rels = new StringBuilder();
                rels.append("""
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
                        """);
                int nextRel = 2;
                Map<String, String> relIdRemap = new LinkedHashMap<>();
                for (ConvertContext.RelEntry entry : slide.relEntries()) {
                    String newId = "rId" + nextRel++;
                    relIdRemap.put(entry.relId(), newId);
                    rels.append("<Relationship Id=\"")
                            .append(newId)
                            .append("\" Type=\"")
                            .append(entry.type())
                            .append("\" Target=\"")
                            .append(entry.target())
                            .append("\"/>\n");
                }
                if (notesPresent) {
                    rels.append("<Relationship Id=\"rId")
                            .append(nextRel)
                            .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide\" Target=\"../notesSlides/notesSlide")
                            .append(slideNum)
                            .append(".xml\"/>\n");
                    writeNotesSlide(extractDir, slideNum, notes, lock.primaryLanguage());
                }
                rels.append("</Relationships>\n");
                String relsXml = rels.toString();
                // Remap rIds inside slide if converter used provisional ids
                String remappedSlide = slideXml;
                for (Map.Entry<String, String> remap : relIdRemap.entrySet()) {
                    if (!remap.getKey().equals(remap.getValue())) {
                        remappedSlide = remappedSlide.replace("\"" + remap.getKey() + "\"", "\"" + remap.getValue() + "\"");
                    }
                }
                if (!relIdRemap.isEmpty()) {
                    remappedSlide = injectTransition(slides.get(i).slideXml());
                    for (Map.Entry<String, String> remap : relIdRemap.entrySet()) {
                        remappedSlide = remappedSlide.replace("r:embed=\"" + remap.getKey() + "\"", "r:embed=\"" + remap.getValue() + "\"");
                    }
                    Files.writeString(
                            extractDir.resolve("ppt/slides/slide" + slideNum + ".xml"),
                            remappedSlide,
                            StandardCharsets.UTF_8);
                }
                Files.writeString(
                        extractDir.resolve("ppt/slides/_rels/slide" + slideNum + ".xml.rels"),
                        relsXml,
                        StandardCharsets.UTF_8);

                for (Map.Entry<String, byte[]> media : slide.mediaFiles().entrySet()) {
                    Files.write(extractDir.resolve("ppt/media").resolve(media.getKey()), media.getValue());
                }
                for (Map.Entry<String, byte[]> part : slide.packageFiles().entrySet()) {
                    Path target = extractDir.resolve(part.getKey());
                    Files.createDirectories(target.getParent());
                    Files.write(target, part.getValue());
                }
            }

            writePresentation(extractDir, slides.size(), widthEmu, heightEmu, hasNotes);
            writeContentTypes(extractDir, slides.size(), hasNotes, slides);
            writePackageRels(extractDir);
            writeDocProps(extractDir, project.getFileName().toString());

            Files.createDirectories(outputPptx.getParent());
            PptxPackage.zipDirectory(extractDir, outputPptx);
            return outputPptx;
        } finally {
            deleteRecursively(extractDir);
        }
    }

    private static String injectTransition(String slideXml) {
        if (slideXml.contains("<p:transition")) {
            return slideXml;
        }
        String transition = """
                  <p:transition p14:dur="400" xmlns:p14="http://schemas.microsoft.com/office/powerpoint/2010/main">
                    <p:fade/>
                  </p:transition>
                """;
        int idx = slideXml.lastIndexOf("</p:sld>");
        if (idx < 0) {
            return slideXml;
        }
        return slideXml.substring(0, idx) + "\n" + transition + slideXml.substring(idx);
    }

    private static void writeTheme(Path extractDir, SpecLock.Parsed lock, String name) throws IOException {
        SpecLock.ThemeColorSpec colors = lock.colors();
        SpecLock.ThemeFontSpec fonts = lock.fonts();
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="%s">
                <a:themeElements>
                <a:clrScheme name="%s">
                <a:dk1><a:srgbClr val="%s"/></a:dk1>
                <a:lt1><a:srgbClr val="%s"/></a:lt1>
                <a:dk2><a:srgbClr val="%s"/></a:dk2>
                <a:lt2><a:srgbClr val="%s"/></a:lt2>
                <a:accent1><a:srgbClr val="%s"/></a:accent1>
                <a:accent2><a:srgbClr val="%s"/></a:accent2>
                <a:accent3><a:srgbClr val="%s"/></a:accent3>
                <a:accent4><a:srgbClr val="%s"/></a:accent4>
                <a:accent5><a:srgbClr val="%s"/></a:accent5>
                <a:accent6><a:srgbClr val="%s"/></a:accent6>
                <a:hlink><a:srgbClr val="%s"/></a:hlink>
                <a:folHlink><a:srgbClr val="%s"/></a:folHlink>
                </a:clrScheme>
                <a:fontScheme name="%s">
                <a:majorFont>
                <a:latin typeface="%s"/><a:ea typeface="%s"/><a:cs typeface="%s"/>
                </a:majorFont>
                <a:minorFont>
                <a:latin typeface="%s"/><a:ea typeface="%s"/><a:cs typeface="%s"/>
                </a:minorFont>
                </a:fontScheme>
                <a:fmtScheme name="%s">
                <a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
                <a:gradFill rotWithShape="1"><a:gsLst>
                <a:gs pos="0"><a:schemeClr val="phClr"><a:tint val="50000"/><a:satMod val="300000"/></a:schemeClr></a:gs>
                <a:gs pos="35000"><a:schemeClr val="phClr"><a:tint val="37000"/><a:satMod val="300000"/></a:schemeClr></a:gs>
                <a:gs pos="100000"><a:schemeClr val="phClr"><a:tint val="15000"/><a:satMod val="350000"/></a:schemeClr></a:gs>
                </a:gsLst><a:lin ang="16200000" scaled="1"/></a:gradFill>
                <a:gradFill rotWithShape="1"><a:gsLst>
                <a:gs pos="0"><a:schemeClr val="phClr"><a:tint val="100000"/><a:shade val="100000"/><a:satMod val="130000"/></a:schemeClr></a:gs>
                <a:gs pos="100000"><a:schemeClr val="phClr"><a:tint val="50000"/><a:shade val="100000"/><a:satMod val="350000"/></a:schemeClr></a:gs>
                </a:gsLst><a:lin ang="16200000" scaled="0"/></a:gradFill>
                </a:fillStyleLst>
                <a:lnStyleLst>
                <a:ln w="9525" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>
                <a:ln w="25400" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>
                <a:ln w="38100" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>
                </a:lnStyleLst>
                <a:effectStyleLst>
                <a:effectStyle><a:effectLst/></a:effectStyle>
                <a:effectStyle><a:effectLst/></a:effectStyle>
                <a:effectStyle><a:effectLst/></a:effectStyle>
                </a:effectStyleLst>
                <a:bgFillStyleLst>
                <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
                <a:gradFill rotWithShape="1"><a:gsLst>
                <a:gs pos="0"><a:schemeClr val="phClr"><a:tint val="40000"/><a:satMod val="350000"/></a:schemeClr></a:gs>
                <a:gs pos="100000"><a:schemeClr val="phClr"><a:tint val="20000"/><a:satMod val="255000"/></a:schemeClr></a:gs>
                </a:gsLst><a:path path="circle"><a:fillToRect l="50000" t="-80000" r="50000" b="180000"/></a:path></a:gradFill>
                <a:gradFill rotWithShape="1"><a:gsLst>
                <a:gs pos="0"><a:schemeClr val="phClr"><a:tint val="80000"/><a:satMod val="300000"/></a:schemeClr></a:gs>
                <a:gs pos="100000"><a:schemeClr val="phClr"><a:shade val="30000"/><a:satMod val="200000"/></a:schemeClr></a:gs>
                </a:gsLst><a:path path="circle"><a:fillToRect l="50000" t="50000" r="50000" b="50000"/></a:path></a:gradFill>
                </a:bgFillStyleLst>
                </a:fmtScheme>
                </a:themeElements>
                <a:objectDefaults/>
                <a:extraClrSchemeLst/>
                </a:theme>
                """.formatted(
                escape(name),
                escape(name),
                colors.slots().get("dk1"),
                colors.slots().get("lt1"),
                colors.slots().get("dk2"),
                colors.slots().get("lt2"),
                colors.slots().get("accent1"),
                colors.slots().get("accent2"),
                colors.slots().get("accent3"),
                colors.slots().get("accent4"),
                colors.slots().get("accent5"),
                colors.slots().get("accent6"),
                colors.slots().get("hlink"),
                colors.slots().get("folHlink"),
                escape(name),
                escape(fonts.major().latin()),
                escape(fonts.major().ea()),
                escape(fonts.major().cs()),
                escape(fonts.minor().latin()),
                escape(fonts.minor().ea()),
                escape(fonts.minor().cs()),
                escape(name));
        Files.writeString(extractDir.resolve("ppt/theme/theme1.xml"), xml, StandardCharsets.UTF_8);
    }

    private static void writeMasterAndLayout(Path extractDir, int widthEmu, int heightEmu) throws IOException {
        String master = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                             xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                             xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld>
                    <p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg>
                    <p:spTree>
                      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                      <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
                    </p:spTree>
                  </p:cSld>
                  <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2"
                            accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6"
                            hlink="hlink" folHlink="folHlink"/>
                  <p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>
                </p:sldMaster>
                """;
        Files.writeString(extractDir.resolve("ppt/slideMasters/slideMaster1.xml"), master, StandardCharsets.UTF_8);
        Files.writeString(
                extractDir.resolve("ppt/slideMasters/_rels/slideMaster1.xml.rels"),
                """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
                          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
                        </Relationships>
                        """,
                StandardCharsets.UTF_8);
        String layout = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                             xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                             xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank" preserve="1">
                  <p:cSld name="Blank">
                    <p:spTree>
                      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                      <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
                    </p:spTree>
                  </p:cSld>
                  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
                </p:sldLayout>
                """;
        Files.writeString(extractDir.resolve("ppt/slideLayouts/slideLayout1.xml"), layout, StandardCharsets.UTF_8);
        Files.writeString(
                extractDir.resolve("ppt/slideLayouts/_rels/slideLayout1.xml.rels"),
                """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
                        </Relationships>
                        """,
                StandardCharsets.UTF_8);
    }

    private static void writeNotesMaster(Path extractDir) throws IOException {
        Files.writeString(
                extractDir.resolve("ppt/notesMasters/notesMaster1.xml"),
                """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <p:notesMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                                       xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                                       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                          <p:cSld>
                            <p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg>
                            <p:spTree>
                              <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                              <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
                            </p:spTree>
                          </p:cSld>
                          <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2"
                                    accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6"
                                    hlink="hlink" folHlink="folHlink"/>
                        </p:notesMaster>
                        """,
                StandardCharsets.UTF_8);
        Files.writeString(
                extractDir.resolve("ppt/notesMasters/_rels/notesMaster1.xml.rels"),
                """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
                        </Relationships>
                        """,
                StandardCharsets.UTF_8);
    }

    private static void writeNotesSlide(Path extractDir, int slideNum, String notes, String lang)
            throws IOException {
        String language = lang == null ? "en-US" : lang;
        String escaped = escape(notes);
        String xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:notes xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                         xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                         xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld>
                    <p:spTree>
                      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                      <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
                      <p:sp>
                        <p:nvSpPr><p:cNvPr id="2" name="Slide Image Placeholder 1"/><p:cNvSpPr/><p:nvPr><p:ph type="sldImg"/></p:nvPr></p:nvSpPr>
                        <p:spPr/>
                      </p:sp>
                      <p:sp>
                        <p:nvSpPr><p:cNvPr id="3" name="Notes Placeholder 2"/><p:cNvSpPr/><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr>
                        <p:spPr/>
                        <p:txBody>
                          <a:bodyPr/><a:lstStyle/>
                          <a:p><a:pPr/><a:r><a:rPr lang="%s" dirty="0"/><a:t>%s</a:t></a:r></a:p>
                        </p:txBody>
                      </p:sp>
                    </p:spTree>
                  </p:cSld>
                  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
                </p:notes>
                """.formatted(language, escaped);
        Files.writeString(
                extractDir.resolve("ppt/notesSlides/notesSlide" + slideNum + ".xml"),
                xml,
                StandardCharsets.UTF_8);
        Files.writeString(
                extractDir.resolve("ppt/notesSlides/_rels/notesSlide" + slideNum + ".xml.rels"),
                """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="../slides/slide%d.xml"/>
                          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster" Target="../notesMasters/notesMaster1.xml"/>
                        </Relationships>
                        """.formatted(slideNum),
                StandardCharsets.UTF_8);
    }

    private static void writePresentation(
            Path extractDir, int slideCount, int widthEmu, int heightEmu, List<Boolean> hasNotes)
            throws IOException {
        StringBuilder sldIdLst = new StringBuilder();
        StringBuilder rels = new StringBuilder();
        rels.append("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>
                <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster" Target="notesMasters/notesMaster1.xml"/>
                """);
        int rel = 4;
        for (int i = 1; i <= slideCount; i++) {
            String rid = "rId" + rel++;
            sldIdLst.append("<p:sldId id=\"")
                    .append(255 + i)
                    .append("\" r:id=\"")
                    .append(rid)
                    .append("\"/>");
            rels.append("<Relationship Id=\"")
                    .append(rid)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide")
                    .append(i)
                    .append(".xml\"/>\n");
        }
        rels.append("</Relationships>\n");
        String presentation = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                                xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                                saveSubsetFonts="1">
                  <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
                  <p:notesMasterIdLst><p:notesMasterId r:id="rId3"/></p:notesMasterIdLst>
                  <p:sldIdLst>%s</p:sldIdLst>
                  <p:sldSz cx="%d" cy="%d"/>
                  <p:notesSz cx="6858000" cy="9144000"/>
                </p:presentation>
                """.formatted(sldIdLst, widthEmu, heightEmu);
        Files.writeString(extractDir.resolve("ppt/presentation.xml"), presentation, StandardCharsets.UTF_8);
        Files.writeString(extractDir.resolve("ppt/_rels/presentation.xml.rels"), rels.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                extractDir.resolve("ppt/presProps.xml"),
                """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <p:presentationPr xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"/>
                        """,
                StandardCharsets.UTF_8);
        Files.writeString(
                extractDir.resolve("ppt/viewProps.xml"),
                """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <p:viewPr xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                                  xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"/>
                        """,
                StandardCharsets.UTF_8);
        Files.writeString(
                extractDir.resolve("ppt/tableStyles.xml"),
                """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <a:tblStyleLst xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" def="{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"/>
                        """,
                StandardCharsets.UTF_8);
    }

    private static void writeContentTypes(
            Path extractDir,
            int slideCount,
            List<Boolean> hasNotes,
            List<SvgSlideConverter.SlideConversion> slides)
            throws IOException {
        StringBuilder overrides = new StringBuilder();
        for (int i = 1; i <= slideCount; i++) {
            overrides.append("<Override PartName=\"/ppt/slides/slide")
                    .append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>\n");
            if (hasNotes.get(i - 1)) {
                overrides.append("<Override PartName=\"/ppt/notesSlides/notesSlide")
                        .append(i)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml\"/>\n");
            }
            for (Map.Entry<String, String> entry : slides.get(i - 1).contentTypeOverrides().entrySet()) {
                overrides.append("<Override PartName=\"/")
                        .append(entry.getKey())
                        .append("\" ContentType=\"")
                        .append(entry.getValue())
                        .append("\"/>\n");
            }
        }
        String xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Default Extension="jpeg" ContentType="image/jpeg"/>
                  <Default Extension="jpg" ContentType="image/jpeg"/>
                  <Default Extension="png" ContentType="image/png"/>
                  <Default Extension="gif" ContentType="image/gif"/>
                  <Default Extension="emf" ContentType="image/x-emf"/>
                  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
                  <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
                  <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
                  <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
                  <Override PartName="/ppt/notesMasters/notesMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml"/>
                  <Override PartName="/ppt/presProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"/>
                  <Override PartName="/ppt/viewProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml"/>
                  <Override PartName="/ppt/tableStyles.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.tableStyles+xml"/>
                  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
                  %s
                </Types>
                """.formatted(overrides);
        Files.writeString(extractDir.resolve("[Content_Types].xml"), xml, StandardCharsets.UTF_8);
    }

    private static void writePackageRels(Path extractDir) throws IOException {
        Files.writeString(
                extractDir.resolve("_rels/.rels"),
                """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
                          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
                          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
                        </Relationships>
                        """,
                StandardCharsets.UTF_8);
    }

    private static void writeDocProps(Path extractDir, String title) throws IOException {
        Files.writeString(
                extractDir.resolve("docProps/core.xml"),
                """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                                          xmlns:dc="http://purl.org/dc/elements/1.1/"
                                          xmlns:dcterms="http://purl.org/dc/terms/"
                                          xmlns:dcmitype="http://purl.org/dc/dcmitype/"
                                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                          <dc:title>%s</dc:title>
                          <dc:creator>ppt-master</dc:creator>
                          <cp:lastModifiedBy>ppt-master</cp:lastModifiedBy>
                        </cp:coreProperties>
                        """.formatted(escape(title)),
                StandardCharsets.UTF_8);
        Files.writeString(
                extractDir.resolve("docProps/app.xml"),
                """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
                                    xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
                          <Application>ppt-master Java svg_to_pptx</Application>
                        </Properties>
                        """,
                StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walk(root)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                    });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
