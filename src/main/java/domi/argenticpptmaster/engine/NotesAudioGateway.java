package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Notes-to-audio synthesis via Fish Audio (production) or legacy adapters.
 *
 * <p>Semantic contract: project {@code notes/*.md} → {@code audio/<stem>.mp3}.</p>
 */
public interface NotesAudioGateway {

    /**
     * Synthesize per-slide narration for the native-enhance project directory.
     *
     * @param project project root containing {@code notes/}
     * @param voice   Fish preset key ({@code female_mandarin} / {@code male_mandarin})
     * @param timeout overall batch timeout
     */
    EngineResult synthesize(Path project, String voice, Duration timeout);
}
