package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;

/** Exports {@code svg_final} into a PPTX artifact. */
public interface SvgToPptxExporter {

    EngineResult export(Path project, Duration timeout);
}
