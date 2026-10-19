package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;

/** Builds {@code svg_final} from {@code svg_output}. */
public interface SvgFinalizer {

    EngineResult finalizeSvg(Path project, Duration timeout);
}
