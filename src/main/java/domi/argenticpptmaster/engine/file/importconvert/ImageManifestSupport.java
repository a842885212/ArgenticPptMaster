package domi.argenticpptmaster.engine.file.importconvert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.engine.ooxml.templatefill.TfXml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * 图片落盘、去重与 {@code image_manifest.json} 生成，对齐 {@code ppt_to_md.py}
 * {@code save_picture} / {@code _manifest_entry} / {@code _update_manifest_usage}。
 *
 * <p>去重键为媒体 part 路径（{@code ppt/media/...}）；文件名优先取媒体 basename，
 * 冲突时 {@code _2}/{@code _3} 递增；manifest 字段顺序与 Python dict 一致（JSON 文本对比依赖）。
 * 像素尺寸用 {@link ImageIO} 解码（对齐 Pillow），解码失败与矢量图一律记 null。</p>
 */
final class ImageManifestSupport {

    static final String MANIFEST_FILE = "image_manifest.json";
    static final long EMU_PER_INCH = 914400;

    private static final Set<String> OFFICE_VECTOR_EXTENSIONS = Set.of("emf", "wmf");
    private static final Pattern SANITIZE = Pattern.compile("[^\\w.\\-]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern LEGACY_GENERATED_IMAGE =
            Pattern.compile("^slide_\\d{2}_image_\\d{2}\\.[A-Za-z0-9]+$");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ImageManifestSupport() {}

    /** [Content_Types].xml 的 Default 扩展名 → content type 映射（每次转换解析一次）。 */
    record PackageContentTypes(Map<String, String> byExtension) {

        static PackageContentTypes parse(Path extractDir) {
            Map<String, String> byExtension = new LinkedHashMap<>();
            Path contentTypes = extractDir.resolve("[Content_Types].xml");
            if (Files.isRegularFile(contentTypes)) {
                try {
                    Document doc = TfXml.parse(Files.readAllBytes(contentTypes));
                    for (Element def : TfXml.descendants(doc.getDocumentElement(), "Default")) {
                        String ext = def.getAttribute("Extension");
                        if (ext != null && !ext.isBlank()) {
                            byExtension.put(ext.toLowerCase(Locale.ROOT), nullToEmpty(def.getAttribute("ContentType")));
                        }
                    }
                } catch (Exception ignored) {
                    // content type 缺失不阻断转换，content_type 字段留空
                }
            }
            return new PackageContentTypes(byExtension);
        }

        String forPart(String partName) {
            String ext = extension(partName);
            return ext.isEmpty() ? null : byExtension.get(ext);
        }
    }

    /** 转换级状态：资产缓存（part → 已存资产）、已用文件名、manifest 条目（保持首用顺序）。 */
    static final class AssetState {
        private final Map<String, SavedAsset> cache = new LinkedHashMap<>();
        private final Set<String> usedFilenames = new HashSet<>();
        private final List<ObjectNode> manifest = new ArrayList<>();

        Map<String, SavedAsset> cache() {
            return cache;
        }

        Set<String> usedFilenames() {
            return usedFilenames;
        }

        List<ObjectNode> manifest() {
            return manifest;
        }
    }

    /** 已保存资产：文件名 + manifest 条目（occurrences 随命中追加）。 */
    record SavedAsset(String filename, ObjectNode manifestEntry, boolean newAsset) {}

    /**
     * 保存形状引用的图片（part 由调用方经 slide rels 解析）；part 已缓存时仅追加 occurrence 并复用文件名。
     * 返回 null 表示形状无可用图片（调用方决定占位文本）。
     */
    static SavedAsset savePicture(
            Element shape,
            int slideIndex,
            Path partPath,
            String partName,
            PackageContentTypes contentTypes,
            Path assetDir,
            AssetState state,
            int nextAssetIndex) throws IOException {
        if (partPath == null || !Files.isRegularFile(partPath)) {
            return null;
        }
        byte[] blob = Files.readAllBytes(partPath);
        if (blob.length == 0) {
            return null;
        }
        String contentType = contentTypes.forPart(partName);
        String ext = normalizeExt(extension(partName), contentType);
        ObjectNode occurrence = occurrence(shape, slideIndex);

        SavedAsset cached = state.cache().get(partName);
        if (cached != null) {
            ArrayNode occurrences = (ArrayNode) cached.manifestEntry().withArray("occurrences");
            occurrences.add(occurrence);
            updateUsage(cached.manifestEntry());
            return new SavedAsset(cached.filename(), cached.manifestEntry(), false);
        }

        String filename = uniqueFilename(sanitizeFilename(filenameBase(partName, nextAssetIndex, ext)), ext, state);
        Files.createDirectories(assetDir);
        Files.copy(partPath, assetDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);

        ObjectNode entry = manifestEntry(nextAssetIndex, filename, partName, ext, blob, contentType, occurrence);
        updateUsage(entry);
        SavedAsset saved = new SavedAsset(filename, entry, true);
        state.cache().put(partName, saved);
        state.manifest().add(entry);
        return saved;
    }

    /** 按 Python {@code _reset_generated_asset_dir} 语义清理旧产物目录。 */
    static void resetGeneratedAssetDir(Path assetDir) throws IOException {
        if (!Files.exists(assetDir)) {
            return;
        }
        if (!Files.isRegularFile(assetDir.resolve(MANIFEST_FILE))) {
            try (var stream = Files.list(assetDir)) {
                for (Path path : stream.filter(Files::isRegularFile).toList()) {
                    if (LEGACY_GENERATED_IMAGE.matcher(path.getFileName().toString()).matches()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
            return;
        }
        deleteRecursively(assetDir);
    }

    static void writeManifest(Path assetDir, AssetState state) throws IOException {
        if (state.manifest().isEmpty()) {
            return;
        }
        ArrayNode root = OBJECT_MAPPER.createArrayNode();
        state.manifest().forEach(root::add);
        Files.writeString(
                assetDir.resolve(MANIFEST_FILE),
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
    }

    private static ObjectNode manifestEntry(
            int index, String filename, String partName, String ext,
            byte[] blob, String contentType, ObjectNode occurrence) {
        ObjectNode entry = OBJECT_MAPPER.createObjectNode();
        int[] pixels = pixelSize(blob, ext);
        Integer pixelWidth = pixels[0] > 0 ? pixels[0] : null;
        Integer pixelHeight = pixels[1] > 0 ? pixels[1] : null;
        Double pixelRatio = pixelWidth != null && pixelHeight != null && pixelHeight > 0
                ? round((double) pixelWidth / pixelHeight, 6)
                : null;
        boolean vector = OFFICE_VECTOR_EXTENSIONS.contains(ext);

        entry.put("index", index);
        entry.put("filename", filename);
        entry.put("original_filename", filename);
        entry.put("asset_kind", vector ? "office_vector" : "bitmap");
        entry.put("svg_renderable", !vector);
        entry.put("pptx_native_supported", true);
        entry.put("source_kind", "pptx_picture");
        entry.put("source_ext", "." + ext);
        entry.put("source_target", partName);
        entry.put("content_type", nullToEmpty(contentType));
        entry.set("display_left_emu", occurrence.get("display_left_emu"));
        entry.set("display_top_emu", occurrence.get("display_top_emu"));
        entry.set("display_width_emu", occurrence.get("display_width_emu"));
        entry.set("display_height_emu", occurrence.get("display_height_emu"));
        entry.set("display_width_in", occurrence.get("display_width_in"));
        entry.set("display_height_in", occurrence.get("display_height_in"));
        entry.set("display_ratio", occurrence.get("display_ratio"));
        if (entry.get("display_ratio").isNull() && pixelRatio != null) {
            entry.put("display_ratio", pixelRatio);
        }
        if (pixelWidth != null) {
            entry.put("pixel_width", pixelWidth);
        } else {
            entry.putNull("pixel_width");
        }
        if (pixelHeight != null) {
            entry.put("pixel_height", pixelHeight);
        } else {
            entry.putNull("pixel_height");
        }
        if (pixelRatio != null) {
            entry.put("pixel_ratio", pixelRatio);
        } else {
            entry.putNull("pixel_ratio");
        }
        ArrayNode occurrences = entry.putArray("occurrences");
        occurrences.add(occurrence);
        return entry;
    }

    /** 对齐 {@code _update_manifest_usage}：usage_count = occurrences 数；ratio 变体升序去重；首个 ratio 回填顶层。 */
    private static void updateUsage(ObjectNode entry) {
        ArrayNode occurrences = (ArrayNode) entry.get("occurrences");
        entry.put("usage_count", occurrences.size());
        TreeSet<Double> ratios = new TreeSet<>();
        for (JsonNode node : occurrences) {
            JsonNode ratio = node.get("display_ratio");
            if (ratio != null && ratio.isNumber()) {
                ratios.add(ratio.asDouble());
            }
        }
        if (!ratios.isEmpty()) {
            ArrayNode variants = entry.putArray("display_ratio_variants");
            ratios.forEach(variants::add);
            JsonNode top = entry.get("display_ratio");
            if (top == null || top.isNull()) {
                entry.put("display_ratio", ratios.first());
            }
        }
    }

    private static ObjectNode occurrence(Element shape, int slideIndex) {
        long width = emu(shape, "width");
        long height = emu(shape, "height");
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("slide_index", slideIndex);
        node.put("shape_name", ReadbackShapes.shapeName(shape));
        node.put("display_left_emu", emu(shape, "left"));
        node.put("display_top_emu", emu(shape, "top"));
        node.put("display_width_emu", width);
        node.put("display_height_emu", height);
        if (width > 0) {
            node.put("display_width_in", round(width / (double) EMU_PER_INCH, 4));
        } else {
            node.putNull("display_width_in");
        }
        if (height > 0) {
            node.put("display_height_in", round(height / (double) EMU_PER_INCH, 4));
        } else {
            node.putNull("display_height_in");
        }
        if (width > 0 && height > 0) {
            node.put("display_ratio", round(width / (double) height, 6));
        } else {
            node.putNull("display_ratio");
        }
        return node;
    }

    private static long emu(Element shape, String attr) {
        Element xfrm = switch (shape.getLocalName()) {
            case "graphicFrame" -> TfXml.firstChild(shape, "xfrm");
            default -> {
                Element spPr = TfXml.firstChild(shape, "spPr");
                yield spPr == null ? null : TfXml.firstChild(spPr, "xfrm");
            }
        };
        if (xfrm == null) {
            return 0;
        }
        String value;
        if ("width".equals(attr) || "height".equals(attr)) {
            Element ext = TfXml.firstChild(xfrm, "ext");
            if (ext == null) {
                return 0;
            }
            value = "width".equals(attr) ? ext.getAttribute("cx") : ext.getAttribute("cy");
        } else {
            Element off = TfXml.firstChild(xfrm, "off");
            if (off == null) {
                return 0;
            }
            value = "left".equals(attr) ? off.getAttribute("x") : off.getAttribute("y");
        }
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int[] pixelSize(byte[] blob, String ext) {
        if (OFFICE_VECTOR_EXTENSIONS.contains(ext) || "svg".equals(ext)) {
            return new int[] {0, 0};
        }
        try (InputStream in = new java.io.ByteArrayInputStream(blob)) {
            var image = ImageIO.read(in);
            return image == null ? new int[] {0, 0} : new int[] {image.getWidth(), image.getHeight()};
        } catch (IOException ex) {
            return new int[] {0, 0};
        }
    }

    private static String filenameBase(String partName, int index, String ext) {
        String base = partName.substring(partName.lastIndexOf('/') + 1);
        return base.isBlank() ? "image_" + String.format(Locale.ROOT, "%03d", index) + "." + ext : base;
    }

    /** 对齐 {@code sanitize_filename}：非 {@code \\w . -} 字符替换为下划线，剥去首尾 {@code ._}，空则 asset。 */
    private static String sanitizeFilename(String value) {
        String sanitized = SANITIZE.matcher(value).replaceAll("_").replaceAll("^[._]+|[._]+$", "");
        return sanitized.isBlank() ? "asset" : sanitized;
    }

    private static String uniqueFilename(String base, String ext, AssetState state) {
        if (!base.contains(".")) {
            base = base + "." + ext;
        }
        if (state.usedFilenames().add(base)) {
            return base;
        }
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String suffix = dot > 0 ? base.substring(dot) : "." + ext;
        int counter = 2;
        while (true) {
            String candidate = stem + "_" + counter + suffix;
            if (state.usedFilenames().add(candidate)) {
                return candidate;
            }
            counter++;
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeExt(String ext, String contentType) {
        if (ext != null && !ext.isBlank()) {
            return "jpeg".equals(ext) ? "jpg" : ext;
        }
        if (contentType != null) {
            return switch (contentType.toLowerCase(Locale.ROOT)) {
                case "image/bmp" -> "bmp";
                case "image/gif" -> "gif";
                case "image/jpeg", "image/jpg" -> "jpg";
                case "image/png" -> "png";
                case "image/svg+xml" -> "svg";
                case "image/tiff" -> "tiff";
                case "image/x-emf" -> "emf";
                case "image/x-wmf" -> "wmf";
                default -> "bin";
            };
        }
        return "bin";
    }

    private static double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            List<Path> paths = new ArrayList<>();
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(paths::add);
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
