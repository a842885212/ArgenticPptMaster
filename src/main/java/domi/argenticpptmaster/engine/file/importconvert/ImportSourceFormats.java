package domi.argenticpptmaster.engine.file.importconvert;

import java.util.Set;

/** Suffix sets for Java-authoritative import converters. */
public final class ImportSourceFormats {

    public static final Set<String> PRESENTATION = Set.of(
            ".pptx", ".pptm", ".ppsx", ".ppsm", ".potx", ".potm");

    public static final Set<String> EXCEL = Set.of(".xlsx", ".xlsm");

    public static final Set<String> DOCX_HTML = Set.of(".docx", ".html", ".htm");

    public static final String LEGACY_PPT = ".ppt";

    private ImportSourceFormats() {}
}
