// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Relationship-file helpers for slide ↔ notesSlide wiring. */
public final class RelationshipsSupport {

    private static final Pattern RID = Pattern.compile("Id=\"rId(\\d+)\"");
    private static final String EMPTY_RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\""
                    + OoxmlConstants.PACKAGE_REL_NS
                    + "\">\n</Relationships>";

    private RelationshipsSupport() {}

    public static Path relationshipFileForPart(Path extractDir, String partName) {
        Path part = Path.of(partName);
        Path parent = part.getParent() == null ? Path.of("") : part.getParent();
        return extractDir.resolve(parent).resolve("_rels").resolve(part.getFileName() + ".rels");
    }

    public static void ensureRelsFile(Path path) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, EMPTY_RELS, StandardCharsets.UTF_8);
    }

    public static String appendRelationship(Path relsPath, String relType, String target) throws IOException {
        String content = Files.readString(relsPath, StandardCharsets.UTF_8);
        int next = maxRid(content) + 1;
        String rid = "rId" + next;
        String relXml =
                "  <Relationship Id=\""
                        + rid
                        + "\" Type=\""
                        + relType
                        + "\" Target=\""
                        + escapeXml(target)
                        + "\"/>";
        content = content.replace("</Relationships>", relXml + "\n</Relationships>");
        Files.writeString(relsPath, content, StandardCharsets.UTF_8);
        return rid;
    }

    public static Optional<String> findRelationshipId(Path relsPath, String relType, String target)
            throws IOException {
        if (!Files.isRegularFile(relsPath)) {
            return Optional.empty();
        }
        String content = Files.readString(relsPath, StandardCharsets.UTF_8);
        Pattern pattern = Pattern.compile(
                "<Relationship\\b[^>]*\\bId=\"([^\"]+)\"[^>]*\\bType=\""
                        + Pattern.quote(relType)
                        + "\"[^>]*\\bTarget=\""
                        + Pattern.quote(target)
                        + "\"[^>]*/>");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        // Attribute order may vary.
        pattern = Pattern.compile(
                "<Relationship\\b[^>]*\\bType=\""
                        + Pattern.quote(relType)
                        + "\"[^>]*\\bTarget=\""
                        + Pattern.quote(target)
                        + "\"[^>]*\\bId=\"([^\"]+)\"[^>]*/>");
        matcher = pattern.matcher(content);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static List<Relationship> parseRelationships(Path relsPath) throws IOException {
        List<Relationship> relationships = new ArrayList<>();
        if (!Files.isRegularFile(relsPath)) {
            return relationships;
        }
        String content = Files.readString(relsPath, StandardCharsets.UTF_8);
        Pattern pattern = Pattern.compile(
                "<Relationship\\b([^>]*)/>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String attrs = matcher.group(1);
            String id = attr(attrs, "Id");
            String type = attr(attrs, "Type");
            String target = attr(attrs, "Target");
            String targetMode = attr(attrs, "TargetMode");
            if (id != null && type != null && target != null) {
                relationships.add(new Relationship(id, type, target, targetMode));
            }
        }
        return relationships;
    }

    public static int maxRid(String relsContent) {
        int max = 0;
        Matcher matcher = RID.matcher(relsContent);
        while (matcher.find()) {
            max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        return max;
    }

    private static String attr(String attrs, String name) {
        Matcher matcher = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(attrs);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    public record Relationship(String id, String type, String target, String targetMode) {
        public boolean external() {
            return "External".equalsIgnoreCase(targetMode);
        }
    }
}
