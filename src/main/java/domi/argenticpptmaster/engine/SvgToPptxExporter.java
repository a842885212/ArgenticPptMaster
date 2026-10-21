package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Exports project {@code svg_output/} into a PPTX artifact under {@code exports/}.
 *
 * <p>Native/default release export reads {@code svg_output/} (not {@code svg_final/});
 * finalize remains a preview-only stage.</p>
 */
public interface SvgToPptxExporter {

    EngineResult export(Path project, Duration timeout);
}
