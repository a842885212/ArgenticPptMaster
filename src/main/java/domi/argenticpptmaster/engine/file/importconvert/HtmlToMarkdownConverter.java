package domi.argenticpptmaster.engine.file.importconvert;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/** Local HTML/HTM → Markdown converter (static extraction via Jsoup). */
public final class HtmlToMarkdownConverter {

    private HtmlToMarkdownConverter() {}

    public static Path convert(Path htmlPath, Path markdownPath) throws ImportConversionException {
        try {
            String html = Files.readString(htmlPath, StandardCharsets.UTF_8);
            Document document = Jsoup.parse(html, htmlPath.toUri().toString());
            document.select("script, style, noscript").remove();

            String title = document.title();
            if (title == null || title.isBlank()) {
                title = stem(htmlPath);
            }

            StringBuilder body = new StringBuilder();
            Element root = document.body() != null ? document.body() : document;
            NodeTraversor.traverse(new MarkdownVisitor(body), root);

            StringBuilder md = new StringBuilder();
            md.append("# ").append(title.strip()).append("\n\n");
            md.append("- Source: `").append(htmlPath.getFileName()).append("`\n\n");
            String content = body.toString().replaceAll("\\n{3,}", "\n\n").strip();
            if (content.isBlank()) {
                content = document.text().strip();
            }
            if (content.isBlank()) {
                throw new ImportConversionException("HTML conversion produced no usable Markdown for " + htmlPath);
            }
            md.append(content).append('\n');
            Files.writeString(markdownPath, md.toString(), StandardCharsets.UTF_8);
            return markdownPath;
        } catch (ImportConversionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ImportConversionException(
                    "HTML conversion failed for " + htmlPath + ": " + ex.getMessage(), ex);
        }
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static final class MarkdownVisitor implements NodeVisitor {
        private final StringBuilder out;

        private MarkdownVisitor(StringBuilder out) {
            this.out = out;
        }

        @Override
        public void head(Node node, int depth) {
            if (node instanceof TextNode textNode) {
                out.append(textNode.text());
                return;
            }
            if (!(node instanceof Element element)) {
                return;
            }
            String tag = element.normalName();
            switch (tag) {
                case "h1" -> out.append("\n\n# ");
                case "h2" -> out.append("\n\n## ");
                case "h3" -> out.append("\n\n### ");
                case "h4", "h5", "h6" -> out.append("\n\n#### ");
                case "p", "div", "section", "article" -> out.append("\n\n");
                case "br" -> out.append("\n");
                case "li" -> out.append("\n- ");
                case "strong", "b" -> out.append("**");
                case "em", "i" -> out.append('*');
                case "a" -> {
                    /* link text handled as children; URL appended in tail */
                }
                case "img" -> {
                    String alt = element.hasAttr("alt") ? element.attr("alt") : "image";
                    String src = element.hasAttr("src") ? element.attr("src") : "";
                    out.append("![").append(alt).append("](").append(src).append(')');
                }
                default -> {
                    // ignore
                }
            }
        }

        @Override
        public void tail(Node node, int depth) {
            if (!(node instanceof Element element)) {
                return;
            }
            String tag = element.normalName();
            switch (tag) {
                case "strong", "b" -> out.append("**");
                case "em", "i" -> out.append('*');
                case "a" -> {
                    String href = element.hasAttr("href") ? element.attr("href") : "";
                    if (!href.isBlank()) {
                        out.append(" (").append(href).append(')');
                    }
                }
                case "p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol" ->
                        out.append("\n\n");
                default -> {
                    // ignore
                }
            }
        }
    }
}
