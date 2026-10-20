// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Convert Markdown speaker notes to plain text for PPTX notes slides. */
public final class MarkdownNotes {

    private static final Pattern HEADING = Pattern.compile("^#+\\s*");
    private static final Pattern BOLD_STAR = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern BOLD_UNDER = Pattern.compile("__(.+?)__");

    private MarkdownNotes() {}

    public static String toPlainText(String mdContent) {
        if (mdContent == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String line : mdContent.split("\n", -1)) {
            if (line.startsWith("#")) {
                String text = stripInlineBold(HEADING.matcher(line).replaceFirst("").strip());
                if (!text.isEmpty()) {
                    lines.add(text);
                    lines.add("");
                }
            } else if (line.strip().startsWith("- ")) {
                String itemText = stripInlineBold(line.strip().substring(2));
                lines.add("• " + itemText);
            } else if (!line.strip().isEmpty()) {
                lines.add(stripInlineBold(line.strip()));
            } else {
                lines.add("");
            }
        }

        List<String> result = new ArrayList<>();
        boolean prevEmpty = false;
        for (String line : lines) {
            if (line.isEmpty()) {
                if (!prevEmpty) {
                    result.add(line);
                }
                prevEmpty = true;
            } else {
                result.add(line);
                prevEmpty = false;
            }
        }
        return String.join("\n", result).strip();
    }

    private static String stripInlineBold(String text) {
        text = BOLD_STAR.matcher(text).replaceAll("$1");
        return BOLD_UNDER.matcher(text).replaceAll("$1");
    }
}
