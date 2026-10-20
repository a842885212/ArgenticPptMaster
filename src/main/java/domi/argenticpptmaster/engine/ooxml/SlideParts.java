// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolve ordered slide parts from an extracted PPTX package. */
public final class SlideParts {

    private static final Pattern SLIDE_NUMBER = Pattern.compile("slide(\\d+)\\.xml$");
    private static final Pattern SLD_ID = Pattern.compile(
            "<(?:p:)?sldId\\b([^>]*)/?>", Pattern.CASE_INSENSITIVE);

    private SlideParts() {}

    public static List<SlidePartRef> read(Path extractDir) throws IOException {
        Path presentation = extractDir.resolve("ppt/presentation.xml");
        Path rels = extractDir.resolve("ppt/_rels/presentation.xml.rels");
        if (!Files.isRegularFile(presentation) || !Files.isRegularFile(rels)) {
            throw new IOException("PPTX package is missing presentation.xml or its relationships");
        }

        Map<String, String> targets = new LinkedHashMap<>();
        for (RelationshipsSupport.Relationship rel : RelationshipsSupport.parseRelationships(rels)) {
            if (OoxmlConstants.SLIDE_REL_TYPE.equals(rel.type())) {
                targets.put(rel.id(), rel.target());
            } else {
                targets.putIfAbsent(rel.id(), rel.target());
            }
        }

        String presentationXml = Files.readString(presentation, StandardCharsets.UTF_8);
        List<SlidePartRef> slides = new ArrayList<>();
        Matcher matcher = SLD_ID.matcher(presentationXml);
        int index = 0;
        while (matcher.find()) {
            String rid = attr(matcher.group(1), "r:id");
            if (rid == null) {
                rid = attr(matcher.group(1), "id");
                if (rid != null && !rid.startsWith("rId")) {
                    rid = null;
                }
            }
            if (rid == null || !targets.containsKey(rid)) {
                continue;
            }
            index++;
            String partName = targetToPart(targets.get(rid));
            slides.add(new SlidePartRef(index, partName, slideNumberFromPart(partName)));
        }
        if (slides.isEmpty()) {
            throw new IOException("No slides found in presentation.xml");
        }
        return List.copyOf(slides);
    }

    public static String targetToPart(String target) {
        String cleaned = target.replaceFirst("^/+", "");
        if (cleaned.startsWith("ppt/")) {
            return cleaned;
        }
        return "ppt/" + cleaned;
    }

    public static int slideNumberFromPart(String partName) {
        Matcher matcher = SLIDE_NUMBER.matcher(partName);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Unsupported slide part name: " + partName);
        }
        return Integer.parseInt(matcher.group(1));
    }

    public static String resolveRelationshipPart(String sourcePart, String target) {
        String targetPath = target.split("#", 2)[0];
        if (targetPath.startsWith("/")) {
            return Path.of(targetPath.replaceFirst("^/+", "")).normalize().toString().replace('\\', '/');
        }
        Path base = Path.of(sourcePart).getParent();
        if (base == null) {
            base = Path.of("");
        }
        return base.resolve(targetPath).normalize().toString().replace('\\', '/');
    }

    private static String attr(String attrs, String name) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(attrs);
        return matcher.find() ? matcher.group(1) : null;
    }
}
