package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Beautify identity / intake / inventory analysis over an existing PPTX. */
public interface BeautifyAnalysisEngine {

    EngineResult identify(Path sourcePptx, Duration timeout);

    EngineResult intake(Path sourcePptx, Path analysisOutDir, Duration timeout);

    EngineResult inventory(
            Path slideLibrary,
            Optional<Path> imageManifest,
            Path inventoryOut,
            Duration timeout);
}
