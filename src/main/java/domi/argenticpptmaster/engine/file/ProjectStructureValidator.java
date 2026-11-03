// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import domi.argenticpptmaster.engine.EngineResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Java port of ppt-master {@code project_manager.py validate}
 * ({@code project_utils.validate_project_structure} + {@code validate_svg_viewbox}).
 *
 * <p>Output text and exit codes are a zero-diff replica of the Python CLI. Known cosmetic
 * deviations: the multi-viewBox summary renders the set in Python repr shape but in
 * insertion order (Python set order is hash-based and not reproducible).</p>
 */
final class ProjectStructureValidator {

    private static final String README_FILE = "README.md";
    private static final String SVG_OUTPUT_DIR = "svg_output";
    private static final String SEPARATOR = "=".repeat(60);
    private static final int INVALID_EXIT = 1;
    private static final int VALID_EXIT = 0;
    /** Python reads at most this many characters when scanning for the viewBox attribute. */
    private static final int VIEWBOX_SCAN_CHARS = 2000;
    /** Python validates at most this many SVG files, taken from the sorted filename list. */
    private static final int VIEWBOX_MAX_FILES = 10;

    private static final Pattern DATE_SUFFIX = Pattern.compile("_(\\d{8})$");
    // Python \w is unicode-aware on str patterns; SVG filenames are ASCII in practice.
    private static final Pattern SVG_NAMING = Pattern.compile("^(slide_\\d+_\\w+|P?\\d+_.+)\\.svg$");
    private static final Pattern VIEWBOX_ATTR = Pattern.compile("viewBox=\"([^\"]+)\"");

    private ProjectStructureValidator() {}

