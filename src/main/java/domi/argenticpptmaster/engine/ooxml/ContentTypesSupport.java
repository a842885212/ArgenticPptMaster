// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

/** Content-type override helpers for notes master / notes slides. */
public final class ContentTypesSupport {

    private ContentTypesSupport() {}

    public static void addNotesContentTypes(Path extractDir, Set<String> noteParts) throws IOException {
        Path path = extractDir.resolve("[Content_Types].xml");
        String contentTypes = Files.readString(path, StandardCharsets.UTF_8);
        contentTypes = addOverride(contentTypes, "ppt/theme/theme2.xml", OoxmlConstants.CONTENT_TYPE_THEME);
        contentTypes = addOverride(
                contentTypes, "ppt/notesMasters/notesMaster1.xml", OoxmlConstants.CONTENT_TYPE_NOTES_MASTER);
        for (String partName : noteParts.stream().sorted().toList()) {
            contentTypes = addOverride(contentTypes, partName, OoxmlConstants.CONTENT_TYPE_NOTES_SLIDE);
        }
        Files.writeString(path, contentTypes, StandardCharsets.UTF_8);
    }

    public static String addOverride(String contentTypes, String partName, String contentType) {
        String normalized = "/" + partName.replaceFirst("^/+", "");
        Pattern existing = Pattern.compile(
                "<Override\\b[^>]*\\bPartName=\"" + Pattern.quote(normalized) + "\"[^>]*/>");
        if (existing.matcher(contentTypes).find()) {
            return contentTypes;
        }
        String override =
                "  <Override PartName=\"" + normalized + "\" ContentType=\"" + contentType + "\"/>";
        return contentTypes.replace("</Types>", override + "\n</Types>");
    }

    public static String addSlideOverride(String contentTypes, String partName) {
        return addOverride(contentTypes, partName, OoxmlConstants.CONTENT_TYPE_SLIDE);
    }
}
