package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Native-enhance init / validate / apply over an existing PPTX. */
public interface NativeEnhanceEngine {

    EngineResult init(
            Path sourcePptx,
            String name,
            Path projectDir,
            Path projectsRoot,
            Duration timeout);

    EngineResult validate(Path project, Duration timeout);

    /**
     * Runs {@code apply} with a fully built argument list
     * (including {@code apply}, project path, {@code --overwrite}, and optional flags).
     */
    EngineResult apply(List<String> arguments, Duration timeout);
}
