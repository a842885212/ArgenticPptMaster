package domi.argenticpptmaster.engine;

import java.time.Duration;
import java.util.List;

/**
 * Notes-to-audio synthesis via external TTS.
 *
 * <p>Documented as out of converter port scope; remains a thin Python adapter.</p>
 */
public interface NotesAudioGateway {

    /**
     * Runs notes-to-audio with a fully built argument list
     * (project path plus {@code --voice}/{@code --rate}/{@code --provider}).
     */
    EngineResult synthesize(List<String> arguments, Duration timeout);
}
