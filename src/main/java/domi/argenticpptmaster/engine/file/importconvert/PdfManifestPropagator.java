package domi.argenticpptmaster.engine.file.importconvert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * PDF-only image propagation gated by {@code sources/<stem>_files/image_manifest.json},
 * aligned with upstream Python {@code _propagate_*} semantics for PDF imports.
 */
public final class PdfManifestPropagator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PdfManifestPropagator() {}

    /**
     * Copies bitmap files listed in the sidecar manifest into {@code project/images/} and merges
     * entries into {@code project/images/image_manifest.json}.
     *
     * @return number of image files copied
     */
    public static int propagate(Path assetDir, Path projectDir) throws IOException {
        if (assetDir == null || !Files.isDirectory(assetDir)) {
            return 0;
        }
        Path manifestPath = assetDir.resolve("image_manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            return 0;
        }
        JsonNode root = MAPPER.readTree(Files.readString(manifestPath));
        if (!root.isArray() || root.isEmpty()) {
            return 0;
        }

        Path imagesDir = projectDir.resolve("images");
        Files.createDirectories(imagesDir);
        Path projectManifest = imagesDir.resolve("image_manifest.json");
        ArrayNode merged = loadOrCreateArray(projectManifest);

        int copied = 0;
        for (JsonNode entry : root) {
            if (entry == null || !entry.isObject()) {
                continue;
            }
            String filename = text(entry, "filename");
            if (filename == null || filename.isBlank()) {
                continue;
            }
            String kind = text(entry, "asset_kind");
            if (kind != null && !"bitmap".equalsIgnoreCase(kind)) {
                continue;
            }
            Path source = assetDir.resolve(filename).normalize();
            if (!source.startsWith(assetDir.toAbsolutePath().normalize()) && !source.startsWith(assetDir)) {
                // best-effort containment; also accept relative resolve under assetDir
                if (!source.getParent().equals(assetDir)) {
                    continue;
                }
            }
            if (!Files.isRegularFile(source)) {
                continue;
            }
            if (!isBitmapSuffix(filename)) {
                continue;
            }
            Path dest = uniquePath(imagesDir.resolve(source.getFileName()));
            Files.copy(source, dest, StandardCopyOption.COPY_ATTRIBUTES);
            copied++;

            ObjectNode copy = entry.deepCopy();
            copy.put("filename", dest.getFileName().toString());
            if (!containsFilename(merged, dest.getFileName().toString())) {
                merged.add(copy);
            }
        }

        Files.writeString(
                projectManifest,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(merged) + "\n");
        return copied;
    }

    private static ArrayNode loadOrCreateArray(Path projectManifest) throws IOException {
        if (!Files.isRegularFile(projectManifest)) {
            return MAPPER.createArrayNode();
        }
        JsonNode existing = MAPPER.readTree(Files.readString(projectManifest));
        if (existing != null && existing.isArray()) {
            return (ArrayNode) existing.deepCopy();
        }
        return MAPPER.createArrayNode();
    }

    private static boolean containsFilename(ArrayNode array, String filename) {
        for (JsonNode node : array) {
            if (filename.equals(text(node, "filename"))) {
                return true;
            }
        }
        return false;
    }

    private static Path uniquePath(Path desired) throws IOException {
        if (!Files.exists(desired)) {
            return desired;
        }
        String name = desired.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : "";
        Path parent = desired.getParent();
        for (int i = 2; i < 10_000; i++) {
            Path candidate = parent.resolve(stem + "_" + i + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Could not allocate unique image path for " + desired);
    }

    private static boolean isBitmapSuffix(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp")
                || lower.endsWith(".bmp")
                || lower.endsWith(".tif")
                || lower.endsWith(".tiff");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
