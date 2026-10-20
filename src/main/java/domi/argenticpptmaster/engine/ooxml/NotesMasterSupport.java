// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Ensure notesMaster + theme2 parts are wired into an extracted PPTX package. */
public final class NotesMasterSupport {

    private NotesMasterSupport() {}

    public static void ensureNotesMaster(Path extractDir) throws IOException {
        Path pptDir = extractDir.resolve("ppt");
        Path notesMastersDir = pptDir.resolve("notesMasters");
        Files.createDirectories(notesMastersDir);

        Path notesMasterPath = notesMastersDir.resolve("notesMaster1.xml");
        if (!Files.exists(notesMasterPath)) {
            Files.writeString(notesMasterPath, NotesSlideXml.createNotesMasterXml(), StandardCharsets.UTF_8);
        }

        Path themeDir = pptDir.resolve("theme");
        Files.createDirectories(themeDir);
        Path theme1 = themeDir.resolve("theme1.xml");
        Path theme2 = themeDir.resolve("theme2.xml");
        if (!Files.exists(theme2)) {
            if (!Files.exists(theme1)) {
                throw new IOException("Cannot create notes theme: ppt/theme/theme1.xml is missing");
            }
            Files.copy(theme1, theme2, StandardCopyOption.REPLACE_EXISTING);
        }

        Path notesMasterRels = notesMastersDir.resolve("_rels/notesMaster1.xml.rels");
        if (!Files.exists(notesMasterRels)) {
            Files.createDirectories(notesMasterRels.getParent());
            Files.writeString(notesMasterRels, NotesSlideXml.createNotesMasterRelsXml(), StandardCharsets.UTF_8);
        }

        Path presentationRels = pptDir.resolve("_rels/presentation.xml.rels");
        Optional<String> existing = RelationshipsSupport.findRelationshipId(
                presentationRels, OoxmlConstants.NOTES_MASTER_REL_TYPE, "notesMasters/notesMaster1.xml");
        String notesMasterRid = existing.orElseGet(() -> {
            try {
                return RelationshipsSupport.appendRelationship(
                        presentationRels,
                        OoxmlConstants.NOTES_MASTER_REL_TYPE,
                        "notesMasters/notesMaster1.xml");
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        });

        Path presentationPath = pptDir.resolve("presentation.xml");
        String presentationXml = Files.readString(presentationPath, StandardCharsets.UTF_8);
        if (presentationXml.contains("<p:notesMasterIdLst>")) {
            return;
        }
        if (!presentationXml.contains("</p:sldMasterIdLst>")) {
            throw new IOException("presentation.xml is missing p:sldMasterIdLst");
        }
        String notesMasterLst =
                "<p:notesMasterIdLst><p:notesMasterId r:id=\""
                        + notesMasterRid
                        + "\"/></p:notesMasterIdLst>";
        presentationXml = presentationXml.replaceFirst(
                "</p:sldMasterIdLst>", "</p:sldMasterIdLst>" + notesMasterLst);
        Files.writeString(presentationPath, presentationXml, StandardCharsets.UTF_8);
    }
}
