// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.ProjectWorkspace;
import domi.argenticpptmaster.engine.file.importconvert.DocxToMarkdownConverter;
import domi.argenticpptmaster.engine.file.importconvert.ExcelToMarkdownConverter;
import domi.argenticpptmaster.engine.file.importconvert.HtmlToMarkdownConverter;
import domi.argenticpptmaster.engine.file.importconvert.ImportConversionException;
import domi.argenticpptmaster.engine.file.importconvert.ImportSourceFormats;
import domi.argenticpptmaster.engine.file.importconvert.PdfManifestPropagator;
import domi.argenticpptmaster.engine.file.importconvert.PdfToMarkdownConverter;
import domi.argenticpptmaster.engine.file.importconvert.PptxToMarkdownConverter;
import domi.argenticpptmaster.engine.ooxml.beautify.BeautifyIntakeOps;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * File-system project init/import with Java-authoritative converters for PPTX intake,
 * PPTX/Excel/docx/html/PDF → Markdown.
 */
public final class JavaProjectWorkspace implements ProjectWorkspace {

    private static final String SOURCE_DIRNAME = "sources";
    private static final Set<String> MARKDOWN_SUFFIXES = Set.of(".md", ".markdown");
    private static final Set<String> TABLE_TEXT_SUFFIXES = Set.of(".csv", ".tsv");
    private static final Set<String> BITMAP_IMAGE_SUFFIXES = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".tiff", ".tif");
    private static final Set<String> IMAGE_ASSET_SUFFIXES = union(
            BITMAP_IMAGE_SUFFIXES, Set.of(".emf", ".wmf", ".svg"));
    private static final Set<String> LEGACY_EXCEL_SUFFIXES = Set.of(".xls");
    private static final Set<String> PYTHON_LONG_TAIL_DOCS = Set.of(
            ".doc", ".odt", ".rtf", ".epub", ".tex", ".latex", ".rst", ".org", ".ipynb", ".typ");

    private static final String JAVA_NO_CONVERSION_NOTE =
            "archived only; automatic conversion is not provided by this Java engine";
    private static final String COPY_ONLY_NOTE =
            "copy=false was requested; this Java engine always copies sources and does not move or delete them";

    private final Clock clock;

    public JavaProjectWorkspace() {
        this(Clock.systemDefaultZone());
    }

    public JavaProjectWorkspace(Clock clock) {
        this.clock = clock;
    }

    @Override
    public EngineResult init(String projectName, String format, Path projectsDir, Duration ignoredTimeout) {
        try {
            validateProjectName(projectName);
            Path basePath = projectsDir.toAbsolutePath().normalize();
            String dateStr = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);

            String normalizedFormat = null;
            if (format != null && !format.isBlank()) {
                String normalized = CanvasFormats.normalize(format);
                if (!CanvasFormats.isKnown(normalized)) {
                    throw new IllegalArgumentException(
                            "Unsupported canvas format: " + format.trim()
                                    + " (available: " + CanvasFormats.sortedKeysList()
                                    + "; common alias: xhs -> xiaohongshu)");
                }
                normalizedFormat = normalized;
            }

            String projectDirName;
            if (normalizedFormat == null) {
                projectDirName = projectName + "_" + dateStr;
            } else if (Pattern.compile("_" + Pattern.quote(normalizedFormat) + "_\\d{8}$")
                    .matcher(projectName)
                    .find()) {
                projectDirName = projectName;
            } else {
                projectDirName = projectName + "_" + normalizedFormat + "_" + dateStr;
            }

            Path projectPath = basePath.resolve(projectDirName).normalize();
            if (!isWithinPath(projectPath, basePath)) {
                throw new IllegalArgumentException(
                        "Project directory must stay within the base directory: " + basePath);
            }
            if (Files.exists(projectPath)) {
                throw new FileAlreadyExistsException("Project directory already exists: " + projectPath);
            }

            createProjectTree(projectPath);
            writeReadme(projectPath, projectName, normalizedFormat, dateStr);

            StringBuilder out = new StringBuilder();
            out.append("Project created: ").append(projectPath).append('\n');
            if (normalizedFormat == null) {
                out.append(CanvasFormats.DEFERRED_CANVAS_MESSAGE).append('\n');
            } else {
                CanvasFormats.CanvasFormat info = CanvasFormats.get(normalizedFormat).orElseThrow();
                out.append("Canvas: ")
                        .append(info.name())
                        .append(" (")
                        .append(info.dimensions())
                        .append(")\n");
            }
            out.append("[OK] Project initialized: ").append(projectPath).append('\n');
            out.append("Next:\n");
            out.append("1. Put source files into sources/ (or use import-sources)\n");
            out.append("2. Save your design spec to the project root\n");
            out.append("3. Generate SVG files into svg_output/\n");

            String canvasNote = format != null && !format.isBlank() ? "; canvas=" + format : "";
            appendWorkflowNote(
                    projectPath,
                    "Project initialized: profile=default" + canvasNote + "; path=" + projectPath);

            return new EngineResult(0, out.toString(), false);
        } catch (Exception exc) {
            return failure(exc);
        }
    }

    @Override
    public EngineResult importSources(
            Path project, List<Path> sources, boolean copy, Duration ignoredTimeout) {
        try {
            Path projectDir = project.toAbsolutePath().normalize();
            if (!Files.isDirectory(projectDir)) {
                throw new java.io.FileNotFoundException("Project directory not found: " + projectDir);
            }
            if (sources == null || sources.isEmpty()) {
                throw new IllegalArgumentException("At least one source path or URL is required");
            }

            ImportSummary summary = new ImportSummary();
            if (!copy) {
                summary.notes.add(COPY_ONLY_NOTE);
            }

            Path sourcesDir = ensureSourcesDir(projectDir);
            List<String> expandedItems = new ArrayList<>();
            for (Path source : sources) {
                String item = source.toString();
                if (isUrl(item)) {
                    expandedItems.add(item);
                    continue;
                }
                Path itemPath = source.toAbsolutePath().normalize();
                if (Files.isDirectory(itemPath)) {
                    List<Path> directoryFiles;
                    try (Stream<Path> stream = Files.list(itemPath)) {
                        directoryFiles = stream.filter(Files::isRegularFile).sorted().toList();
                    }
                    if (!directoryFiles.isEmpty()) {
                        directoryFiles.forEach(path -> expandedItems.add(path.toString()));
                        summary.notes.add(
                                item + ": expanded directory into " + directoryFiles.size() + " file(s)");
                    } else {
                        summary.skipped.add(item + ": directory contains no files");
                    }
                    continue;
                }
                expandedItems.add(item);
            }

            Set<String> explicitMarkdownStems = new LinkedHashSet<>();
            for (String item : expandedItems) {
                if (isUrl(item)) {
                    continue;
                }
                Path path = Path.of(item);
                if (Files.isRegularFile(path) && MARKDOWN_SUFFIXES.contains(suffixLower(path))) {
                    explicitMarkdownStems.add(stem(path));
                }
            }

            for (String item : expandedItems) {
                if (isUrl(item)) {
                    summary.skipped.add(item + ": URL fetch is not performed by this Java engine");
                    continue;
                }

                Path sourcePath = Path.of(item).toAbsolutePath().normalize();
                if (!Files.exists(sourcePath)) {
                    summary.skipped.add(item + ": path not found");
                    continue;
                }
                if (Files.isDirectory(sourcePath)) {
                    summary.skipped.add(item + ": directories are not supported");
                    continue;
                }

                String suffix = suffixLower(sourcePath);
                if (MARKDOWN_SUFFIXES.contains(suffix)) {
                    Path duplicate = findEquivalentMarkdown(sourcePath, sourcesDir);
                    if (duplicate != null) {
                        summary.markdown.add(duplicate.toString());
                        propagateCompanionImageAssets(duplicate, projectDir);
                        summary.notes.add(item + ": skipped duplicate markdown import because equivalent content"
                                + " already exists as " + duplicate.getFileName());
                        continue;
                    }
                    MarkdownImportResult imported = importMarkdownWithAssets(sourcePath, sourcesDir);
                    summary.archived.add(imported.markdown().toString());
                    summary.markdown.add(imported.markdown().toString());
                    if (imported.assetDir() != null) {
                        summary.assets.add(imported.assetDir().toString());
                        propagateImageAssets(imported.assetDir(), projectDir);
                    }
                    if (imported.note() != null) {
                        summary.notes.add(imported.note());
                    }
                    continue;
                }

                Path archivedPath = copyFile(sourcePath, sourcesDir.resolve(sourcePath.getFileName()));
                summary.archived.add(archivedPath.toString());

                if (BITMAP_IMAGE_SUFFIXES.contains(suffix)) {
                    Path imagesDir = projectDir.resolve("images");
                    Files.createDirectories(imagesDir);
                    Path imagePath = ensureUniquePath(imagesDir.resolve(archivedPath.getFileName()));
                    Files.copy(archivedPath, imagePath, StandardCopyOption.REPLACE_EXISTING);
                    summary.images.add(imagePath.toString());
                    if (!imagePath.getFileName().equals(archivedPath.getFileName())) {
                        summary.notes.add(
                                item + ": copied runtime image as " + imagePath.getFileName()
                                        + " to avoid a filename collision");
                    }
                } else if (ImportSourceFormats.LEGACY_PPT.equals(suffix)) {
                    summary.notes.add(
                            item + ": archived only; legacy .ppt is not converted automatically. "
                                    + "Resave as .pptx to generate Markdown.");
                } else if (ImportSourceFormats.PRESENTATION.contains(suffix)) {
                    convertPresentation(item, archivedPath, projectDir, sourcesDir, explicitMarkdownStems, summary);
                } else if (ImportSourceFormats.EXCEL.contains(suffix)) {
                    convertExcel(item, archivedPath, projectDir, sourcesDir, explicitMarkdownStems, summary);
                } else if (ImportSourceFormats.DOCX_HTML.contains(suffix)) {
                    convertDocOrHtml(item, archivedPath, projectDir, sourcesDir, explicitMarkdownStems, summary);
                } else if (ImportSourceFormats.PDF.contains(suffix)) {
                    convertPdf(item, archivedPath, projectDir, sourcesDir, explicitMarkdownStems, summary);
                } else if (PYTHON_LONG_TAIL_DOCS.contains(suffix)) {
                    summary.notes.add(item + ": " + JAVA_NO_CONVERSION_NOTE);
                } else if (LEGACY_EXCEL_SUFFIXES.contains(suffix)) {
                    summary.notes.add(
                            item + ": archived only; legacy .xls is not converted automatically. "
                                    + "Resave as .xlsx to generate Markdown.");
                } else if (TABLE_TEXT_SUFFIXES.contains(suffix)) {
                    summary.notes.add(
                            item + ": archived as a plain-text table source; no Markdown conversion needed");
                } else if (".txt".equals(suffix)) {
                    Path markdownPath = normalizeTextSource(archivedPath, sourcesDir);
                    summary.markdown.add(markdownPath.toString());
                } else {
                    summary.notes.add(item + ": archived only, no automatic conversion");
                }
            }

            return buildImportResult(projectDir, summary);
        } catch (ImportConversionException exc) {
            return new EngineResult(1, "[ERROR] " + exc.getMessage(), false);
        } catch (Exception exc) {
            return failure(exc);
        }
    }

    private static void convertPresentation(
            String item,
            Path archivedPath,
            Path projectDir,
            Path sourcesDir,
            Set<String> explicitMarkdownStems,
            ImportSummary summary)
            throws Exception {
        Path analysisDir = projectDir.resolve("analysis");
        Files.createDirectories(analysisDir);
        try {
            BeautifyIntakeOps.runIntake(archivedPath, analysisDir);
            String analysisPath = analysisDir.toAbsolutePath().normalize().toString();
            if (!summary.analysis.contains(analysisPath)) {
                summary.analysis.add(analysisPath);
            }
        } catch (Exception ex) {
            throw new ImportConversionException(
                    item + ": PPTX intake failed (" + ex.getMessage() + ")", ex);
        }

        Path canonicalMarkdown = sourcesDir.resolve(stem(archivedPath) + ".md");
        if (explicitMarkdownStems.contains(stem(archivedPath))) {
            summary.notes.add(
                    item + ": skipped presentation auto-conversion because a same-stem Markdown source was provided");
            return;
        }
        if (Files.exists(canonicalMarkdown)) {
            summary.markdown.add(canonicalMarkdown.toString());
            propagateCompanionImageAssets(canonicalMarkdown, projectDir);
            summary.notes.add(
                    item + ": skipped presentation auto-conversion because " + canonicalMarkdown.getFileName()
                            + " already exists");
            return;
        }
        try {
            PptxToMarkdownConverter.convert(archivedPath, canonicalMarkdown);
            summary.markdown.add(canonicalMarkdown.toString());
            Path assetDir = canonicalMarkdown.resolveSibling(stem(canonicalMarkdown) + "_files");
            if (Files.isDirectory(assetDir)) {
                summary.assets.add(assetDir.toString());
                propagateImageAssets(assetDir, projectDir);
            }
        } catch (Exception ex) {
            throw new ImportConversionException(
                    item + ": presentation conversion failed (" + ex.getMessage() + ")", ex);
        }
    }

    private static void convertExcel(
            String item,
            Path archivedPath,
            Path projectDir,
            Path sourcesDir,
            Set<String> explicitMarkdownStems,
            ImportSummary summary)
            throws Exception {
        Path canonicalMarkdown = sourcesDir.resolve(stem(archivedPath) + ".md");
        if (explicitMarkdownStems.contains(stem(archivedPath))) {
            summary.notes.add(
                    item + ": skipped Excel auto-conversion because a same-stem Markdown source was provided");
            return;
        }
        if (Files.exists(canonicalMarkdown)) {
            summary.markdown.add(canonicalMarkdown.toString());
            propagateCompanionImageAssets(canonicalMarkdown, projectDir);
            summary.notes.add(
                    item + ": skipped Excel auto-conversion because " + canonicalMarkdown.getFileName()
                            + " already exists");
            return;
        }
        try {
            ExcelToMarkdownConverter.convert(archivedPath, canonicalMarkdown);
            summary.markdown.add(canonicalMarkdown.toString());
        } catch (Exception ex) {
            throw new ImportConversionException(
                    item + ": Excel conversion failed (" + ex.getMessage() + ")", ex);
        }
    }

    private static void convertDocOrHtml(
            String item,
            Path archivedPath,
            Path projectDir,
            Path sourcesDir,
            Set<String> explicitMarkdownStems,
            ImportSummary summary)
            throws Exception {
        Path canonicalMarkdown = sourcesDir.resolve(stem(archivedPath) + ".md");
        if (explicitMarkdownStems.contains(stem(archivedPath))) {
            summary.notes.add(
                    item + ": skipped document auto-conversion because a same-stem Markdown source was provided");
            return;
        }
        if (Files.exists(canonicalMarkdown)) {
            summary.markdown.add(canonicalMarkdown.toString());
            propagateCompanionImageAssets(canonicalMarkdown, projectDir);
            summary.notes.add(
                    item + ": skipped document auto-conversion because " + canonicalMarkdown.getFileName()
                            + " already exists");
            return;
        }
        String suffix = suffixLower(archivedPath);
        try {
            if (".docx".equals(suffix)) {
                DocxToMarkdownConverter.convert(archivedPath, canonicalMarkdown);
            } else {
                HtmlToMarkdownConverter.convert(archivedPath, canonicalMarkdown);
            }
            summary.markdown.add(canonicalMarkdown.toString());
            Path assetDir = canonicalMarkdown.resolveSibling(stem(canonicalMarkdown) + "_files");
            if (Files.isDirectory(assetDir)) {
                summary.assets.add(assetDir.toString());
                propagateImageAssets(assetDir, projectDir);
            }
        } catch (Exception ex) {
            throw new ImportConversionException(
                    item + ": document conversion failed (" + ex.getMessage() + ")", ex);
        }
    }

    private static void convertPdf(
            String item,
            Path archivedPath,
            Path projectDir,
            Path sourcesDir,
            Set<String> explicitMarkdownStems,
            ImportSummary summary)
            throws Exception {
        Path canonicalMarkdown = sourcesDir.resolve(stem(archivedPath) + ".md");
        if (explicitMarkdownStems.contains(stem(archivedPath))) {
            summary.notes.add(
                    item + ": skipped PDF auto-conversion because a same-stem Markdown source was provided");
            return;
        }
        if (Files.exists(canonicalMarkdown)) {
            summary.markdown.add(canonicalMarkdown.toString());
            Path assetDir = canonicalMarkdown.resolveSibling(stem(canonicalMarkdown) + "_files");
            if (Files.isDirectory(assetDir)) {
                summary.assets.add(assetDir.toString());
                PdfManifestPropagator.propagate(assetDir, projectDir);
            }
            summary.notes.add(
                    item + ": skipped PDF auto-conversion because " + canonicalMarkdown.getFileName()
                            + " already exists");
            return;
        }
        try {
            PdfToMarkdownConverter.convert(archivedPath, canonicalMarkdown);
            summary.markdown.add(canonicalMarkdown.toString());
            Path assetDir = canonicalMarkdown.resolveSibling(stem(canonicalMarkdown) + "_files");
            if (Files.isDirectory(assetDir)) {
                summary.assets.add(assetDir.toString());
                int copied = PdfManifestPropagator.propagate(assetDir, projectDir);
                if (copied > 0) {
                    summary.images.add(projectDir.resolve("images").toString());
                }
            }
        } catch (ImportConversionException ex) {
            throw new ImportConversionException(
                    item + ": PDF conversion failed (" + ex.getMessage() + ")", ex);
        } catch (Exception ex) {
            throw new ImportConversionException(
                    item + ": PDF conversion failed (" + ex.getMessage() + ")", ex);
        }
    }

    @Override
    public EngineResult info(Path project, Duration ignoredTimeout) {
        return new EngineResult(
                1, "[ERROR] info is still provided by the Python project workspace", false);
    }

    @Override
    public EngineResult validate(Path project, Duration ignoredTimeout) {
        return new EngineResult(
                1, "[ERROR] validate is still provided by the Python project workspace", false);
    }

    private static EngineResult buildImportResult(Path projectDir, ImportSummary summary) {
        boolean importComplete = summary.hasUsableImport();
        StringBuilder out = new StringBuilder();
        if (importComplete) {
            out.append("[OK] Imported sources into: ").append(projectDir).append('\n');
        } else {
            out.append("[ERROR] No usable sources imported into: ").append(projectDir).append('\n');
        }
        appendSection(out, "Archived originals:", summary.archived);
        appendSection(out, "Archived URL records:", summary.urlRecords);
        appendSection(out, "Normalized markdown:", summary.markdown);
        appendSection(out, "Imported asset directories:", summary.assets);
        appendSection(out, "Runtime image copies:", summary.images);
        appendSection(out, "Analysis artifacts:", summary.analysis);
        appendSection(out, "Notes:", summary.notes);
        appendSection(out, "Skipped:", summary.skipped);
        return new EngineResult(importComplete ? 0 : 1, out.toString(), false);
    }

    private static void appendSection(StringBuilder out, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        out.append('\n').append(title).append('\n');
        for (String item : items) {
            out.append("  - ").append(item).append('\n');
        }
    }

    private void validateProjectName(String projectName) {
        if (projectName == null
                || projectName.isEmpty()
                || ".".equals(projectName)
                || "..".equals(projectName)
                || Path.of(projectName).isAbsolute()
                || projectName.contains("/")
                || projectName.contains("\\")) {
            throw new IllegalArgumentException("Project name must be a single, non-absolute path component");
        }
    }

    private static void createProjectTree(Path projectPath) throws IOException {
        List<String> dirs = List.of(
                "svg_output",
                "svg_final",
                "images",
                "icons",
                "notes",
                "templates",
                "live_preview",
                SOURCE_DIRNAME,
                "analysis",
                "validation",
                "exports");
        for (String rel : dirs) {
            Files.createDirectories(projectPath.resolve(rel));
        }
    }

    private static void writeReadme(
            Path projectPath, String projectName, String normalizedFormat, String dateStr)
            throws IOException {
        String canvasSummary =
                normalizedFormat != null
                        ? "- Canvas format: " + normalizedFormat + "\n"
                        : "- " + CanvasFormats.DEFERRED_CANVAS_MESSAGE + "\n";
        String readme =
                """
                # %s

                %s- Created: %s

                ## Directories

                - `svg_output/`: raw SVG output
                - `svg_final/`: self-contained SVG visual preview; may be inserted manually as an SVG image, but PowerPoint Convert to Shape is unsupported
                - `images/`: runtime image pool; converter assets keep their original short filenames when possible
                - `icons/`: project icon set — selected library icons copied in (via icon_sync.py) plus any custom icons you add; embedded from here at export
                - `notes/`: speaker notes
                - `templates/`: project templates
                - `live_preview/`: browser preview runtime files and history (lock.json, server.log, edits.jsonl, annotations.jsonl)
                - `sources/`: source materials and normalized markdown
                - `analysis/`: machine-extracted intermediate analysis (PPTX intake, image_analysis.csv) — the pipeline's canonical must-read source/asset facts
                - `validation/`: cold workflow audit log, SVG quality reports, and PPTX postflight audit reports
                - `exports/`: final native DrawingML pptx deliverables only (timestamped); `_native_charts_tables.pptx` name with `--native-charts-and-tables`, `_narrated.pptx` name when narration audio is embedded
                - `backup/<timestamp>/`: svg_output/ archive (always written in default-flow mode; safe to delete old timestamps)
                """
                        .formatted(projectName, canvasSummary, dateStr);
        Files.writeString(projectPath.resolve("README.md"), readme, StandardCharsets.UTF_8);
    }

    private void appendWorkflowNote(Path projectPath, String message) throws IOException {
        Path logPath = projectPath.resolve("validation/workflow.log");
        Files.createDirectories(logPath.getParent());
        String timestamp =
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                        .withZone(ZoneOffset.UTC)
                        .format(clock.instant());
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String record = "\n=== " + timestamp + " NOTE id=" + id + " ===\n" + message.stripTrailing() + "\n";
        Files.writeString(
                logPath,
                record,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static Path ensureSourcesDir(Path projectDir) throws IOException {
        Path sourcesDir = projectDir.resolve(SOURCE_DIRNAME);
        Files.createDirectories(sourcesDir);
        return sourcesDir;
    }

    private static Path copyFile(Path source, Path destination) throws IOException {
        Path target = ensureUniquePath(destination);
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }

    private static Path copyTree(Path source, Path destination) throws IOException {
        Path target = ensureUniquePath(destination);
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.sorted().toList()) {
                Path relative = source.relativize(path);
                Path destPath = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destPath);
                } else {
                    Files.createDirectories(destPath.getParent());
                    Files.copy(path, destPath, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        return target;
    }

    private static Path ensureUniquePath(Path path) {
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

    private static Path normalizeTextSource(Path archivedPath, Path sourcesDir) throws IOException {
        Path target = ensureUniquePath(sourcesDir.resolve(stem(archivedPath) + ".md"));
        String content = Files.readString(archivedPath, StandardCharsets.UTF_8);
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return target;
    }

    private static String canonicalizeMarkdownContent(String content) {
        String canonical = content.replace("\r\n", "\n");
        canonical = canonical.replaceAll("(?m)^(\\s*Crawled:\\s+).*$", "$1__IGNORED__");
        canonical = canonical.replaceAll("(?m)^(\\s*Imported:\\s+).*$", "$1__IGNORED__");
        canonical = canonical.replaceAll("([^\\s\\]()\\/]+_files)/", "__ASSET_DIR__/");
        return canonical.strip();
    }

    private static Path findEquivalentMarkdown(Path sourcePath, Path sourcesDir) throws IOException {
        String sourceContent = Files.readString(sourcePath, StandardCharsets.UTF_8);
        String canonicalSource = canonicalizeMarkdownContent(sourceContent);

        try (Stream<Path> stream = Files.list(sourcesDir)) {
            List<Path> existing = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> MARKDOWN_SUFFIXES.contains(suffixLower(path)))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path existingPath : existing) {
                try {
                    if (Files.isSameFile(existingPath, sourcePath)) {
                        continue;
                    }
                } catch (IOException ignored) {
                    // fall through
                }
                String existingContent = Files.readString(existingPath, StandardCharsets.UTF_8);
                if (canonicalizeMarkdownContent(existingContent).equals(canonicalSource)) {
                    return existingPath;
                }
            }
        }
        return null;
    }

    private static Path companionAssetDir(Path sourcePath) {
        Path candidate = sourcePath.resolveSibling(stem(sourcePath) + "_files");
        return Files.isDirectory(candidate) ? candidate : null;
    }

    private MarkdownImportResult importMarkdownWithAssets(Path sourcePath, Path sourcesDir) throws IOException {
        Path archivedMarkdown = copyFile(sourcePath, sourcesDir.resolve(sourcePath.getFileName()));

        Path profileSrc = sourcePath.resolveSibling(stem(sourcePath) + ".conversion_profile.json");
        if (Files.isRegularFile(profileSrc)) {
            copyFile(profileSrc, sourcesDir.resolve(stem(archivedMarkdown) + ".conversion_profile.json"));
        }

        Path assetDir = companionAssetDir(sourcePath);
        if (assetDir == null) {
            return new MarkdownImportResult(archivedMarkdown, null, null);
        }

        Path importedAssetDir = copyTree(assetDir, sourcesDir.resolve(stem(archivedMarkdown) + "_files"));
        rewriteMarkdownAssetRefs(archivedMarkdown, assetDir.getFileName().toString(), importedAssetDir.getFileName()
                .toString());

        String note = null;
        if (!stem(archivedMarkdown).equals(stem(sourcePath))) {
            note = sourcePath + ": renamed imported markdown to " + archivedMarkdown.getFileName()
                    + " and rewrote asset references to " + importedAssetDir.getFileName() + "/";
        }
        return new MarkdownImportResult(archivedMarkdown, importedAssetDir, note);
    }

    private static void rewriteMarkdownAssetRefs(
            Path markdownPath, String originalAssetDirname, String importedAssetDirname) throws IOException {
        if (originalAssetDirname.equals(importedAssetDirname)) {
            return;
        }
        String content = Files.readString(markdownPath, StandardCharsets.UTF_8);
        String updated = content.replace(originalAssetDirname + "/", importedAssetDirname + "/");
        if (!updated.equals(content)) {
            Files.writeString(markdownPath, updated, StandardCharsets.UTF_8);
        }
    }

    private static void propagateCompanionImageAssets(Path markdownPath, Path projectDir) throws IOException {
        Path assetDir = markdownPath.resolveSibling(stem(markdownPath) + "_files");
        if (Files.isDirectory(assetDir)) {
            propagateImageAssets(assetDir, projectDir);
        }
    }

    private static void propagateImageAssets(Path assetDir, Path projectDir) throws IOException {
        Path imagesDir = projectDir.resolve("images");
        Files.createDirectories(imagesDir);
        try (Stream<Path> stream = Files.list(assetDir)) {
            for (Path sourceFile : stream.filter(Files::isRegularFile).sorted().toList()) {
                if (!IMAGE_ASSET_SUFFIXES.contains(suffixLower(sourceFile))) {
                    continue;
                }
                Path destination = ensureUniquePath(imagesDir.resolve(sourceFile.getFileName()));
                Files.copy(sourceFile, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static boolean isUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean isWithinPath(Path path, Path parent) {
        Path base = parent.toAbsolutePath().normalize();
        Path target = path.toAbsolutePath().normalize();
        if (!target.startsWith(base)) {
            return false;
        }
        Path relative = base.relativize(target);
        return !relative.startsWith("..");
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

    private static EngineResult failure(Exception exc) {
        return new EngineResult(1, "[ERROR] " + exc.getMessage(), false);
    }

    @SafeVarargs
    private static Set<String> union(Set<String> first, Set<String>... rest) {
        Set<String> combined = new LinkedHashSet<>(first);
        for (Set<String> set : rest) {
            combined.addAll(set);
        }
        return Set.copyOf(combined);
    }

    private record MarkdownImportResult(Path markdown, Path assetDir, String note) {}

    private static final class ImportSummary {
        private final List<String> archived = new ArrayList<>();
        private final List<String> urlRecords = new ArrayList<>();
        private final List<String> markdown = new ArrayList<>();
        private final List<String> assets = new ArrayList<>();
        private final List<String> images = new ArrayList<>();
        private final List<String> analysis = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();

        private boolean hasUsableImport() {
            return !archived.isEmpty()
                    || !markdown.isEmpty()
                    || !assets.isEmpty()
                    || !images.isEmpty()
                    || !analysis.isEmpty();
        }
    }
}
