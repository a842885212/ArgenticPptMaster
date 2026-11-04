package domi.argenticpptmaster.engine.file.importconvert;

import domi.argenticpptmaster.engine.ooxml.templatefill.TfXml;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Leaf-shape iteration aligned with {@code ppt_to_md.py} {@code iter_leaf_shapes}:
 * groups are flattened recursively, leaves are sorted by reading order ({@code top}, {@code left}).
 *
 * <p>与 python-pptx 行为一致：组内子形状的坐标直接使用其自身 xfrm 值（不做组坐标变换），
 * 位置缺失时按 0 处理（对应 python-pptx 占位符继承解析失败时的 safe fallback）。</p>
 */
final class ReadbackShapes {

    /** A flattened leaf shape ({@code p:sp} / {@code p:pic} / {@code p:graphicFrame} / {@code p:cxnSp}). */
    record LeafShape(Element element, long top, long left) {}

    private ReadbackShapes() {}

    static List<LeafShape> leafShapes(Element slideRoot) {
        Element spTree = TfXml.firstDescendant(slideRoot, "spTree");
        if (spTree == null) {
            spTree = slideRoot;
        }
        List<LeafShape> leaves = new ArrayList<>();
        collect(spTree, leaves);
        leaves.sort((a, b) -> a.top != b.top ? Long.compare(a.top, b.top) : Long.compare(a.left, b.left));
        return leaves;
    }

    private static void collect(Element parent, List<LeafShape> leaves) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            switch (child.getLocalName()) {
                case "grpSp" -> collect(child, leaves);
                case "sp", "pic", "graphicFrame", "cxnSp" -> {
                    long[] position = position(child);
                    leaves.add(new LeafShape(child, position[0], position[1]));
                }
                default -> {
                    // spTree 中的 nvGrpSpPr / grpSpPr 等非形状节点忽略
                }
            }
        }
    }

    /** 读取形状的 EMU (top, left)；缺失或非法时返回 0（对齐 python-pptx 的 safe position）。 */
    private static long[] position(Element shape) {
        Element container = shape;
        Element xfrm = switch (shape.getLocalName()) {
            case "graphicFrame" -> TfXml.firstChild(container, "xfrm");
            default -> {
                Element spPr = TfXml.firstChild(container, "spPr");
                yield spPr == null ? null : TfXml.firstChild(spPr, "xfrm");
            }
        };
        Element off = xfrm == null ? null : TfXml.firstChild(xfrm, "off");
        if (off == null) {
            return new long[] {0, 0};
        }
        return new long[] {parseEmu(off.getAttribute("y")), parseEmu(off.getAttribute("x"))};
    }

    private static long parseEmu(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** cNvPr 上的形状名（{@code ppt_to_md.py} 的 {@code shape.name}）。 */
    static String shapeName(Element shape) {
        Element cNvPr = TfXml.firstDescendant(shape, "cNvPr");
        return cNvPr == null ? "" : nullToEmpty(cNvPr.getAttribute("name"));
    }

    static boolean isPicture(Element shape) {
        return "pic".equals(shape.getLocalName());
    }

    static boolean isGraphicFrame(Element shape) {
        return "graphicFrame".equals(shape.getLocalName());
    }

    /** 形状是否含表格（graphicFrame 内含 {@code a:tbl}）。 */
    static boolean hasTable(Element shape) {
        return TfXml.firstDescendant(shape, "tbl") != null;
    }

    /** 形状是否含 chart graphicFrame，返回 relationship id（无则 null）。 */
    static String chartRelationshipId(Element shape) {
        for (Element chart : TfXml.descendants(shape, "chart")) {
            String relId = TfXml.attrNs(chart, "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
            if (relId == null || relId.isBlank()) {
                relId = chart.getAttribute("r:id");
            }
            if (relId != null && !relId.isBlank()) {
                return relId;
            }
        }
        return null;
    }

    /** 形状引用的第一个嵌入图片 relationship id（{@code .//a:blip} 的 r:embed / r:link）。 */
    static String imageRelationshipId(Element shape) {
        String relNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        for (Element blip : TfXml.descendants(shape, "blip")) {
            String relId = TfXml.attrNs(blip, relNs, "embed");
            if (relId == null || relId.isBlank()) {
                relId = TfXml.attrNs(blip, relNs, "link");
            }
            if (relId != null && !relId.isBlank()) {
                return relId;
            }
        }
        return null;
    }

    /** 文本容器（{@code p:txBody}），无则 null。 */
    static Element textBody(Element shape) {
        return TfXml.firstChild(shape, "txBody");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
