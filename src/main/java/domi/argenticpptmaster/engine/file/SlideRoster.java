// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Orders slide SVG filenames by numeric segments for consistent page rosters. */
public final class SlideRoster {

    private static final Pattern NUMBER_RE = Pattern.compile("(\\d+)");

    private SlideRoster() {}

    /** Return direct child {@code *.svg} files in numeric filename order. */
    public static List<Path> discoverSlideSvgs(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith(".svg"))
                    .sorted(slideFilenameComparator())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Comparator<Path> slideFilenameComparator() {
        return Comparator.comparing(SlideRoster::slideFilenameSortKey)
                .thenComparing(path -> path.getFileName().toString());
    }

    private static SortKey slideFilenameSortKey(Path path) {
        String name = path.getFileName().toString();
        String folded = name.toLowerCase(Locale.ROOT);
        List<Segment> segments = new ArrayList<>();
        Matcher matcher = NUMBER_RE.matcher(folded);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                segments.add(Segment.text(folded.substring(last, matcher.start())));
            }
            segments.add(Segment.numeric(Integer.parseInt(matcher.group(1))));
            last = matcher.end();
        }
        if (last < folded.length()) {
            segments.add(Segment.text(folded.substring(last)));
        }
        return new SortKey(segments);
    }

    private record SortKey(List<Segment> segments) implements Comparable<SortKey> {

        @Override
        public int compareTo(SortKey other) {
            int limit = Math.min(segments.size(), other.segments.size());
            for (int i = 0; i < limit; i++) {
                int cmp = segments.get(i).compareTo(other.segments.get(i));
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Integer.compare(segments.size(), other.segments.size());
        }
    }

    private record Segment(int kind, Object value) implements Comparable<Segment> {

        private static Segment numeric(int value) {
            return new Segment(0, value);
        }

        private static Segment text(String value) {
            return new Segment(1, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public int compareTo(Segment other) {
            int kindCmp = Integer.compare(kind, other.kind);
            if (kindCmp != 0) {
                return kindCmp;
            }
            if (kind == 0) {
                return Integer.compare((Integer) value, (Integer) other.value);
            }
            return ((String) value).compareTo((String) other.value);
        }
    }
}
