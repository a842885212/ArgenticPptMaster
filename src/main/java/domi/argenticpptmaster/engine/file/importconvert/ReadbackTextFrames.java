package domi.argenticpptmaster.engine.file.importconvert;

import domi.argenticpptmaster.engine.file.importconvert.ReadbackHyperlinks.LinkContext;
import domi.argenticpptmaster.engine.ooxml.templatefill.TfXml;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;

/**
 * Text-frame / table rendering aligned with {@code ppt_to_md.py}
 * ({@code text_frame_to_markdown} / {@code _paragraph_to_markdown} / {@code table_to_markdown})。
 *
 * <p>段落可见性、list 语义（{@code lvl>0} 或多段落）、连续同 URL run 合并、
 * 链接文本前后空白外置、shape 级 click_action 兜底均按 Python 实现逐条对齐；
 * {@code a:br} 映射为垂直制表符（{@code \\v}），与 python-pptx {@code paragraph.text} 一致。</p>
 */
final class ReadbackTextFrames {

    private ReadbackTextFrames() {}

    /** 渲染一个 txBody；shape 可为 null（此时退化为纯文本，等价 Python 不传 shape）。 */
    static String textFrameToMarkdown(Element txBody, Element shape, LinkContext context) {
        List<Element> visible = new ArrayList<>();
        for (Element paragraph : TfXml.childrenByLocalName(txBody, "p")) {
            if (!normalizeText(paragraphText(paragraph)).isEmpty()
                    || ReadbackHyperlinks.paragraphHasHyperlink(paragraph)) {
                visible.add(paragraph);
            }
        }
        if (visible.isEmpty()) {
            return "";
        }

        boolean listLike = visible.size() > 1;
        if (!listLike) {
            for (Element paragraph : visible) {
                if (paragraphLevel(paragraph) > 0) {
                    listLike = true;
                    break;
                }
            }
        }

        List<String> rendered = new ArrayList<>();
        for (Element paragraph : visible) {
            String text = paragraphToMarkdown(paragraph, shape, context);
            if (text.isEmpty()) {
                continue;
            }
            if (listLike) {
                rendered.add("  ".repeat(Math.max(paragraphLevel(paragraph), 0)) + "- " + text);
            } else {
                rendered.add(text);
            }
        }
        return String.join(listLike ? "\n" : "\n\n", rendered);
    }

    /** 对齐 Python {@code normalize_text}：CRLF/CR → LF，逐行折叠空白，去空行。 */
    static String normalizeText(String value) {
        String normalized = value.replace("\r\n", "\n").replace("\r", "\n");
        StringBuilder out = new StringBuilder();
        for (String line : normalized.split("\n")) {
            String collapsed = line.replaceAll("\\s+", " ").strip();
            if (!collapsed.isEmpty()) {
                out.append(collapsed).append('\n');
            }
        }
        if (out.isEmpty()) {
            return "";
        }
        return out.substring(0, out.length() - 1);
    }

    /** 段落纯文本：run + field 文本拼接，{@code a:br} 记为 {@code \\v}（对齐 python-pptx {@code paragraph.text}）。 */
    static String paragraphText(Element paragraph) {
        StringBuilder text = new StringBuilder();
        for (org.w3c.dom.Node node : iterable(paragraph.getChildNodes())) {
            if (!(node instanceof Element child)) {
                continue;
            }
            switch (child.getLocalName()) {
                case "r", "fld" -> {
                    Element t = TfXml.firstChild(child, "t");
                    text.append(t == null || t.getTextContent() == null ? "" : t.getTextContent());
                }
                case "br" -> text.append('\u000B');
                default -> {
                    // endParaRPr 等无文本节点忽略
                }
            }
        }
        return text.toString();
    }

