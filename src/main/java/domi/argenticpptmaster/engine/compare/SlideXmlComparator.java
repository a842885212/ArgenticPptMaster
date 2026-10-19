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
 * Compares PPTX packages by normalized slide XML rather than whole-archive bytes.
 *
 * <p>Ignores volatile relationship ids ({@code rIdN}) and collapses whitespace so
 * dual-run checks can focus on DrawingML content.</p>
 */
public final class SlideXmlComparator {

    private static final Pattern RID = Pattern.compile("rId\\d+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public record Diff(String partName, String leftNormalized, String rightNormalized) {
    }

    public record Result(boolean equal, List<Diff> diffs) {
        public static Result equal() {
            return new Result(true, List.of());
        }
    }

    public Result compare(Path leftPptx, Path rightPptx) throws IOException {
        Objects.requireNonNull(leftPptx, "leftPptx");
        Objects.requireNonNull(rightPptx, "rightPptx");
        Map<String, String> left = readSlideParts(leftPptx);
        Map<String, String> right = readSlideParts(rightPptx);
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
                diffs.add(new Diff(name, "", right.get(name)));
            }
        }
        diffs.sort(Comparator.comparing(Diff::partName));
        return diffs.isEmpty() ? Result.equal() : new Result(false, List.copyOf(diffs));
    }

    static String normalize(String xml) {
        String withoutRids = RID.matcher(xml).replaceAll("rId_");
        return WHITESPACE.matcher(withoutRids).replaceAll(" ").trim();
    }

    private static Map<String, String> readSlideParts(Path pptx) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(pptx.toFile())) {
            List<? extends ZipEntry> entries = zip.stream()
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (!isSlideXml(name)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    parts.put(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return parts;
    }

    private static boolean isSlideXml(String name) {
        return name.startsWith("ppt/slides/slide")
                && name.endsWith(".xml")
                && !name.contains("/_rels/");
    }
}
