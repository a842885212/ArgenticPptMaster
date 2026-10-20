package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;

/** Splits {@code notes/total.md} into per-slide notes files. */
public interface SpeakerNotesSplitter {

    EngineResult split(Path project, Duration timeout);
}
