package domi.argenticpptmaster.engine.file.importconvert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Document → Markdown via system pandoc ({@code ProcessBuilder}), contract-aligned with
 * upstream {@code doc_to_md._convert_with_pandoc}.
 */
public final class PandocToMarkdownConverter {

    private static final Set<String> IMAGE_ASSET_SUFFIXES = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".tiff", ".tif", ".emf", ".wmf", ".svg");
    private static final Set<String> OFFICE_VECTOR = Set.of(".emf", ".wmf");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern HTML_IMG_TAG = Pattern.compile(
            "<img\\b([^>]*)/?>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile(
            "\\b(src|alt)\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);

    private final String pandocCommand;
    private final ProcessRunner processRunner;

    public PandocToMarkdownConverter() {
        this("pandoc", ProcessRunner.SYSTEM);
    }

    public PandocToMarkdownConverter(String pandocCommand) {
        this(pandocCommand, ProcessRunner.SYSTEM);
    }

    public PandocToMarkdownConverter(String pandocCommand, ProcessRunner processRunner) {
        this.pandocCommand =
                pandocCommand == null || pandocCommand.isBlank() ? "pandoc" : pandocCommand.strip();
        this.processRunner = processRunner == null ? ProcessRunner.SYSTEM : processRunner;
    }

    public Path convert(Path inputPath, Path markdownPath) throws ImportConversionException {
        String suffix = suffixLower(inputPath);
        String inputFormat = ImportSourceFormats.PANDOC_DOCS.get(suffix);
        if (inputFormat == null) {
            throw new ImportConversionException("Unsupported pandoc suffix: " + suffix);
        }

        try {
            Files.createDirectories(markdownPath.getParent());
            String relMediaDir = stem(markdownPath) + "_files";
            Path mediaDir = markdownPath.resolveSibling(relMediaDir);
            deleteRecursively(mediaDir);

            List<String> command = buildCommand(inputPath, markdownPath, inputFormat, relMediaDir);
            ProcessResult result = processRunner.run(command, markdownPath.getParent());
            if (result.exitCode() == 127
                    || (result.exitCode() != 0
                            && looksLikeMissingBinary(result.stderr(), result.exceptionMessage()))) {
                throw new ImportConversionException(
                        "pandoc is not available (command='"
                                + pandocCommand
                                + "'). Install pandoc (macOS: brew install pandoc; "
                                + "Ubuntu: sudo apt install pandoc) or set ppt-master.pandoc-command.");
            }
            if (result.exitCode() != 0) {
                String detail = result.stderr() == null || result.stderr().isBlank()
                        ? result.stdout()
                        : result.stderr();
                throw new ImportConversionException(
                        "Pandoc conversion failed for "
                                + inputPath.getFileName()
                                + " (exit="
                                + result.exitCode()
                                + "): "
                                + truncate(detail));
            }
            if (!Files.isRegularFile(markdownPath)) {
                throw new ImportConversionException(
                        "Pandoc completed but no Markdown was written for " + inputPath.getFileName());
            }

            String markdown = Files.readString(markdownPath, StandardCharsets.UTF_8);
            markdown = flattenMedia(mediaDir, relMediaDir, markdown);
            markdown = normalizeAbsoluteMediaPaths(mediaDir, relMediaDir, markdown);
            markdown = htmlImgToMd(markdown);
            Files.writeString(markdownPath, markdown, StandardCharsets.UTF_8);

            if (Files.isDirectory(mediaDir)) {
                writePandocImageManifest(mediaDir, relMediaDir, markdown);
                if (isEmptyDir(mediaDir)) {
                    deleteRecursively(mediaDir);
                }
            }
            return markdownPath;
        } catch (ImportConversionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ImportConversionException(
                    "Pandoc conversion failed for " + inputPath + ": " + ex.getMessage(), ex);
        }
    }

    /** Visible for tests: exact argv passed to pandoc. */
    List<String> buildCommand(Path inputPath, Path markdownPath, String inputFormat, String relMediaDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(pandocCommand);
        cmd.add("-f");
        cmd.add(inputFormat);
        cmd.add("-t");
        cmd.add("gfm");
        cmd.add(inputPath.toAbsolutePath().normalize().toString());
        cmd.add("-o");
        cmd.add(markdownPath.toAbsolutePath().normalize().toString());
        cmd.add("--wrap");
        cmd.add("none");
        cmd.add("--strip-comments");
        cmd.add("--extract-media");
        cmd.add(relMediaDir);
        return List.copyOf(cmd);
    }

    private static String flattenMedia(Path mediaDir, String relMediaDir, String markdown)
            throws IOException {
        Path nested = mediaDir.resolve("media");
        if (!Files.isDirectory(nested)) {
            return markdown;
        }
        try (Stream<Path> stream = Files.list(nested)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                Path target = mediaDir.resolve(file.getFileName());
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try {
            Files.deleteIfExists(nested);
        } catch (IOException ignored) {
            // nested may still hold dirs; best-effort
        }
        return markdown.replace(relMediaDir + "/media/", relMediaDir + "/");
    }

    private static String normalizeAbsoluteMediaPaths(Path mediaDir, String relMediaDir, String markdown) {
        String updated = markdown;
        String absUnix = mediaDir.toAbsolutePath().normalize().toString().replace('\\', '/');
        String absNative = mediaDir.toAbsolutePath().normalize().toString();
        if (updated.contains(absUnix)) {
            updated = updated.replace(absUnix, relMediaDir);
        }
        if (!absNative.equals(absUnix) && updated.contains(absNative)) {
            updated = updated.replace(absNative, relMediaDir);
        }
        return updated;
    }

    static String htmlImgToMd(String markdown) {
        Matcher matcher = HTML_IMG_TAG.matcher(markdown);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String attrs = matcher.group(1);
            String src = null;
            String alt = null;
            Matcher attrMatcher = ATTR.matcher(attrs);
            while (attrMatcher.find()) {
                String name = attrMatcher.group(1).toLowerCase(Locale.ROOT);
                String value = attrMatcher.group(2);
                if ("src".equals(name)) {
                    src = value;
                } else if ("alt".equals(name)) {
                    alt = value;
                }
            }
            if (src == null || src.isBlank()) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            if (alt == null || alt.isBlank()) {
                alt = stemOf(src);
            }
            matcher.appendReplacement(
                    sb, Matcher.quoteReplacement("![" + alt + "](" + src + ")"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static void writePandocImageManifest(Path mediaDir, String relMediaDir, String markdown)
            throws IOException {
        Pattern refPattern = Pattern.compile(Pattern.quote(relMediaDir) + "/([^)\\s]+)");
        Map<String, List<ObjectNode>> occurrenceMap = new LinkedHashMap<>();
        Matcher matcher = refPattern.matcher(markdown);
        int occurrenceIndex = 0;
        while (matcher.find()) {
            occurrenceIndex++;
            String filename = Path.of(matcher.group(1)).getFileName().toString();
            ObjectNode occurrence = MAPPER.createObjectNode();
            occurrence.put("occurrence_index", occurrenceIndex);
            occurrence.put("source_ref", relMediaDir + "/" + filename);
            occurrenceMap.computeIfAbsent(filename, key -> new ArrayList<>()).add(occurrence);
        }

        ArrayNode manifest = MAPPER.createArrayNode();
        try (Stream<Path> stream = Files.list(mediaDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : files) {
                String ext = suffixLower(file);
                if (!IMAGE_ASSET_SUFFIXES.contains(ext)) {
                    continue;
                }
                String assetKind = OFFICE_VECTOR.contains(ext) ? "office_vector" : "bitmap";
                List<ObjectNode> occurrences = occurrenceMap.getOrDefault(file.getFileName().toString(), List.of());
                ObjectNode entry = MAPPER.createObjectNode();
                entry.put("index", manifest.size() + 1);
                entry.put("filename", file.getFileName().toString());
                entry.put("original_filename", file.getFileName().toString());
                entry.put("asset_kind", assetKind);
                entry.put("svg_renderable", !"office_vector".equals(assetKind));
                entry.put("pptx_native_supported", true);
                entry.put("source_kind", "pandoc_image");
                entry.put("source_ext", ext);
                entry.putNull("pixel_width");
                entry.putNull("pixel_height");
                entry.putNull("pixel_ratio");
                entry.putNull("display_ratio");
                ArrayNode occArray = entry.putArray("occurrences");
                for (ObjectNode occurrence : occurrences) {
                    occArray.add(occurrence);
                }
                entry.put("usage_count", occurrences.isEmpty() ? 1 : occurrences.size());
                manifest.add(entry);
            }
        }
        if (!manifest.isEmpty()) {
            Files.writeString(
                    mediaDir.resolve("image_manifest.json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n",
                    StandardCharsets.UTF_8);
        }
    }

    private static boolean looksLikeMissingBinary(String stderr, String exceptionMessage) {
        String haystack = ((stderr == null ? "" : stderr) + " " + (exceptionMessage == null ? "" : exceptionMessage))
                .toLowerCase(Locale.ROOT);
        return haystack.contains("no such file")
                || haystack.contains("cannot find")
                || haystack.contains("error=2")
                || haystack.contains("not found")
                || haystack.contains("cannot run program");
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : all) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) + "…" : trimmed;
    }

    private static String stemOf(String pathOrUrl) {
        String name = Path.of(pathOrUrl.replace('\\', '/')).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String suffixLower(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    /** Pluggable process runner for unit tests. */
    @FunctionalInterface
    public interface ProcessRunner {
        ProcessRunner SYSTEM = (command, cwd) -> {
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                if (cwd != null) {
                    builder.directory(cwd.toFile());
                }
                builder.redirectErrorStream(false);
                Process process = builder.start();
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                int code = process.waitFor();
                return new ProcessResult(code, stdout, stderr, null);
            } catch (IOException ex) {
                return new ProcessResult(127, "", "", ex.getMessage());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return new ProcessResult(1, "", "interrupted", ex.getMessage());
            }
        };

        ProcessResult run(List<String> command, Path cwd);
    }

    public record ProcessResult(int exitCode, String stdout, String stderr, String exceptionMessage) {}
}
