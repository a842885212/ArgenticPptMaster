// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.SpeakerNotesSplitter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** In-process speaker notes splitter matching {@code total_md_split.py} default CLI behavior. */
public final class JavaSpeakerNotesSplitter implements SpeakerNotesSplitter {

    private static final Pattern HEADING_RE = Pattern.compile("^(#{1,6})\\s*(.+?)\\s*$");
    private static final Pattern HR_RE = Pattern.compile("^\\s*[-*]{3,}\\s*$");
    private static final Pattern NORMALIZE_RE =
            Pattern.compile("[^0-9A-Za-z\\u4e00-\\u9fff]+");
    private static final Pattern LEADING_DIGITS = Pattern.compile("^(\\d{1,3})");
    private static final Pattern SLIDE_PAGE_PREFIX =
            Pattern.compile("^(?:slide|page|p)\\s*[-_:]?\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_PAGE_PREFIX =
            Pattern.compile("^第\\s*(\\d{1,3})\\s*[页张]");

    @Override
    public EngineResult split(Path project, Duration timeout) {
        StringBuilder output = new StringBuilder();
        if (!Files.exists(project)) {
            output.append("Error: Path does not exist: ").append(project);
            return new EngineResult(1, output.toString(), false);
        }

        Path outputDir = project.resolve("notes");
        appendVerboseHeader(output, project, outputDir);

        List<Path> svgFiles = SlideRoster.discoverSlideSvgs(project.resolve("svg_output"));
        if (svgFiles.isEmpty()) {
            output.append("Error: No SVG files found");
            return new EngineResult(1, output.toString(), false);
        }

        output.append("  Found ").append(svgFiles.size()).append(" SVG file(s)\n");

        List<String> svgStems = svgFiles.stream().map(path -> stem(path)).toList();
        Path totalMdPath = project.resolve("notes").resolve("total.md");
        ParseResult parseResult = parseTotalMd(totalMdPath, svgStems, output);
        if (parseResult.notes().isEmpty()) {
            output.append("Error: No notes content found");
            return new EngineResult(1, output.toString(), false);
        }

        output.append("  Found ").append(parseResult.notes().size()).append(" notes section(s)\n\n");

        List<String> missingNotes = new ArrayList<>();
        for (Path svgPath : svgFiles) {
            String svgStem = stem(svgPath);
            if (!parseResult.notes().containsKey(svgStem)) {
                missingNotes.add(svgStem);
            }
        }

        if (!missingNotes.isEmpty()) {
            output.append("Error: SVG files and notes do not match\n");
            output.append("  Missing notes: ").append(String.join(", ", missingNotes));
            output.append("\n\nPlease regenerate the notes file to ensure every SVG has corresponding notes.");
            return new EngineResult(1, output.toString(), false);
        }

        output.append("[OK] SVG files and notes have one-to-one correspondence\n\n");

        int successCount = writeNotes(parseResult.notes(), outputDir, output);
        if (successCount != parseResult.notes().size()) {
            output.append("\n[Failed] Notes splitting failed");
            return new EngineResult(1, output.toString(), false);
        }

        output.append("\n\n[Done] Notes splitting complete");
        return new EngineResult(0, output.toString(), false);
    }

    private static void appendVerboseHeader(StringBuilder output, Path project, Path outputDir) {
        output.append("PPT Master - Speaker Notes Splitting Tool\n");
        output.append("==================================================\n");
        output.append("  Project path: ").append(project).append('\n');
        output.append("  Output directory: ").append(outputDir).append("\n\n");
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private record ParseResult(Map<String, String> notes) {}

    private static ParseResult parseTotalMd(Path mdPath, List<String> svgStems, StringBuilder output) {
        if (!Files.isRegularFile(mdPath)) {
            output.append("Error: ").append(mdPath).append(" file does not exist\n");
            return new ParseResult(Map.of());
        }

        final String content;
        try {
            content = Files.readString(mdPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            output.append("Error: Unable to read file ").append(mdPath).append(": ").append(e.getMessage()).append('\n');
            return new ParseResult(Map.of());
        }

        Set<String> exact = new HashSet<>(svgStems);
        Map<String, List<String>> normMap = new HashMap<>();
        Map<Integer, List<String>> numMap = new HashMap<>();
        for (String stem : svgStems) {
            String norm = normalizeTitle(stem);
            if (!norm.isEmpty()) {
                normMap.computeIfAbsent(norm, key -> new ArrayList<>()).add(stem);
            }
            Integer num = extractLeadingNumber(stem);
            if (num != null) {
                numMap.computeIfAbsent(num, key -> new ArrayList<>()).add(stem);
            }
        }

        Map<String, String> notes = new LinkedHashMap<>();
        String currentKey = null;
        List<String> currentLines = new ArrayList<>();
        List<String> unmatchedHeadings = new ArrayList<>();

        for (String line : content.split("\n", -1)) {
            Matcher heading = HEADING_RE.matcher(line);
            if (heading.matches()) {
                String rawTitle = heading.group(2).strip();
                String matched = matchTitle(rawTitle, exact, normMap, numMap, svgStems);
                if (matched != null) {
                    if (currentKey != null) {
                        appendSection(notes, currentKey, joinAndStrip(currentLines));
                    }
                    currentKey = matched;
                    currentLines = new ArrayList<>();
                    continue;
                }
                unmatchedHeadings.add(rawTitle);
            }

            if (HR_RE.matcher(line).matches()) {
                continue;
            }
            if (currentKey != null) {
                currentLines.add(line);
            }
        }

        if (currentKey != null) {
            appendSection(notes, currentKey, joinAndStrip(currentLines));
        }

        if (!unmatchedHeadings.isEmpty()) {
            output.append("\n[Notice] Found unmatched headings (ignored):\n");
            int limit = Math.min(unmatchedHeadings.size(), 10);
            for (int i = 0; i < limit; i++) {
                output.append("  - ").append(unmatchedHeadings.get(i)).append('\n');
            }
            if (unmatchedHeadings.size() > 10) {
                output.append("  ... and ").append(unmatchedHeadings.size() - 10).append(" more\n");
            }
        }

        return new ParseResult(notes);
    }

    private static void appendSection(Map<String, String> notes, String key, String text) {
        if (notes.containsKey(key) && !text.isEmpty()) {
            notes.put(key, (notes.get(key).stripTrailing() + "\n\n" + text).strip());
        } else if (!notes.containsKey(key)) {
            notes.put(key, text);
        }
    }

    private static String joinAndStrip(List<String> lines) {
        return String.join("\n", lines).strip();
    }

    private static String matchTitle(
            String rawTitle,
            Set<String> exact,
            Map<String, List<String>> normMap,
            Map<Integer, List<String>> numMap,
            List<String> svgStems) {
        if (exact.contains(rawTitle)) {
            return rawTitle;
        }
        String norm = normalizeTitle(rawTitle);
        List<String> normMatches = normMap.get(norm);
        if (norm != null && !norm.isEmpty() && normMatches != null && normMatches.size() == 1) {
            return normMatches.get(0);
        }
        Integer num = extractLeadingNumber(rawTitle);
        if (num != null) {
            List<String> numMatches = numMap.get(num);
            if (numMatches != null && numMatches.size() == 1) {
                return numMatches.get(0);
            }
        }
        if (norm != null && !norm.isEmpty()) {
            List<String> candidates = new ArrayList<>();
            for (String stem : svgStems) {
                String stemNorm = normalizeTitle(stem);
                if (stemNorm.contains(norm)) {
                    candidates.add(stem);
                }
            }
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
        }
        return null;
    }

    static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String text = title.strip();
        text = NORMALIZE_RE.matcher(text).replaceAll("_");
        text = text.replaceAll("_+", "_");
        while (text.startsWith("_")) {
            text = text.substring(1);
        }
        while (text.endsWith("_")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.toLowerCase(Locale.ROOT);
    }

    static Integer extractLeadingNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped = text.strip();
        Matcher digits = LEADING_DIGITS.matcher(stripped);
        if (digits.find()) {
            return Integer.parseInt(digits.group(1));
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        Matcher slidePage = SLIDE_PAGE_PREFIX.matcher(lower);
        if (slidePage.find()) {
            return Integer.parseInt(slidePage.group(1));
        }
        Matcher chinesePage = CHINESE_PAGE_PREFIX.matcher(lower);
        if (chinesePage.find()) {
            return Integer.parseInt(chinesePage.group(1));
        }
        return null;
    }

    private static int writeNotes(Map<String, String> notes, Path outputDir, StringBuilder output) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            output.append("  Error: Unable to write file ").append(outputDir).append(": ").append(e.getMessage());
            return 0;
        }

        int successCount = 0;
        for (Map.Entry<String, String> entry : notes.entrySet()) {
            Path outputPath = outputDir.resolve(entry.getKey() + ".md");
            try {
                Files.writeString(outputPath, entry.getValue(), StandardCharsets.UTF_8);
                output.append("  Generated: ").append(outputPath.getFileName()).append('\n');
                successCount++;
            } catch (IOException e) {
                output.append("  Error: Unable to write file ").append(outputPath).append(": ").append(e.getMessage())
                        .append('\n');
            }
        }

        output.append("\n[Done] Successfully generated ")
                .append(successCount)
                .append('/')
                .append(notes.size())
                .append(" file(s)");
        return successCount;
    }
}
