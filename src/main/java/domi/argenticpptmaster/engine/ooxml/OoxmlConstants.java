// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml;

/** Shared OOXML / OPC constants for PPTX package edits. */
public final class OoxmlConstants {

    public static final String PACKAGE_REL_NS =
            "http://schemas.openxmlformats.org/package/2006/relationships";
    public static final String CONTENT_TYPES_NS =
            "http://schemas.openxmlformats.org/package/2006/content-types";
    public static final String PRESENTATION_NS =
            "http://schemas.openxmlformats.org/presentationml/2006/main";
    public static final String DRAWING_NS =
            "http://schemas.openxmlformats.org/drawingml/2006/main";
    public static final String OFFICE_REL_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    public static final String SLIDE_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide";
    public static final String NOTES_SLIDE_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide";
    public static final String NOTES_MASTER_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster";
    public static final String THEME_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme";
    public static final String SLIDE_LAYOUT_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout";
    public static final String SLIDE_MASTER_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster";

    public static final String CONTENT_TYPE_NOTES_SLIDE =
            "application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml";
    public static final String CONTENT_TYPE_NOTES_MASTER =
            "application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml";
    public static final String CONTENT_TYPE_THEME =
            "application/vnd.openxmlformats-officedocument.theme+xml";
    public static final String CONTENT_TYPE_SLIDE =
            "application/vnd.openxmlformats-officedocument.presentationml.slide+xml";

    private OoxmlConstants() {}
}
