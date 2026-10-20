// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Apply speaker notes markdown files onto an extracted PPTX package.
 *
 * <p>Mirrors {@code native_enhance_pptx_core._apply_notes} / notes content-type updates.</p>
 */
public final class NotesApplier {

    private static final Pattern NOTES_SLIDE_PART =
            Pattern.compile("^ppt/notesSlides/notesSlide([1-9]\\d*)\\.xml$");

    private NotesApplier() {}

    public static Optional<Path> resolveNotePath(Path notesDir, int index) {
        List<Path> candidates = List.of(
                notesDir.resolve("%03d.md".formatted(index)),
                notesDir.resolve("%02d.md".formatted(index)),
                notesDir.resolve(index + ".md"),
                notesDir.resolve("slide%03d.md".formatted(index)),
                notesDir.resolve("slide%02d.md".formatted(index)),
                notesDir.resolve("slide" + index + ".md"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public static Optional<String> applyNotes(Path extractDir, SlidePartRef slide, Path noteMd)
            throws IOException {
        String notesText = MarkdownNotes.toPlainText(Files.readString(noteMd, StandardCharsets.UTF_8));
        if (notesText.isEmpty()) {
            return Optional.empty();
        }

        NotesMasterSupport.ensureNotesMaster(extractDir);
        Path slideRels = RelationshipsSupport.relationshipFileForPart(extractDir, slide.partName());
        RelationshipsSupport.ensureRelsFile(slideRels);

        String notesPart = notesSlidePartForSlide(extractDir, slide).orElse(null);
        if (notesPart == null) {
            notesPart = allocateNotesSlidePart(extractDir);
            String target = relativize(slide.partName(), notesPart);
            RelationshipsSupport.appendRelationship(slideRels, OoxmlConstants.NOTES_SLIDE_REL_TYPE, target);
        }

        Path notesXmlPath = extractDir.resolve(notesPart);
        Files.createDirectories(notesXmlPath.getParent());
        Files.writeString(
                notesXmlPath,
                NotesSlideXml.createNotesSlideXml(slide.slideNumber(), notesText),
                StandardCharsets.UTF_8);

        Path notesRelsPath = RelationshipsSupport.relationshipFileForPart(extractDir, notesPart);
        Files.createDirectories(notesRelsPath.getParent());
        Files.writeString(
                notesRelsPath,
                NotesSlideXml.createNotesSlideRelsXml(slide.slideNumber()),
                StandardCharsets.UTF_8);
        return Optional.of(notesPart);
    }

    public static Set<String> applyNotesFromDirectory(Path extractDir, Path notesDir, List<SlidePartRef> slides)
            throws IOException {
        Set<String> noteParts = new HashSet<>();
        for (SlidePartRef slide : slides) {
            Optional<Path> note = resolveNotePath(notesDir, slide.index());
            if (note.isEmpty()) {
                continue;
            }
            applyNotes(extractDir, slide, note.get()).ifPresent(noteParts::add);
        }
        if (!noteParts.isEmpty()) {
            ContentTypesSupport.addNotesContentTypes(extractDir, noteParts);
        }
        return noteParts;
    }

    public static Optional<String> notesSlidePartForSlide(Path extractDir, SlidePartRef slide)
            throws IOException {
        Path slideRels = RelationshipsSupport.relationshipFileForPart(extractDir, slide.partName());
        List<String> matches = new java.util.ArrayList<>();
        for (RelationshipsSupport.Relationship rel : RelationshipsSupport.parseRelationships(slideRels)) {
            if (!OoxmlConstants.NOTES_SLIDE_REL_TYPE.equals(rel.type())) {
                continue;
            }
            if (rel.external()) {
                throw new IOException("Slide " + slide.index() + " has an external notesSlide relationship");
            }
            if (rel.target() == null || rel.target().isBlank()) {
                throw new IOException("Slide " + slide.index() + " notesSlide relationship has no Target");
            }
            String partName = SlideParts.resolveRelationshipPart(slide.partName(), rel.target());
            if (!isNotesSlidePart(partName)) {
                throw new IOException(
                        "Slide " + slide.index() + " has an unsupported notesSlide target: " + rel.target());
            }
            matches.add(partName);
        }
        if (matches.size() > 1) {
            throw new IOException("Slide " + slide.index() + " has multiple notesSlide relationships");
        }
        return matches.stream().findFirst();
    }

    static String allocateNotesSlidePart(Path extractDir) throws IOException {
        Set<Integer> used = usedNotesSlideIndices(extractDir);
        int index = 1;
        while (used.contains(index)) {
            index++;
        }
        return "ppt/notesSlides/notesSlide" + index + ".xml";
    }

    static Set<Integer> usedNotesSlideIndices(Path extractDir) throws IOException {
        Set<Integer> used = new HashSet<>();
        Path notesDir = extractDir.resolve("ppt/notesSlides");
        if (Files.isDirectory(notesDir)) {
            try (Stream<Path> files = Files.list(notesDir)) {
                files.filter(Files::isRegularFile).forEach(path -> {
                    Matcher matcher = NOTES_SLIDE_PART.matcher("ppt/notesSlides/" + path.getFileName());
                    if (matcher.matches()) {
                        used.add(Integer.parseInt(matcher.group(1)));
                    }
                });
            }
            Path relsDir = notesDir.resolve("_rels");
            if (Files.isDirectory(relsDir)) {
                try (Stream<Path> files = Files.list(relsDir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".xml.rels")).forEach(path -> {
                        String name = path.getFileName().toString().replace(".rels", "");
                        Matcher matcher = NOTES_SLIDE_PART.matcher("ppt/notesSlides/" + name);
                        if (matcher.matches()) {
                            used.add(Integer.parseInt(matcher.group(1)));
                        }
                    });
                }
            }
        }
        Path contentTypes = extractDir.resolve("[Content_Types].xml");
        if (Files.isRegularFile(contentTypes)) {
            String xml = Files.readString(contentTypes, StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile(
                            "PartName=\"/(ppt/notesSlides/notesSlide[1-9]\\d*\\.xml)\"")
                    .matcher(xml);
            while (matcher.find()) {
                Matcher part = NOTES_SLIDE_PART.matcher(matcher.group(1));
                if (part.matches()) {
                    used.add(Integer.parseInt(part.group(1)));
                }
            }
        }
        return used;
    }

    static boolean isNotesSlidePart(String partName) {
        Path path = Path.of(partName);
        return "ppt/notesSlides".equals(path.getParent() == null ? "" : path.getParent().toString().replace('\\', '/'))
                && path.getFileName() != null
                && path.getFileName().toString().endsWith(".xml")
                && !path.getFileName().toString().equals(".xml");
    }

    private static String relativize(String fromPart, String toPart) {
        Path fromDir = Path.of(fromPart).getParent();
        if (fromDir == null) {
            fromDir = Path.of("");
        }
        return fromDir.relativize(Path.of(toPart)).toString().replace('\\', '/');
    }
}
