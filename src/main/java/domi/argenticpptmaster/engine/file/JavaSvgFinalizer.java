// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.SvgFinalizer;
import domi.argenticpptmaster.engine.file.svgfinalize.SvgFinalizeOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Java production {@code finalize_svg} authority.
 *
 * <p>Runs the pin default full pipeline (geometry materialize, icon embed, local
 * {@code <use>} expand, align/embed images, flatten tspan) and atomically publishes
 * {@code svg_final/}.</p>
 */
public final class JavaSvgFinalizer implements SvgFinalizer {

    public static final String HEADER = "[JAVA] finalize-svg";

    @Override
    public EngineResult finalizeSvg(Path project, Duration timeout) {
        try {
            if (project == null || !Files.isDirectory(project)) {
                return new EngineResult(1, HEADER + " project not found: " + project, false);
            }
            SvgFinalizeOps.FinalizeOutcome outcome = SvgFinalizeOps.finalizeProject(project);
            if (!outcome.success()) {
                return new EngineResult(1, HEADER + " " + outcome.message(), false);
            }
            return new EngineResult(
                    0,
                    HEADER + " finalized " + outcome.svgCount() + " SVG(s) -> "
                            + project.resolve("svg_final").toAbsolutePath().normalize(),
                    false);
        } catch (Exception ex) {
            return new EngineResult(1, HEADER + " failed: " + ex.getMessage(), false);
        }
    }
}
