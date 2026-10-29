// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;

/**
 * Phase-3 spec_lock typography-size recurring-role blocking.
 *
 * <p>Only the third+ undeclared display font-size across {@code svg_output} is
 * blocking. Color / font-family drift stay informational (not recorded as failures).</p>
 */
final class SpecLockTypographyRecurrenceQualitySupport {

    static final int SPARSE_UNDECLARED_FONT_SIZE_MAX_OCCURRENCES = 2;
    static final double FONT_SIZE_ANCHOR_TOLERANCE_PX = 2.0;

    private static final Pattern LOCK_ROW = Pattern.compile("^\\|\\s*([^|]+?)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*$");
    private static final Pattern POSITIVE_NUMERIC =
            Pattern.compile("^(?=.*[1-9])(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)$");

    private SpecLockTypographyRecurrenceQualitySupport() {}

    /**
     * Pre-count undeclared sizes across all svg files, then emit blocking errors
     * per file under {@code svg_output}.
     */
    static Map<Path, List<String>> checkProject(Path project, List<Path> svgFiles, Map<Path, Document> documents) {
        Path lockPath = project.resolve("spec_lock.md");
        if (!Files.isRegularFile(lockPath)) {
            return Map.of();
        }
        TypographyAnchors anchors;
        try {
            anchors = parseTypographyAnchors(Files.readString(lockPath));
        } catch (IOException ex) {
            return Map.of();
        }
        if (anchors.lockedSizes().isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> projectOccurrences = new LinkedHashMap<>();
        for (Path svgFile : svgFiles) {
            Document doc = documents.get(svgFile);
            if (doc == null) {
                continue;
            }
            for (Map.Entry<String, Integer> entry :
                    countUndeclared(doc, anchors.lockedSizes(), anchors.anchorSizes()).entrySet()) {
                projectOccurrences.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }

        Map<Path, List<String>> failuresByFile = new LinkedHashMap<>();
        for (Path svgFile : svgFiles) {
            Document doc = documents.get(svgFile);
            if (doc == null) {
                continue;
            }
            boolean underSvgOutput = "svg_output".equals(svgFile.getParent() == null
                    ? ""
                    : svgFile.getParent().getFileName().toString());
            if (!underSvgOutput) {
                continue;
            }
            Map<String, Integer> local =
                    countUndeclared(doc, anchors.lockedSizes(), anchors.anchorSizes());
            Map<String, Integer> recurring = new LinkedHashMap<>();
            for (String value : local.keySet()) {
                int total = projectOccurrences.getOrDefault(value, local.get(value));
                if (total > SPARSE_UNDECLARED_FONT_SIZE_MAX_OCCURRENCES) {
                    recurring.put(value, total);
                }
            }
            if (recurring.isEmpty()) {
                continue;
            }
            List<String> shown = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : recurring.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                shown.add(entry.getKey() + " (" + entry.getValue() + " occurrences)");
            }
            String sizeIssue = "undeclared font-size " + String.join(", ", shown)
                    + " exceeds the sparse-display limit of "
                    + SPARSE_UNDECLARED_FONT_SIZE_MAX_OCCURRENCES + " occurrences";
            failuresByFile
                    .computeIfAbsent(svgFile, ignored -> new ArrayList<>())
                    .add("spec_lock typography-size recurrence: " + sizeIssue
                            + ". Structural text must return to its declared role band; "
                            + "a genuinely recurring display treatment needs a justified named role "
                            + "in the Design Spec and spec_lock.");
        }
        return failuresByFile;
    }

    private static Map<String, Integer> countUndeclared(
            Document document, Set<String> lockedSizes, List<Double> anchorSizes) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry :
                SvgQualityFontSizes.effectiveTextSizeCounts(document).entrySet()) {
            String value = entry.getKey();
            if (lockedSizes.contains(value)) {
                continue;
            }
            double usedPx;
            try {
                usedPx = Double.parseDouble(value);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (!Double.isFinite(usedPx) || usedPx < 0) {
                continue;
            }
            boolean nearAnchor = false;
            for (double anchor : anchorSizes) {
                if (Math.abs(usedPx - anchor) <= FONT_SIZE_ANCHOR_TOLERANCE_PX) {
                    nearAnchor = true;
                    break;
                }
            }
            if (nearAnchor) {
                continue;
            }
            counts.merge(value, entry.getValue(), Integer::sum);
        }
        return counts;
    }

    private static TypographyAnchors parseTypographyAnchors(String text) {
        Map<String, Map<String, String>> sections = parseSections(text);
        Map<String, String> typography = sections.getOrDefault("typography", Map.of());
        Set<String> locked = new LinkedHashSet<>();
        List<Double> anchors = new ArrayList<>();
        for (Map.Entry<String, String> entry : typography.entrySet()) {
            String key = entry.getKey();
            if ("font_family".equals(key) || key.endsWith("_family")) {
                continue;
            }
            String value = entry.getValue().strip();
            if (!POSITIVE_NUMERIC.matcher(value).matches()) {
                continue;
            }
            double anchor = Double.parseDouble(value);
            if (!Double.isFinite(anchor) || anchor <= 0) {
                continue;
            }
            locked.add(SvgQualityFontSizes.canonicalKey(anchor));
            anchors.add(anchor);
        }
        return new TypographyAnchors(locked, anchors);
    }

    private static Map<String, Map<String, String>> parseSections(String text) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        String current = null;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            if (line.startsWith("## ")) {
                current = line.substring(3).strip().toLowerCase(Locale.ROOT);
                sections.putIfAbsent(current, new LinkedHashMap<>());
                continue;
            }
            Matcher matcher = LOCK_ROW.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String key = matcher.group(1).strip().replace('-', '_');
            String value = matcher.group(2).strip();
            int dot = key.indexOf('.');
            String section;
            String field;
            if (dot > 0 && dot < key.length() - 1) {
                section = key.substring(0, dot).toLowerCase(Locale.ROOT);
                field = key.substring(dot + 1);
            } else if (current != null) {
                section = current;
                field = key;
            } else {
                continue;
            }
            sections.computeIfAbsent(section, ignored -> new LinkedHashMap<>()).put(field, value);
        }
        return sections;
    }

    private record TypographyAnchors(Set<String> lockedSizes, List<Double> anchorSizes) {}
}
