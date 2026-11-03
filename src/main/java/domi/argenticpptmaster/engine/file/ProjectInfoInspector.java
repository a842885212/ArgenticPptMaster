// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import domi.argenticpptmaster.engine.EngineResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Java port of ppt-master {@code project_manager.py info} ({@code project_utils.get_project_info}).
 *
 * <p>Output text is a zero-diff replica of the Python CLI, including the leading blank
 * line and the 60-character separator.</p>
 */
final class ProjectInfoInspector {

    /** Candidate design-spec filenames checked at the project root (Python order). */
    static final List<String> SPEC_CANDIDATES = List.of(
            "design_spec.md", "设计规范与内容大纲.md", "design_specification.md", "设计规范.md");

    private static final String LEGACY_SOURCE_FILE = "来源文档.md";
    private static final String SOURCES_DIR = "sources";
    private static final String SVG_OUTPUT_DIR = "svg_output";
    private static final String SEPARATOR = "=".repeat(60);
    private static final int UNKNOWN_EXIT = 0;

    private static final Pattern DATE_SUFFIX = Pattern.compile("_(\\d{8})$");
    private static final Pattern FULL_NAME =
            Pattern.compile("^(.+)_([a-z0-9_-]+)_(\\d{8})$");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private ProjectInfoInspector() {}

    /** Result of parsing a project directory name, mirroring Python {@code parse_project_name}. */
    record ParsedName(String name, String format, String formatName, String dateFormatted) {

        static ParsedName unknown(String dirName) {
            return new ParsedName(dirName, "unknown", "Unknown format", "Unknown date");
        }
    }

    /**
     * 解析项目目录名（{@code name_format_YYYYMMDD}，大小写不敏感，format 走 alias 归一化）。
     *
     * <p>Faithful port of Python {@code parse_project_name}: full match first (greedy name group,
     * normalized format), then fallback suffix match {@code _format(?:_YYYYMMDD)?$} over format
     * keys sorted by length descending.</p>
     */
    static ParsedName parseProjectName(String dirName) {
        ParsedName parsed = ParsedName.unknown(dirName);
        String dateFormatted = "Unknown date";
        Matcher dateMatcher = DATE_SUFFIX.matcher(dirName);
        if (dateMatcher.find()) {
            dateFormatted = formatCompactDate(dateMatcher.group(1));
        }

        String format = "unknown";
        String formatName = "Unknown format";
        String name = dirName;

        Matcher full = FULL_NAME.matcher(dirName.toLowerCase(Locale.ROOT));
        if (full.matches()) {
            String normalized = CanvasFormats.normalize(full.group(2));
            Optional<CanvasFormats.CanvasFormat> canvas = CanvasFormats.get(normalized);
            if (canvas.isPresent()) {
                return new ParsedName(
                        dirName.substring(0, full.group(1).length()), normalized, canvas.get().name(), dateFormatted);
            }
        }

        String lower = dirName.toLowerCase(Locale.ROOT);
        List<String> keys = CanvasFormats.keys().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String key : keys) {
            if (Pattern.compile("_" + Pattern.quote(key) + "(?:_\\d{8})?$")
                    .matcher(lower)
                    .find()) {
                format = key;
                formatName = CanvasFormats.get(key).map(CanvasFormats.CanvasFormat::name).orElse("Unknown format");
                break;
            }
        }

        // Name cleanup mirrors Python re.sub order: trailing date first, then trailing format.
        name = DATE_SUFFIX.matcher(name).replaceFirst("");
        if (!"unknown".equals(format)) {
            name = name.replaceFirst("(?i)_" + Pattern.quote(format) + "$", "");
        }
        return new ParsedName(name, format, formatName, dateFormatted);
    }

    /**
     * 执行 {@code info} 命令：探测项目结构并以 Python 原文格式渲染。
     *
     * <p>目录不存在时仍 exit 0 并输出 {@code Exists: No}（与 Python 对齐）。</p>
     */
    static EngineResult info(Path project) {
        ParsedName parsed = parseProjectName(project.getFileName().toString());
        boolean exists = Files.exists(project);
        boolean hasSpec = false;
        boolean hasSource = false;
        int sourceCount = 0;
        int svgCount = 0;
        if (exists) {
            for (String candidate : SPEC_CANDIDATES) {
                if (Files.exists(project.resolve(candidate))) {
                    hasSpec = true;
                    break;
                }
            }
            hasSource = Files.exists(project.resolve(LEGACY_SOURCE_FILE))
                    || Files.exists(project.resolve(SOURCES_DIR));
            Path sources = project.resolve(SOURCES_DIR);
            if (Files.exists(sources)) {
                // TODO(port-project-info-validate-to-java): Python counts only direct entries
                // (non-recursive). Consider recursive counting as a separate change.
                try (Stream<Path> entries = Files.list(sources)) {
                    sourceCount = (int) entries.filter(Files::isRegularFile).count();
                } catch (IOException ex) {
                    throw new UncheckedIOException("failed to list sources: " + sources, ex);
                }
            }
            Path svgOutput = project.resolve(SVG_OUTPUT_DIR);
            if (Files.exists(svgOutput)) {
                try (Stream<Path> entries = Files.list(svgOutput)) {
                    svgCount = (int) entries
                            .filter(entry -> entry.getFileName().toString().endsWith(".svg"))
                            .count();
                } catch (IOException ex) {
                    throw new UncheckedIOException("failed to list svg_output: " + svgOutput, ex);
                }
            }
        }
        return new EngineResult(UNKNOWN_EXIT, render(project, parsed, exists, hasSpec, hasSource, sourceCount, svgCount), false);
    }

    private static String formatCompactDate(String compact) {
        try {
            return LocalDate.parse(compact, COMPACT_DATE).format(DISPLAY_DATE);
        } catch (DateTimeParseException ex) {
            return "Unknown date";
        }
    }

    private static String render(
            Path project,
            ParsedName parsed,
            boolean exists,
            boolean hasSpec,
            boolean hasSource,
            int sourceCount,
            int svgCount) {
        StringBuilder out = new StringBuilder();
        out.append('\n');
        out.append("Project info: ").append(parsed.name()).append('\n');
        out.append(SEPARATOR).append('\n');
        out.append("Path: ").append(project).append('\n');
        out.append("Exists: ").append(exists ? "Yes" : "No").append('\n');
        out.append("SVG files: ").append(svgCount).append('\n');
        out.append("Design spec: ").append(hasSpec ? "Yes" : "No").append('\n');
        out.append("Source materials: ").append(hasSource ? "Yes" : "No").append('\n');
        out.append("Source count: ").append(sourceCount).append('\n');
        out.append("Canvas format: ").append(parsed.formatName()).append('\n');
        out.append("Created: ").append(parsed.dateFormatted()).append('\n');
        return out.toString();
    }

    /** Read at most the first {@code maxChars} characters, mirroring Python {@code f.read(n)}. */
    static String readHead(Path file, int maxChars) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return content.length() <= maxChars ? content : content.substring(0, maxChars);
    }
}
