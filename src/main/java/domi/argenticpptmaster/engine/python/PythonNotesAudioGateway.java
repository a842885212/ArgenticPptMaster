package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.NotesAudioGateway;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.time.Duration;
import java.util.List;

public final class PythonNotesAudioGateway implements NotesAudioGateway {

    public static final String SCRIPT = "skills/ppt-master/scripts/notes_to_audio.py";

    private final PptMasterCommandExecutor commands;

    public PythonNotesAudioGateway(PptMasterCommandExecutor commands) {
        this.commands = commands;
    }

    @Override
    public EngineResult synthesize(List<String> arguments, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, arguments, timeout);
    }
}
