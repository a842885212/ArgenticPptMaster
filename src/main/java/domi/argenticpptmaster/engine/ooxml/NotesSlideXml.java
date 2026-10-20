// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Notes slide / notes master XML builders (DrawingML strings, not POI). */
public final class NotesSlideXml {

    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u9fff\\uf900-\\ufaff]");

    private NotesSlideXml() {}

    public static String createNotesSlideXml(int slideNum, String notesText) {
        String defaultLanguage = "en-US";
        String escaped = escapeXml(notesText == null ? "" : notesText);
        List<String> paragraphs = new ArrayList<>();
        for (String para : escaped.split("\n", -1)) {
            if (!para.strip().isEmpty()) {
                String lang = detectLang(para);
                paragraphs.add("""
                        <a:p>
                                      <a:pPr/>
                                      <a:r>
                                        <a:rPr lang="%s" dirty="0"/>
                                        <a:t>%s</a:t>
                                      </a:r>
                                    </a:p>""".formatted(lang, para));
            } else {
                paragraphs.add(
                        "<a:p><a:pPr/><a:endParaRPr lang=\"" + defaultLanguage + "\" dirty=\"0\"/></a:p>");
            }
        }
        String paragraphsXml = paragraphs.isEmpty()
                ? "<a:p><a:endParaRPr lang=\"" + defaultLanguage + "\" dirty=\"0\"/></a:p>"
                : String.join("\n            ", paragraphs);

        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:notes xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                         xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                         xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld>
                    <p:spTree>
                      <p:nvGrpSpPr>
                        <p:cNvPr id="1" name=""/>
                        <p:cNvGrpSpPr/>
                        <p:nvPr/>
                      </p:nvGrpSpPr>
                      <p:grpSpPr>
                        <a:xfrm>
                          <a:off x="0" y="0"/>
                          <a:ext cx="0" cy="0"/>
                          <a:chOff x="0" y="0"/>
                          <a:chExt cx="0" cy="0"/>
                        </a:xfrm>
                      </p:grpSpPr>
                      <p:sp>
                        <p:nvSpPr>
                          <p:cNvPr id="2" name="Slide Image Placeholder 1"/>
                          <p:cNvSpPr>
                            <a:spLocks noGrp="1" noRot="1" noChangeAspect="1"/>
                          </p:cNvSpPr>
                          <p:nvPr>
                            <p:ph type="sldImg" idx="2"/>
                          </p:nvPr>
                        </p:nvSpPr>
                        <p:spPr/>
                      </p:sp>
                      <p:sp>
                        <p:nvSpPr>
                          <p:cNvPr id="3" name="Notes Placeholder 2"/>
                          <p:cNvSpPr>
                            <a:spLocks noGrp="1"/>
                          </p:cNvSpPr>
                          <p:nvPr>
                            <p:ph type="body" sz="quarter" idx="3"/>
                          </p:nvPr>
                        </p:nvSpPr>
                        <p:spPr/>
                        <p:txBody>
                          <a:bodyPr/>
                          <a:lstStyle/>
                          %s
                        </p:txBody>
                      </p:sp>
                    </p:spTree>
                  </p:cSld>
                  <p:clrMapOvr>
                    <a:masterClrMapping/>
                  </p:clrMapOvr>
                </p:notes>""".formatted(paragraphsXml);
    }

    public static String createNotesSlideRelsXml(int slideNum) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster" Target="../notesMasters/notesMaster1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="../slides/slide%d.xml"/>
                </Relationships>""".formatted(slideNum);
    }

    public static String createNotesMasterXml() {
        String language = "en-US";
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:notesMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                               xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                               xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld>
                    <p:spTree>
                      <p:nvGrpSpPr>
                        <p:cNvPr id="1" name=""/>
                        <p:cNvGrpSpPr/>
                        <p:nvPr/>
                      </p:nvGrpSpPr>
                      <p:grpSpPr>
                        <a:xfrm>
                          <a:off x="0" y="0"/>
                          <a:ext cx="0" cy="0"/>
                          <a:chOff x="0" y="0"/>
                          <a:chExt cx="0" cy="0"/>
                        </a:xfrm>
                      </p:grpSpPr>
                      <p:sp>
                        <p:nvSpPr>
                          <p:cNvPr id="2" name="Header Placeholder 1"/>
                          <p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>
                          <p:nvPr><p:ph type="hdr" sz="quarter"/></p:nvPr>
                        </p:nvSpPr>
                        <p:spPr>
                          <a:xfrm>
                            <a:off x="0" y="0"/>
                            <a:ext cx="2971800" cy="457200"/>
                          </a:xfrm>
                          <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                        </p:spPr>
                        <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr/><a:endParaRPr lang="%s"/></a:p></p:txBody>
                      </p:sp>
                      <p:sp>
                        <p:nvSpPr>
                          <p:cNvPr id="3" name="Date Placeholder 2"/>
                          <p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>
                          <p:nvPr><p:ph type="dt" sz="half" idx="1"/></p:nvPr>
                        </p:nvSpPr>
                        <p:spPr>
                          <a:xfrm>
                            <a:off x="3884613" y="0"/>
                            <a:ext cx="2971800" cy="457200"/>
                          </a:xfrm>
                          <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                        </p:spPr>
                        <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr/><a:endParaRPr lang="%s"/></a:p></p:txBody>
                      </p:sp>
                      <p:sp>
                        <p:nvSpPr>
                          <p:cNvPr id="4" name="Slide Image Placeholder 3"/>
                          <p:cNvSpPr><a:spLocks noGrp="1" noRot="1" noChangeAspect="1"/></p:cNvSpPr>
                          <p:nvPr><p:ph type="sldImg" idx="2"/></p:nvPr>
                        </p:nvSpPr>
                        <p:spPr>
                          <a:xfrm>
                            <a:off x="1143000" y="685800"/>
                            <a:ext cx="4572000" cy="3429000"/>
                          </a:xfrm>
                          <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                        </p:spPr>
                      </p:sp>
                      <p:sp>
                        <p:nvSpPr>
                          <p:cNvPr id="5" name="Notes Placeholder 4"/>
                          <p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>
                          <p:nvPr><p:ph type="body" sz="quarter" idx="3"/></p:nvPr>
                        </p:nvSpPr>
                        <p:spPr>
                          <a:xfrm>
                            <a:off x="685800" y="4343400"/>
                            <a:ext cx="5486400" cy="4114800"/>
                          </a:xfrm>
                          <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                        </p:spPr>
                        <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr/><a:endParaRPr lang="%s"/></a:p></p:txBody>
                      </p:sp>
                      <p:sp>
                        <p:nvSpPr>
                          <p:cNvPr id="6" name="Footer Placeholder 5"/>
                          <p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>
                          <p:nvPr><p:ph type="ftr" sz="quarter" idx="4"/></p:nvPr>
                        </p:nvSpPr>
                        <p:spPr>
                          <a:xfrm>
                            <a:off x="0" y="8685213"/>
                            <a:ext cx="2971800" cy="457200"/>
                          </a:xfrm>
                          <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                        </p:spPr>
                        <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr/><a:endParaRPr lang="%s"/></a:p></p:txBody>
                      </p:sp>
                      <p:sp>
                        <p:nvSpPr>
                          <p:cNvPr id="7" name="Slide Number Placeholder 6"/>
                          <p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>
                          <p:nvPr><p:ph type="sldNum" sz="quarter" idx="5"/></p:nvPr>
                        </p:nvSpPr>
                        <p:spPr>
                          <a:xfrm>
                            <a:off x="3884613" y="8685213"/>
                            <a:ext cx="2971800" cy="457200"/>
                          </a:xfrm>
                          <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                        </p:spPr>
                        <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr/><a:endParaRPr lang="%s"/></a:p></p:txBody>
                      </p:sp>
                    </p:spTree>
                  </p:cSld>
                  <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2"
                            accent1="accent1" accent2="accent2" accent3="accent3"
                            accent4="accent4" accent5="accent5" accent6="accent6"
                            hlink="hlink" folHlink="folHlink"/>
                  <p:hf/>
                  <p:notesStyle>
                    <a:lvl1pPr marL="0" algn="l">
                      <a:defRPr sz="1200" lang="%s"/>
                    </a:lvl1pPr>
                  </p:notesStyle>
                </p:notesMaster>"""
                .formatted(language, language, language, language, language, language);
    }

    public static String createNotesMasterRelsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme2.xml"/>
                </Relationships>""";
    }

    static String detectLang(String text) {
        return CJK.matcher(text).find() ? "zh-CN" : "en-US";
    }

    static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
