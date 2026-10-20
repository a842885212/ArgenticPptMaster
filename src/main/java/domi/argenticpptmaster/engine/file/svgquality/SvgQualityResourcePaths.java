// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Project-relative SVG resource paths (from ppt-master {@code resource_paths.py}). */
final class SvgQualityResourcePaths {

    private static final Set<String> SVG_WORK_DIR_NAMES = Set.of(
            "authoring-svg",
            "authoring-svg-flat",
            "svg",
            "svg_output",
            "svg_final",
            "svg-flat");
    private static final String SVG_FINAL_CANDIDATE_PREFIX = ".svg_final.candidate-";
    private static final String TEMPLATE_SOURCE_DIR_NAME = "templates";
    private static final String TEMPLATE_SPEC_FILENAME = "design_spec.md";
    private static final Pattern TEMPLATE_QUALIFIED_SPEC =
            Pattern.compile("design_spec\\.(?:brand|style|layout|deck)\\.[^/\\\\]+\\.md");

    private SvgQualityResourcePaths() {}

    static Path projectRootForSvgPath(Path svgPath) {
        Path path = svgPath;
        Path base = Files.isDirectory(path) ? path : path.getParent();
        if (base == null) {
            return path;
        }
        String baseName = base.getFileName().toString();
        if (SVG_WORK_DIR_NAMES.contains(baseName) || baseName.startsWith(SVG_FINAL_CANDIDATE_PREFIX)) {
            Path parent = base.getParent();
            return parent != null ? parent : base;
        }
        if (baseName.equals(TEMPLATE_SOURCE_DIR_NAME) && isTemplateSourceDir(base)) {
            Path parent = base.getParent();
            return parent != null ? parent : base;
        }
        return base;
    }

    private static boolean isTemplateSourceDir(Path base) {
        if (Files.isRegularFile(base.resolve(TEMPLATE_SPEC_FILENAME))) {
            return true;
        }
        try (var stream = Files.newDirectoryStream(base, "design_spec.*.md")) {
            for (Path candidate : stream) {
                if (TEMPLATE_QUALIFIED_SPEC.matcher(candidate.getFileName().toString()).matches()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Best-effort template detection.
        }
        return false;
    }

    static Path iconDirForSvg(Path svgPath) {
        return projectRootForSvgPath(svgPath).resolve("icons");
    }

    /**
     * Resolve one exact SVG-relative image reference inside the project, or empty if the href
     * is not a simple in-project relative path.
     */
    static Optional<Path> externalImageReferencePath(Path svgDir, String href, Path projectRoot) {
        if (href == null) {
            return Optional.empty();
        }
        URI parsed;
        try {
            parsed = new URI(href.strip());
        } catch (Exception e) {
            return Optional.empty();
        }
        String scheme = parsed.getScheme();
        if (scheme != null && !scheme.isEmpty()) {
            return Optional.empty();
        }
        if (parsed.getHost() != null) {
            return Optional.empty();
        }
        if (parsed.getRawQuery() != null) {
            return Optional.empty();
        }
        if (parsed.getRawFragment() != null) {
            return Optional.empty();
        }
        String pathPart = parsed.getPath();
        if (pathPart == null || pathPart.isEmpty()) {
            return Optional.empty();
        }
        String decoded = URLDecoder.decode(pathPart, StandardCharsets.UTF_8);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        Path relative = Path.of(decoded);
        if (relative.isAbsolute()) {
            return Optional.empty();
        }
        Path resolvedProjectRoot = projectRoot != null
                ? projectRoot.toAbsolutePath().normalize()
                : projectRootForSvgPath(svgDir).toAbsolutePath().normalize();
        Path resolved = svgDir.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(resolvedProjectRoot)) {
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    static Optional<Path> externalImageReferencePath(Path svgDir, String href) {
        return externalImageReferencePath(svgDir, href, null);
    }
}
