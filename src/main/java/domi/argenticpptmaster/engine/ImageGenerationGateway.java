package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;

/**
 * AI image generation from a prompts manifest.
 *
 * <p>Documented as out of converter port scope; remains a thin Python adapter.</p>
 */
public interface ImageGenerationGateway {

    EngineResult generateFromManifest(Path manifest, Path outputDir, Duration timeout);
}
