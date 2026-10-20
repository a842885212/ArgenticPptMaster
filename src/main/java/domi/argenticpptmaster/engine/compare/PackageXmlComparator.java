// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.compare;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compares PPTX packages by normalized slide and notesSlide XML.
 *
 * <p>Extends the slide-only dual-run check so notes material paths can be validated
 * without relying on whole-archive bytes.</p>
 */
public final class PackageXmlComparator {

    private static final Pattern RID = Pattern.compile("rId\\d+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public record Diff(String partName, String leftNormalized, String rightNormalized) {
    }

    public record Result(boolean equal, List<Diff> diffs) {
        public static Result identical() {
            return new Result(true, List.of());
        }
    }

    public Result compare(Path leftPptx, Path rightPptx) throws IOException {
        Objects.requireNonNull(leftPptx, "leftPptx");
        Objects.requireNonNull(rightPptx, "rightPptx");
        Map<String, String> left = readParts(leftPptx);
        Map<String, String> right = readParts(rightPptx);
        List<Diff> diffs = new ArrayList<>();
        for (String name : left.keySet()) {
            if (!right.containsKey(name)) {
                diffs.add(new Diff(name, left.get(name), ""));
                continue;
            }
            String leftNorm = normalize(left.get(name));
            String rightNorm = normalize(right.get(name));
            if (!leftNorm.equals(rightNorm)) {
                diffs.add(new Diff(name, leftNorm, rightNorm));
            }
        }
        for (String name : right.keySet()) {
            if (!left.containsKey(name)) {
                diffs.add(new Diff(name, "", normalize(right.get(name))));
            }
        }
        diffs.sort(Comparator.comparing(Diff::partName));
        return diffs.isEmpty() ? Result.identical() : new Result(false, List.copyOf(diffs));
    }

    static String normalize(String xml) {
        String withoutRids = RID.matcher(xml).replaceAll("rId_");
        return WHITESPACE.matcher(withoutRids).replaceAll(" ").trim();
    }

    private static Map<String, String> readParts(Path pptx) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(pptx.toFile())) {
            List<? extends ZipEntry> entries = zip.stream()
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (!isComparedXml(name)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    parts.put(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return parts;
    }

    private static boolean isComparedXml(String name) {
        if (name.contains("/_rels/")) {
            return false;
        }
        if (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) {
            return true;
        }
        return name.startsWith("ppt/notesSlides/notesSlide") && name.endsWith(".xml");
    }
}
