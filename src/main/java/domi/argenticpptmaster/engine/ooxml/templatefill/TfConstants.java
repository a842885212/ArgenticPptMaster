// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import java.util.Set;

/** Template-fill OOXML / OPC constants beyond shared {@code OoxmlConstants}. */
public final class TfConstants {

    public static final String CHART_NS = "http://schemas.openxmlformats.org/drawingml/2006/chart";
    public static final String CHART_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart";
    public static final String PACKAGE_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/package";
    public static final String CHART_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.drawingml.chart+xml";
    public static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String DIAGRAM_URI = "http://schemas.openxmlformats.org/drawingml/2006/diagram";
    public static final String DIAGRAM_NS = "http://schemas.openxmlformats.org/drawingml/2006/diagram";
    public static final String MC_NS = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    public static final String SPREADSHEET_NS =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    public static final String LIBRARY_SCHEMA = "template_fill_pptx_library.v1";
    public static final String PLAN_SCHEMA = "template_fill_pptx_plan.v1";
    public static final String CHECK_SCHEMA = "template_fill_pptx_check.v1";
    public static final String VALIDATE_SCHEMA = "template_fill_pptx_validate.v1";

    public static final String KEEP_TRANSITION = "keep";
    public static final String DEFAULT_TRANSITION = KEEP_TRANSITION;
    public static final double DEFAULT_TRANSITION_DURATION = 0.5;

    public static final Set<String> TRANSITION_OBJECT_FIELDS =
            Set.of("effect", "effect_options", "duration", "advance_after");

    public static final Set<String> SHARED_REL_TYPES = Set.of(
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout",
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster",
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster",
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme",
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/presProps",
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/viewProps",
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/tableStyles");

    public static final String SLIDE_JUMP_ACTION = "ppaction://hlinksldjump";

    private TfConstants() {}
}