    /**
     * 执行 {@code validate} 命令：结构完整性 + viewBox 校验，按 Python 格式渲染。
     *
     * <p>viewBox 校验仅在目录名解析出已知画布格式时执行期望比对；格式为 {@code unknown}
     * 时跳过比对，但 viewBox 缺失或多个不同 viewBox 的通用警告仍生效（与 Python 一致）。</p>
     */
    static EngineResult validate(Path project) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            collect(project, errors, warnings);
        } catch (IOException | RuntimeException ex) {
            // Python main() catches all exceptions, prints [ERROR] and exits 1.
            return new EngineResult(INVALID_EXIT, "[ERROR] " + ex + "\n", false);
        }
        boolean valid = errors.isEmpty();
        return new EngineResult(valid ? VALID_EXIT : INVALID_EXIT, render(project, errors, warnings, valid), false);
    }

    private static void collect(Path project, List<String> errors, List<String> warnings) throws IOException {
        if (!Files.exists(project)) {
            errors.add("Project directory does not exist: " + project);
            return;
        }
        if (!Files.isDirectory(project)) {
            errors.add("Not a valid directory: " + project);
            return;
        }

        if (!Files.exists(project.resolve(README_FILE))) {
            errors.add("Missing required file: " + README_FILE);
        }

        boolean hasSpec = false;
        for (String candidate : ProjectInfoInspector.SPEC_CANDIDATES) {
            if (Files.exists(project.resolve(candidate))) {
                hasSpec = true;
                break;
            }
        }
        if (!hasSpec) {
            warnings.add("Missing design specification file (suggested filename: design_spec.md)");
        }

        Path svgOutput = project.resolve(SVG_OUTPUT_DIR);
        List<Path> svgFiles = List.of();
        if (!Files.exists(svgOutput)) {
            errors.add("Missing svg_output directory");
        } else if (!Files.isDirectory(svgOutput)) {
            errors.add("svg_output is not a directory");
        } else {
            try (Stream<Path> entries = Files.list(svgOutput)) {
                // TODO(port-project-info-validate-to-java): Python iterates os-dependent glob
                // order here; sorted order is deterministic. Validate all files, not just
                // viewBox's first 10, as a separate change.
                svgFiles = entries
                        .filter(entry -> entry.getFileName().toString().endsWith(".svg"))
                        .sorted(java.util.Comparator.comparing(entry -> entry.getFileName().toString()))
                        .toList();
            }
            if (svgFiles.isEmpty()) {
                warnings.add("svg_output directory is empty, no SVG files found");
            } else {
                for (Path svg : svgFiles) {
                    String fileName = svg.getFileName().toString();
                    if (!SVG_NAMING.matcher(fileName).matches()) {
                        warnings.add("Non-standard SVG file naming: " + fileName);
                    }
                }
            }
        }

        String dirName = project.getFileName().toString();
        if (!DATE_SUFFIX.matcher(dirName).find()) {
            warnings.add("Directory name missing date suffix (_YYYYMMDD): " + dirName);
        }

        if (!svgFiles.isEmpty()) {
            ProjectInfoInspector.ParsedName parsed =
                    ProjectInfoInspector.parseProjectName(dirName);
            warnings.addAll(viewBoxWarnings(svgFiles, parsed));
        }
    }

    private static List<String> viewBoxWarnings(List<Path> sortedSvgFiles, ProjectInfoInspector.ParsedName parsed)
            throws IOException {
        List<String> warnings = new ArrayList<>();
        Set<String> viewBoxes = new LinkedHashSet<>();
        String expectedViewBox = CanvasFormats.get(parsed.format())
                .map(CanvasFormats.CanvasFormat::viewBox)
                .orElse(null);

        int checked = 0;
        for (Path svg : sortedSvgFiles) {
            // TODO(port-project-info-validate-to-java): Python caps at 10 files / 2000 chars.
            // Consider checking all files as a separate change.
            if (checked++ >= VIEWBOX_MAX_FILES) {
                break;
            }
            String fileName = svg.getFileName().toString();
            final String head;
            try {
                head = ProjectInfoInspector.readHead(svg, VIEWBOX_SCAN_CHARS);
            } catch (IOException | RuntimeException ex) {
                warnings.add(fileName + ": Failed to read - " + ex);
                continue;
            }
            Matcher matcher = VIEWBOX_ATTR.matcher(head);
            if (matcher.find()) {
                String viewBox = matcher.group(1);
                viewBoxes.add(viewBox);
                if (expectedViewBox != null && !viewBox.equals(expectedViewBox)) {
                    warnings.add(fileName + ": viewBox '" + viewBox + "' does not match expected format '"
                            + parsed.format() + "' (expected: '" + expectedViewBox + "')");
                }
            } else {
                warnings.add(fileName + ": viewBox attribute not found");
            }
        }

        if (viewBoxes.size() > 1) {
            warnings.add("Multiple different viewBox settings detected: " + pythonSetRepr(viewBoxes));
        }
        return warnings;
    }

    /** Render like Python {@code str(set)}: {@code {'a', 'b'}} (order is insertion order here). */
    private static String pythonSetRepr(Set<String> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                out.append(", ");
            }
            out.append('\'').append(value).append('\'');
            first = false;
        }
        return out.append('}').toString();
    }

    private static String render(Path project, List<String> errors, List<String> warnings, boolean valid) {
        StringBuilder out = new StringBuilder();
        out.append('\n');
        out.append("Project validation: ").append(project).append('\n');
        out.append(SEPARATOR).append('\n');
        if (!errors.isEmpty()) {
            out.append('\n').append("[ERROR]").append('\n');
            for (String error : errors) {
                out.append("  - ").append(error).append('\n');
            }
        }
        if (!warnings.isEmpty()) {
            out.append('\n').append("[WARN]").append('\n');
            for (String warning : warnings) {
                out.append("  - ").append(warning).append('\n');
            }
        }
        if (valid && warnings.isEmpty()) {
            out.append('\n').append("[OK] Project structure is complete.").append('\n');
        } else if (valid) {
            out.append('\n').append("[OK] Project structure is valid, with warnings.").append('\n');
        } else {
            out.append('\n').append("[ERROR] Project structure is invalid.").append('\n');
        }
        return out.toString();
    }
}