    private static String paragraphToMarkdown(Element paragraph, Element shape, LinkContext context) {
        StringBuilder parts = new StringBuilder();
        String currentUrl = null;
        StringBuilder currentText = new StringBuilder();

        boolean hasRunHyperlink = false;
        for (Element run : TfXml.childrenByLocalName(paragraph, "r")) {
            String url = ReadbackHyperlinks.runUrl(run, context);
            if (url != null) {
                hasRunHyperlink = true;
            }
            if (!java.util.Objects.equals(url, currentUrl)) {
                flush(parts, currentUrl, currentText);
                currentText = new StringBuilder();
                currentUrl = url;
            }
            Element t = TfXml.firstChild(run, "t");
            currentText.append(t == null || t.getTextContent() == null ? "" : t.getTextContent());
        }
        flush(parts, currentUrl, currentText);

        String text = normalizeText(parts.toString());

        // shape 级 click_action 仅在无 run 级链接时生效（Python has_run_hyperlink 语义）
        if (!hasRunHyperlink && shape != null) {
            String actionUrl = ReadbackHyperlinks.clickActionUrl(shape, context);
            if (actionUrl != null) {
                text = "[" + ReadbackHyperlinks.escapeMdLinkText(text) + "](" + actionUrl + ")";
            }
        }
        return text;
    }

    private static void flush(StringBuilder parts, String url, StringBuilder currentText) {
        if (currentText.isEmpty()) {
            return;
        }
        String raw = currentText.toString();
        if (url == null) {
            parts.append(raw);
            return;
        }
        String lead = raw.substring(0, raw.length() - raw.stripLeading().length());
        String trail = raw.substring(raw.stripTrailing().length());
        String core = raw.strip();
        String display = core.isEmpty() ? url : ReadbackHyperlinks.escapeMdLinkText(core);
        parts.append(lead).append('[').append(display).append("](").append(url).append(')').append(trail);
    }

    private static int paragraphLevel(Element paragraph) {
        Element pPr = TfXml.firstChild(paragraph, "pPr");
        if (pPr == null) {
            return 0;
        }
        String lvl = pPr.getAttribute("lvl");
        if (lvl == null || lvl.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(lvl.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 表格渲染（{@code table_to_markdown}）：单元格多段落以 {@code \\n} 连接后逐行规范化，{@code |} 转义。 */
    static String tableToMarkdown(Element graphicFrame) {
        Element tbl = TfXml.firstDescendant(graphicFrame, "tbl");
        if (tbl == null) {
            return "";
        }
        List<List<String>> rows = new ArrayList<>();
        for (Element tr : TfXml.childrenByLocalName(tbl, "tr")) {
            List<String> cells = new ArrayList<>();
            for (Element tc : TfXml.childrenByLocalName(tr, "tc")) {
                cells.add(escapeTableCell(tableCellText(tc)));
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return "";
        }
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder out = new StringBuilder();
        appendRow(out, pad(rows.get(0), width));
        List<String> separator = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            separator.add("---");
        }
        appendRow(out, separator);
        for (int r = 1; r < rows.size(); r++) {
            appendRow(out, pad(rows.get(r), width));
        }
        return out.toString().strip();
    }

    private static String tableCellText(Element tc) {
        Element txBody = TfXml.firstChild(tc, "txBody");
        if (txBody == null) {
            return "";
        }
        List<String> paragraphs = new ArrayList<>();
        for (Element paragraph : TfXml.childrenByLocalName(txBody, "p")) {
            paragraphs.add(paragraphText(paragraph));
        }
        return String.join("\n", paragraphs);
    }

    private static String escapeTableCell(String value) {
        String normalized = normalizeText(value);
        if (normalized.isEmpty()) {
            return " ";
        }
        return normalized.replace("|", "\\|");
    }

    private static List<String> pad(List<String> row, int width) {
        List<String> padded = new ArrayList<>(row);
        while (padded.size() < width) {
            padded.add(" ");
        }
        return padded;
    }

    private static void appendRow(StringBuilder out, List<String> cells) {
        out.append("| ").append(String.join(" | ", cells)).append(" |\n");
    }

    private static Iterable<org.w3c.dom.Node> iterable(org.w3c.dom.NodeList nodes) {
        List<org.w3c.dom.Node> out = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add(nodes.item(i));
        }
        return out;
    }
}
