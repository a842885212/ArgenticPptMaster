package domi.argenticpptmaster.engine.file.importconvert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * http(s) URL → Markdown importer (HttpClient + Jsoup), contract-aligned with upstream
 * {@code web_to_md} write conventions (MD, {@code _files/}, manifest, failure {@code .url.txt}).
 */
public final class UrlToMarkdownImporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT =
            "ArgenticPptMaster-UrlImporter/1.0 (+https://github.com/; import-sources)";
    private static final Pattern NON_SAFE = Pattern.compile("[^a-zA-Z0-9._\\-]+");

    private final PageFetcher pageFetcher;
    private final ImageFetcher imageFetcher;
    private final boolean resolveHostSsrf;

    public UrlToMarkdownImporter() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.pageFetcher = new HttpPageFetcher(client);
        this.imageFetcher = new HttpImageFetcher(client);
        this.resolveHostSsrf = true;
    }

    /** Test/fixture constructor: scheme checks only (no DNS SSRF) so offline contract tests work. */
    public UrlToMarkdownImporter(PageFetcher pageFetcher, ImageFetcher imageFetcher) {
        this(pageFetcher, imageFetcher, false);
    }

    public UrlToMarkdownImporter(PageFetcher pageFetcher, ImageFetcher imageFetcher, boolean resolveHostSsrf) {
        this.pageFetcher = pageFetcher;
        this.imageFetcher = imageFetcher;
        this.resolveHostSsrf = resolveHostSsrf;
    }

    /**
     * Import one URL into {@code markdownPath}. On page-level failure the caller must write
     * {@code .url.txt} then fail the whole import.
     */
    public Path importUrl(String url, Path markdownPath) throws ImportConversionException {
        URI uri = UrlSsrfGuard.requireHttpUrl(url);
        if (resolveHostSsrf) {
            UrlSsrfGuard.validateResolvedTarget(uri);
        }

        FetchedPage page = pageFetcher.fetch(uri);
        Document document = Jsoup.parse(page.html(), uri.toString());
        Metadata metadata = extractMetadata(document, uri.toString());

        Element content = findMainContent(document);
        if (content == null) {
            throw new ImportConversionException("Could not extract main content from " + url);
        }

        Path assetDir = markdownPath.resolveSibling(stem(markdownPath) + "_files");
        String relPrefix = assetDir.getFileName().toString();
        try {
            deleteRecursively(assetDir);
            int images = downloadAndRewriteImages(content, uri.toString(), assetDir, relPrefix);
            String bodyMd = elementToMarkdown(content).replaceAll("\\n{3,}", "\n\n").strip();
            if (bodyMd.isBlank()) {
                throw new ImportConversionException("Extracted Markdown body is empty for " + url);
            }

            StringBuilder out = new StringBuilder();
            out.append("<!--\n");
            out.append("  Source: ").append(url).append('\n');
            out.append("  Crawled: ").append(Instant.now()).append('\n');
            if (metadata.date() != null && !metadata.date().isBlank()) {
                out.append("  Published: ").append(metadata.date()).append('\n');
            }
            if (metadata.author() != null && !metadata.author().isBlank()) {
                out.append("  Author: ").append(metadata.author()).append('\n');
            }
            out.append("-->\n\n");
            if (metadata.title() != null && !metadata.title().isBlank()) {
                out.append("# ").append(metadata.title().strip()).append("\n\n");
            }
            if (metadata.description() != null && !metadata.description().isBlank()) {
                out.append("> ").append(metadata.description().strip()).append("\n\n");
            }
            out.append(bodyMd).append('\n');

            Files.createDirectories(markdownPath.getParent());
            Files.writeString(markdownPath, out.toString(), StandardCharsets.UTF_8);
            if (images == 0 && Files.isDirectory(assetDir) && isEmptyDir(assetDir)) {
                deleteRecursively(assetDir);
            }
            return markdownPath;
        } catch (ImportConversionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ImportConversionException("URL import failed for " + url + ": " + ex.getMessage(), ex);
        }
    }

    /** Write audit {@code .url.txt} for a failed URL import. */
    public static Path writeUrlFailureRecord(Path sourcesDir, String url) throws IOException {
        String stem = deriveUrlBasename(url);
        Path target = ensureUnique(sourcesDir.resolve(stem + ".url.txt"));
        String body = "URL: " + url + "\nImported: " + Instant.now() + "\n";
        Files.writeString(target, body, StandardCharsets.UTF_8);
        return target;
    }

    public static String deriveUrlBasename(String url) {
        try {
            URI uri = URI.create(url.trim());
            List<String> parts = new ArrayList<>();
            String host = sanitizeName(uri.getHost() == null ? "" : uri.getHost());
            if (!host.isBlank()) {
                parts.add(host);
            }
            String path = uri.getPath();
            if (path != null && !path.isBlank() && !"/".equals(path)) {
                String pathPart = sanitizeName(path.replaceAll("^/+|/+$", "").replace('/', '_'));
                if (!pathPart.isBlank()) {
                    parts.add(pathPart);
                }
            }
            return parts.isEmpty() ? "web_source" : String.join("_", parts);
        } catch (RuntimeException ex) {
            return "web_source";
        }
    }

    private int downloadAndRewriteImages(Element content, String pageUrl, Path imageDir, String relPrefix)
            throws IOException {
        Elements images = content.select("img");
        if (images.isEmpty()) {
            return 0;
        }
        Files.createDirectories(imageDir);
        Map<String, String> downloaded = new LinkedHashMap<>();
        Map<String, ObjectNode> manifestByFilename = new LinkedHashMap<>();
        int saved = 0;
        int idx = 0;
        for (Element img : images) {
            idx++;
            String src = firstImageCandidate(img);
            if (src == null) {
                continue;
            }
            img.attr("src", src);
            String absUrl;
            try {
                absUrl = URI.create(pageUrl).resolve(src).toString();
            } catch (RuntimeException ex) {
                continue;
            }
            String savedName;
            if (downloaded.containsKey(absUrl)) {
                savedName = downloaded.get(absUrl);
            } else {
                try {
                    URI imageUri = UrlSsrfGuard.requireHttpUrl(absUrl);
                    if (resolveHostSsrf) {
                        UrlSsrfGuard.validateResolvedTarget(imageUri);
                    }
                    FetchedBytes bytes = imageFetcher.fetch(imageUri);
                    String filename = buildImageFilename(absUrl, idx, bytes.contentType());
                    filename = uniqueFilename(imageDir, filename);
                    Files.write(imageDir.resolve(filename), bytes.body());
                    downloaded.put(absUrl, filename);
                    savedName = filename;
                    ObjectNode entry = MAPPER.createObjectNode();
                    entry.put("index", manifestByFilename.size() + 1);
                    entry.put("filename", savedName);
                    entry.put("original_filename", savedName);
                    entry.put("asset_kind", "bitmap");
                    entry.put("svg_renderable", true);
                    entry.put("pptx_native_supported", true);
                    entry.put("source_kind", "web_image");
                    entry.put("source_url", absUrl);
                    entry.put("source_page_url", pageUrl);
                    entry.put(
                            "content_type",
                            bytes.contentType() == null
                                    ? ""
                                    : bytes.contentType().split(";")[0].strip());
                    entry.putArray("occurrences");
                    manifestByFilename.put(savedName, entry);
                    saved++;
                } catch (Exception ex) {
                    // Soft-skip single image failures.
                    continue;
                }
            }
            img.attr("src", relPrefix + "/" + savedName);
            ObjectNode manifestItem = manifestByFilename.get(savedName);
            if (manifestItem != null) {
                ArrayNode occurrences = (ArrayNode) manifestItem.get("occurrences");
                ObjectNode occurrence = occurrences.addObject();
                occurrence.put("occurrence_index", idx);
                occurrence.put("source_url", absUrl);
                occurrence.put("alt_text", img.hasAttr("alt") ? img.attr("alt") : "");
                manifestItem.put("usage_count", occurrences.size());
            }
        }
        if (!manifestByFilename.isEmpty()) {
            Files.writeString(
                    imageDir.resolve("image_manifest.json"),
                    MAPPER.writerWithDefaultPrettyPrinter()
                                    .writeValueAsString(new ArrayList<>(manifestByFilename.values()))
                            + "\n",
                    StandardCharsets.UTF_8);
        }
        return saved;
    }

    private static String firstImageCandidate(Element img) {
        for (String attr : List.of("data-src", "data-original", "data-lazy-src", "data-actualsrc", "src")) {
            if (!img.hasAttr(attr)) {
                continue;
            }
            String value = img.attr(attr).strip();
            if (value.isBlank() || value.startsWith("data:")) {
                continue;
            }
            if (value.startsWith("http://")
                    || value.startsWith("https://")
                    || value.startsWith("//")
                    || value.startsWith("/")) {
                if (value.startsWith("//")) {
                    return "https:" + value;
                }
                return value;
            }
        }
        return null;
    }

    private static String buildImageFilename(String absUrl, int seq, String contentType) {
        String path = URI.create(absUrl).getPath();
        String basename = path == null || path.isBlank() ? "" : Path.of(path).getFileName().toString();
        int q = basename.indexOf('?');
        if (q >= 0) {
            basename = basename.substring(0, q);
        }
        String stem;
        String ext;
        int dot = basename.lastIndexOf('.');
        if (dot > 0 && basename.length() - dot <= 5) {
            stem = basename.substring(0, dot);
            ext = basename.substring(dot).toLowerCase(Locale.ROOT);
        } else {
            stem = basename;
            ext = "";
        }
        if (ext.isBlank() && contentType != null) {
            String ctype = contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
            ext = switch (ctype) {
                case "image/jpeg", "image/jpg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/gif" -> ".gif";
                case "image/webp" -> ".webp";
                default -> "";
            };
        }
        if (ext.isBlank()) {
            ext = ".jpg";
        }
        stem = sanitizeName(stem);
        if (stem.isBlank()) {
            stem = "image_" + seq;
        }
        return stem + ext;
    }

    private static String uniqueFilename(Path dir, String filename) throws IOException {
        Path candidate = dir.resolve(filename);
        if (!Files.exists(candidate)) {
            return filename;
        }
        int dot = filename.lastIndexOf('.');
        String stem = dot >= 0 ? filename.substring(0, dot) : filename;
        String ext = dot >= 0 ? filename.substring(dot) : "";
        int counter = 1;
        while (true) {
            String next = stem + "_" + counter + ext;
            if (!Files.exists(dir.resolve(next))) {
                return next;
            }
            counter++;
        }
    }

    static Element findMainContent(Document document) {
        document.select("script, style, nav, header, footer, aside, noscript, iframe").remove();
        Element best = null;
        int maxScore = 0;
        for (String selector : List.of("article", "main", "[role=main]", ".post-content", ".article-content", "#content")) {
            Elements elements = document.select(selector);
            for (Element el : elements) {
                String text = el.text();
                int length = text.length();
                if (length < 100) {
                    continue;
                }
                int chinese = countChinese(text);
                int score = length + chinese * 2;
                if (score > maxScore) {
                    maxScore = score;
                    best = el;
                }
            }
        }
        if (best == null || maxScore < 200) {
            for (Element div : document.select("div")) {
                int pCount = div.select("> p").size();
                String text = div.text();
                if (text.length() > 200 && pCount >= 1) {
                    int chinese = countChinese(text);
                    int score = text.length() + chinese * 2 + pCount * 50;
                    if (score > maxScore) {
                        maxScore = score;
                        best = div;
                    }
                }
            }
        }
        return best != null ? best : document.body();
    }

    private static Metadata extractMetadata(Document document, String url) {
        String title = document.title() == null ? "" : document.title().strip();
        Map<String, String> metas = new LinkedHashMap<>();
        for (Element meta : document.select("meta")) {
            String name = meta.hasAttr("name") ? meta.attr("name") : meta.attr("property");
            String content = meta.attr("content");
            if (name != null && !name.isBlank() && content != null && !content.isBlank()) {
                metas.put(name.toLowerCase(Locale.ROOT), content.strip());
            }
        }
        String date = firstNonBlank(
                metas.get("article:published_time"),
                metas.get("og:published_time"),
                metas.get("pubdate"),
                metas.get("publishdate"),
                metas.get("date"));
        String author = firstNonBlank(metas.get("author"), metas.get("article:author"), metas.get("og:article:author"));
        String description = firstNonBlank(metas.get("description"), metas.get("og:description"));
        if (title.isBlank()) {
            title = deriveUrlBasename(url).replace('_', ' ');
        }
        return new Metadata(title, date, author, description);
    }

    static String elementToMarkdown(Element root) {
        if (root == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        NodeTraversor.traverse(new MarkdownVisitor(out), root);
        return out.toString();
    }

    private static int countChinese(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fa5) {
                count++;
            }
        }
        return count;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String sanitizeName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = NON_SAFE.matcher(value).replaceAll("_");
        cleaned = cleaned.replaceAll("_+", "_");
        return cleaned.replaceAll("^_+|_+$", "");
    }

    private static Path ensureUnique(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot >= 0 ? fileName.substring(0, dot) : fileName;
        String suffix = dot >= 0 ? fileName.substring(dot) : "";
        int counter = 2;
        while (true) {
            Path candidate = path.resolveSibling(stem + "_" + counter + suffix);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            counter++;
        }
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            List<Path> all = walk.sorted((a, b) -> b.compareTo(a)).toList();
            for (Path entry : all) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private record Metadata(String title, String date, String author, String description) {}

    public record FetchedPage(String html) {}

    public record FetchedBytes(byte[] body, String contentType) {}

    @FunctionalInterface
    public interface PageFetcher {
        FetchedPage fetch(URI uri) throws ImportConversionException;
    }

    @FunctionalInterface
    public interface ImageFetcher {
        FetchedBytes fetch(URI uri) throws ImportConversionException;
    }

    private static final class HttpPageFetcher implements PageFetcher {
        private final HttpClient client;

        private HttpPageFetcher(HttpClient client) {
            this.client = client;
        }

        @Override
        public FetchedPage fetch(URI uri) throws ImportConversionException {
            try {
                URI current = uri;
                for (int hop = 0; hop <= UrlSsrfGuard.MAX_REDIRECTS; hop++) {
                    UrlSsrfGuard.validateResolvedTarget(current);
                    HttpRequest request = HttpRequest.newBuilder(current)
                            .timeout(REQUEST_TIMEOUT)
                            .header("User-Agent", USER_AGENT)
                            .GET()
                            .build();
                    HttpResponse<InputStream> response =
                            client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    int code = response.statusCode();
                    if (code >= 300 && code < 400) {
                        String location = response.headers().firstValue("location").orElse(null);
                        response.body().close();
                        if (location == null || location.isBlank()) {
                            throw new ImportConversionException("Redirect without Location from " + current);
                        }
                        current = current.resolve(location);
                        continue;
                    }
                    if (code < 200 || code >= 300) {
                        response.body().close();
                        throw new ImportConversionException("HTTP " + code + " fetching " + current);
                    }
                    byte[] body = readLimited(response.body(), UrlSsrfGuard.MAX_BODY_BYTES);
                    String charset = response.headers()
                            .firstValue("Content-Type")
                            .map(UrlToMarkdownImporter::charsetFromContentType)
                            .orElse("UTF-8");
                    return new FetchedPage(new String(body, charset));
                }
                throw new ImportConversionException("Too many redirects fetching " + uri);
            } catch (ImportConversionException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ImportConversionException("Failed to fetch " + uri + ": " + ex.getMessage(), ex);
            }
        }
    }

    private static final class HttpImageFetcher implements ImageFetcher {
        private final HttpClient client;

        private HttpImageFetcher(HttpClient client) {
            this.client = client;
        }

        @Override
        public FetchedBytes fetch(URI uri) throws ImportConversionException {
            try {
                URI current = uri;
                for (int hop = 0; hop <= UrlSsrfGuard.MAX_REDIRECTS; hop++) {
                    UrlSsrfGuard.validateResolvedTarget(current);
                    HttpRequest request = HttpRequest.newBuilder(current)
                            .timeout(REQUEST_TIMEOUT)
                            .header("User-Agent", USER_AGENT)
                            .GET()
                            .build();
                    HttpResponse<InputStream> response =
                            client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    int code = response.statusCode();
                    if (code >= 300 && code < 400) {
                        String location = response.headers().firstValue("location").orElse(null);
                        response.body().close();
                        if (location == null || location.isBlank()) {
                            throw new ImportConversionException("Image redirect without Location from " + current);
                        }
                        current = current.resolve(location);
                        continue;
                    }
                    if (code < 200 || code >= 300) {
                        response.body().close();
                        throw new ImportConversionException("HTTP " + code + " fetching image " + current);
                    }
                    byte[] body = readLimited(response.body(), UrlSsrfGuard.MAX_IMAGE_BYTES);
                    String contentType = response.headers().firstValue("Content-Type").orElse("");
                    return new FetchedBytes(body, contentType);
                }
                throw new ImportConversionException("Too many redirects fetching image " + uri);
            } catch (ImportConversionException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ImportConversionException("Failed to fetch image " + uri + ": " + ex.getMessage(), ex);
            }
        }
    }

    private static byte[] readLimited(InputStream in, long maxBytes) throws IOException {
        try (in) {
            byte[] buffer = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE));
            if (buffer.length > maxBytes) {
                throw new IOException("Response body exceeds limit of " + maxBytes + " bytes");
            }
            return buffer;
        }
    }

    private static String charsetFromContentType(String contentType) {
        if (contentType == null) {
            return "UTF-8";
        }
        for (String part : contentType.split(";")) {
            String trimmed = part.strip();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                return trimmed.substring("charset=".length()).strip();
            }
        }
        return "UTF-8";
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
            switch (element.normalName()) {
                case "h1" -> out.append("\n\n# ");
                case "h2" -> out.append("\n\n## ");
                case "h3" -> out.append("\n\n### ");
                case "h4", "h5", "h6" -> out.append("\n\n#### ");
                case "p", "div", "section", "article" -> out.append("\n\n");
                case "br" -> out.append("\n");
                case "li" -> out.append("\n- ");
                case "strong", "b" -> out.append("**");
                case "em", "i" -> out.append('*');
                case "blockquote" -> out.append("\n\n> ");
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
            switch (element.normalName()) {
                case "strong", "b" -> out.append("**");
                case "em", "i" -> out.append('*');
                case "a" -> {
                    String href = element.hasAttr("href") ? element.attr("href") : "";
                    if (!href.isBlank()) {
                        out.append(" (").append(href).append(')');
                    }
                }
                case "p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "blockquote" ->
                        out.append("\n\n");
                default -> {
                    // ignore
                }
            }
        }
    }
}
