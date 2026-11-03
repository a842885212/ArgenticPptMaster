package domi.argenticpptmaster.engine.file.importconvert;

import java.util.Map;
import java.util.Set;

/** Suffix sets for Java-authoritative import converters. */
public final class ImportSourceFormats {

    public static final Set<String> PRESENTATION = Set.of(
            ".pptx", ".pptm", ".ppsx", ".ppsm", ".potx", ".potm");

    public static final Set<String> EXCEL = Set.of(".xlsx", ".xlsm");

    public static final Set<String> DOCX_HTML = Set.of(".docx", ".html", ".htm");

    public static final Set<String> PDF = Set.of(".pdf");

    public static final String LEGACY_PPT = ".ppt";

    /**
     * True-pandoc document suffixes → pandoc {@code -f} format name.
     * Media extraction ({@code --extract-media}) is enabled for all of these.
     */
    public static final Map<String, String> PANDOC_DOCS = Map.of(
            ".doc", "doc",
            ".odt", "odt",
            ".rtf", "rtf",
            ".tex", "latex",
            ".latex", "latex",
            ".rst", "rst",
            ".org", "org",
            ".typ", "typst");

    public static final Set<String> PANDOC_SUFFIXES = PANDOC_DOCS.keySet();

    /** Explicitly unsupported this change; hard-reject at API and engine. */
    public static final Set<String> UNSUPPORTED_EPUB_IPYNB = Set.of(".epub", ".ipynb");

    private ImportSourceFormats() {}
}
