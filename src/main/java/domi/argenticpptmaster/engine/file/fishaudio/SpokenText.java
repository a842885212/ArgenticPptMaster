package domi.argenticpptmaster.engine.file.fishaudio;

/**
 * 口播文本清洗，精确对齐上游 {@code notes_to_audio.spoken_text}：去掉 Markdown 标题行，保留其余。
 */
public final class SpokenText {

    private SpokenText() {}

    public static String fromMarkdown(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean lastWasBlank = false;
        for (String raw : markdown.split("\\R", -1)) {
            if (raw.stripLeading().startsWith("#")) {
                continue;
            }
            String line = rstrip(raw);
            if (line.isBlank()) {
                if (out.length() > 0 && !lastWasBlank) {
                    out.append('\n');
                    lastWasBlank = true;
                }
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
            lastWasBlank = false;
        }
        return out.toString().strip();
    }

    private static String rstrip(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
