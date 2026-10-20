package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.SpeakerNotesSplitter;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class PythonSpeakerNotesSplitter implements SpeakerNotesSplitter {

    public static final String SCRIPT = "skills/ppt-master/scripts/total_md_split.py";

    private final PptMasterCommandExecutor commands;

    public PythonSpeakerNotesSplitter(PptMasterCommandExecutor commands) {
        this.commands = commands;
    }

    @Override
    public EngineResult split(Path project, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, List.of(project.toString()), timeout);
    }
}
