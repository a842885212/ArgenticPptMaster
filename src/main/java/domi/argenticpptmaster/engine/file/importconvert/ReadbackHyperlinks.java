package domi.argenticpptmaster.engine.file.importconvert;

import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import domi.argenticpptmaster.engine.ooxml.RelationshipsSupport;
import domi.argenticpptmaster.engine.ooxml.RelationshipsSupport.Relationship;
import domi.argenticpptmaster.engine.ooxml.SlideParts;
import domi.argenticpptmaster.engine.ooxml.templatefill.TfXml;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.w3c.dom.Element;

/**
 * Hyperlink resolution aligned with {@code ppt_to_md.py}: run-level external URLs,
 * {@code ppaction://hlinksldjump} internal jumps resolved via slide relationships,
 * and shape-level {@code click_action} fallback.
 */
final class ReadbackHyperlinks {

    /** python-pptx 无法解析位置等异常时打 WARN 到 stderr；Java 侧转换无 stderr 通道，静默降级。 */
    private static final String[] UNSUPPORTED_URL_SCHEMES = {"javascript:", "data:", "vbscript:", "file:"};
    private static final String JUMP_ACTION = "ppaction://hlinksldjump";
    private static final String DRAWING_REL_NS = OoxmlConstants.OFFICE_REL_NS;

    private ReadbackHyperlinks() {}

    /** 当前形状所属 part 名、该 part 的 rels 与全 deck 的 slide part → 页码映射。 */
    record LinkContext(String sourcePart, List<Relationship> relationships, Map<String, Integer> slideIndexByPart) {}

    /** 段落内任一 run 携带 hlinkClick 即视为有链接（{@code _paragraph_has_hyperlink}）。 */
    static boolean paragraphHasHyperlink(Element paragraph) {
        for (Element run : TfXml.childrenByLocalName(paragraph, "r")) {
            Element rPr = TfXml.firstChild(run, "rPr");
            if (rPr != null && TfXml.firstChild(rPr, "hlinkClick") != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析单个 run 的链接目标；无链接 / 危险 scheme / 悬空跳转返回 null。
     * 优先内部跳转，其次外链（仅 TargetMode=External 的关系，对齐 python-pptx hyperlink.address）。
     */
    static String runUrl(Element run, LinkContext context) {
        Element rPr = TfXml.firstChild(run, "rPr");
        Element hlink = rPr == null ? null : TfXml.firstChild(rPr, "hlinkClick");
        if (hlink == null) {
            return null;
        }
        String action = nullToEmpty(hlink.getAttribute("action"));
        String relId = TfXml.attrNs(hlink, DRAWING_REL_NS, "id");
        if (relId == null || relId.isBlank()) {
            relId = hlink.getAttribute("r:id");
        }
        if (relId == null || relId.isBlank()) {
            return null;
        }
        if (action.contains("hlinksldjump")) {
            return internalJump(relId, context);
        }
        Relationship rel = findRelationship(context.relationships(), relId);
        if (rel == null || !rel.external() || !isSupportedUrl(rel.target())) {
            return null;
        }
        return encodeMdUrl(unescapeXml(rel.target()));
    }

    /** shape 级 click_action：named-slide 跳转或外链；无则 null。 */
    static String clickActionUrl(Element shape, LinkContext context) {
        Element cNvPr = TfXml.firstDescendant(shape, "cNvPr");
        Element hlink = cNvPr == null ? null : TfXml.firstChild(cNvPr, "hlinkClick");
        if (hlink == null) {
            return null;
        }
        String relId = TfXml.attrNs(hlink, DRAWING_REL_NS, "id");
        if (relId == null || relId.isBlank()) {
            relId = hlink.getAttribute("r:id");
        }
        if (relId == null || relId.isBlank()) {
            return null;
        }
        if (nullToEmpty(hlink.getAttribute("action")).contains("hlinksldjump")) {
            return internalJump(relId, context);
        }
        Relationship rel = findRelationship(context.relationships(), relId);
        if (rel == null || !rel.external() || !isSupportedUrl(rel.target())) {
            return null;
        }
        return encodeMdUrl(unescapeXml(rel.target()));
    }

    private static String internalJump(String relId, LinkContext context) {
        Relationship rel = findRelationship(context.relationships(), relId);
        if (rel == null) {
            return null;
        }
        String targetPart = SlideParts.resolveRelationshipPart(
                context.sourcePart(), rel.target().split("#", 2)[0]);
        Integer index = context.slideIndexByPart().get(targetPart);
        return index == null ? null : "#slide-" + index;
    }

    private static Relationship findRelationship(List<Relationship> relationships, String relId) {
        for (Relationship rel : relationships) {
            if (relId.equals(rel.id())) {
                return rel;
            }
        }
        return null;
    }

    static boolean isSupportedUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        for (String scheme : UNSUPPORTED_URL_SCHEMES) {
            if (lower.startsWith(scheme)) {
                return false;
            }
        }
        return true;
    }

    /** 对齐 Python {@code quote(url, safe="/:?=&%#@!$'*+,;")}。 */
    static String encodeMdUrl(String url) {
        String safe = "/:?=&%#@!$'*+,;";
        StringBuilder out = new StringBuilder();
        for (byte b : url.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            char ch = (char) c;
            if (c < 128 && safe.indexOf(ch) >= 0) {
                out.append(ch);
            } else if (c < 128 && (Character.isLetterOrDigit(ch) || ch == '-' || ch == '.' || ch == '_' || ch == '~')) {
                // python quote 默认保留 unreserved 字符
                out.append(ch);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return out.toString();
    }

    /** 对齐 Python {@code _escape_md_link_text}。 */
    static String escapeMdLinkText(String text) {
        return text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** RelationshipsSupport 以正则解析原始 XML 文本，rel target 可能含实体，使用前反转义。 */
    static String unescapeXml(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
