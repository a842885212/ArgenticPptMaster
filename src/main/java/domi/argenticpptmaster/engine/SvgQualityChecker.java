package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;

/** Validates SVG output quality before finalize/export. */
public interface SvgQualityChecker {

    EngineResult check(Path project, String format, Duration timeout);
}
